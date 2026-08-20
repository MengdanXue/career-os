package com.careeros.infrastructure.extraction;

import com.careeros.application.ExtractionPorts.FingerprintLock;
import com.careeros.application.DecisionPorts.DecisionInputLock;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PostgresFingerprintLock implements FingerprintLock, DecisionInputLock, AutoCloseable {
    private final HikariDataSource lockDataSource;
    private final TransactionTemplate transactions;
    private final long advisoryLockTimeoutMillis;
    private final ConcurrentHashMap<String, LocalLock> localLocks = new ConcurrentHashMap<>();

    public PostgresFingerprintLock(
        DataSourceProperties dataSourceProperties,
        TransactionTemplate transactions
    ) {
        this(dataSourceProperties, transactions, 8, 5_000, 30_000);
    }

    @Autowired
    public PostgresFingerprintLock(
        DataSourceProperties dataSourceProperties,
        TransactionTemplate transactions,
        @Value("${career-os.extraction.lock-pool-size:8}") int lockPoolSize,
        @Value("${career-os.extraction.lock-connection-timeout-ms:5000}") long connectionTimeoutMillis,
        @Value("${career-os.extraction.lock-wait-timeout-ms:30000}") long advisoryLockTimeoutMillis
    ) {
        if (lockPoolSize < 1) throw new IllegalArgumentException("lockPoolSize must be positive");
        if (connectionTimeoutMillis < 250) {
            throw new IllegalArgumentException("connectionTimeoutMillis must be at least 250");
        }
        if (advisoryLockTimeoutMillis < 1) {
            throw new IllegalArgumentException("advisoryLockTimeoutMillis must be positive");
        }
        DataSourceProperties properties = Objects.requireNonNull(dataSourceProperties);
        HikariConfig lockPool = new HikariConfig();
        lockPool.setJdbcUrl(properties.determineUrl());
        lockPool.setUsername(properties.determineUsername());
        lockPool.setPassword(properties.determinePassword());
        lockPool.setDriverClassName(properties.determineDriverClassName());
        lockPool.setMaximumPoolSize(lockPoolSize);
        lockPool.setMinimumIdle(0);
        lockPool.setConnectionTimeout(connectionTimeoutMillis);
        lockPool.setPoolName("career-os-fingerprint-lock-" + Integer.toHexString(System.identityHashCode(this)));
        this.lockDataSource = new HikariDataSource(lockPool);
        this.transactions = Objects.requireNonNull(transactions);
        this.advisoryLockTimeoutMillis = advisoryLockTimeoutMillis;
    }

    @Override
    public <T> T execute(String fingerprint, Supplier<T> operation) {
        return execute(fingerprint, operation, ignored -> {});
    }

    @Override
    public <T> T execute(
        String fingerprint,
        Supplier<T> operation,
        Consumer<RuntimeException> afterRollback
    ) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(afterRollback, "afterRollback");
        LocalLock local = retain(fingerprint);
        local.lock.lock();
        try (Connection lockConnection = lockDataSource.getConnection()) {
            advisoryLock(lockConnection, fingerprint, advisoryLockTimeoutMillis);
            RuntimeException operationFailure = null;
            try {
                return executeWhileSessionLocked(operation, afterRollback);
            } catch (RuntimeException failure) {
                operationFailure = failure;
                throw failure;
            } finally {
                try {
                    advisoryUnlock(lockConnection, fingerprint);
                } catch (SQLException unlockFailure) {
                    if (operationFailure == null) throw unlockFailure;
                    operationFailure.addSuppressed(unlockFailure);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not acquire extraction fingerprint lock", exception);
        } finally {
            local.lock.unlock();
            release(fingerprint, local);
        }
    }

    private <T> T executeWhileSessionLocked(
        Supplier<T> operation,
        Consumer<RuntimeException> afterRollback
    ) {
        try {
            return transactions.execute(status -> operation.get());
        } catch (RuntimeException failure) {
            try {
                transactions.executeWithoutResult(status -> afterRollback.accept(failure));
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }

    private static void advisoryLock(
        Connection connection,
        String fingerprint,
        long timeoutMillis
    ) throws SQLException {
        long key = Long.parseUnsignedLong(fingerprint.substring(0, 16), 16);
        try (PreparedStatement timeout = connection.prepareStatement(
                "select set_config('lock_timeout', ?, false)")) {
            timeout.setString(1, timeoutMillis + "ms");
            timeout.execute();
        }
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_lock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        }
    }

    private static void advisoryUnlock(Connection connection, String fingerprint) throws SQLException {
        long key = Long.parseUnsignedLong(fingerprint.substring(0, 16), 16);
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        }
    }

    @Override
    @PreDestroy
    public void close() {
        lockDataSource.close();
    }

    private LocalLock retain(String fingerprint) {
        return localLocks.compute(fingerprint, (key, existing) -> {
            LocalLock value = existing == null ? new LocalLock() : existing;
            value.references++;
            return value;
        });
    }

    private void release(String fingerprint, LocalLock expected) {
        localLocks.computeIfPresent(fingerprint, (key, existing) -> {
            if (existing != expected) return existing;
            existing.references--;
            return existing.references == 0 ? null : existing;
        });
    }

    private static final class LocalLock {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int references;
    }
}
