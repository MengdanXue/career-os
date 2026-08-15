package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquisitionPorts.SourceRunLock;
import com.careeros.domain.acquisition.SourceCrawlRun;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

@Component
public final class PostgresSourceRunLock implements SourceRunLock, AutoCloseable {
    private final HikariDataSource dataSource;
    private final long pollIntervalMillis;

    @Autowired
    public PostgresSourceRunLock(
        DataSourceProperties properties,
        @Value("${career-os.acquisition.lock-pool-size:2}") int poolSize,
        @Value("${career-os.acquisition.lock-connection-timeout-ms:5000}") long connectionTimeoutMillis,
        @Value("${career-os.acquisition.lock-poll-interval-ms:50}") long pollIntervalMillis
    ) {
        if (poolSize < 1) throw new IllegalArgumentException("poolSize must be positive");
        if (connectionTimeoutMillis < 250) throw new IllegalArgumentException("connection timeout must be at least 250 ms");
        if (pollIntervalMillis < 1) throw new IllegalArgumentException("poll interval must be positive");
        Objects.requireNonNull(properties, "properties");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.determineUrl());
        config.setUsername(properties.determineUsername());
        config.setPassword(properties.determinePassword());
        config.setDriverClassName(properties.determineDriverClassName());
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(connectionTimeoutMillis);
        config.setPoolName("career-os-source-lock-" + Integer.toHexString(System.identityHashCode(this)));
        dataSource = new HikariDataSource(config);
        this.pollIntervalMillis = pollIntervalMillis;
    }

    @Override
    public Optional<SourceCrawlRun> tryExecute(
        String sourceCode, Duration wait, Supplier<SourceCrawlRun> work
    ) {
        if (sourceCode == null || sourceCode.isBlank()) throw new IllegalArgumentException("sourceCode is required");
        Objects.requireNonNull(wait, "wait");
        Objects.requireNonNull(work, "work");
        long deadline = System.nanoTime() + Math.max(0, wait.toNanos());
        try (Connection connection = dataSource.getConnection()) {
            while (!tryAcquire(connection, sourceCode)) {
                if (System.nanoTime() >= deadline) return Optional.empty();
                sleep(Math.min(pollIntervalMillis, Math.max(1, wait.toMillis())));
            }
            RuntimeException failure = null;
            try {
                return Optional.ofNullable(work.get());
            } catch (RuntimeException exception) {
                failure = exception;
                throw exception;
            } finally {
                try { unlock(connection, sourceCode); }
                catch (SQLException unlockFailure) {
                    if (failure == null) throw new IllegalStateException("Could not release source lock", unlockFailure);
                    failure.addSuppressed(unlockFailure);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not acquire source lock", exception);
        }
    }

    private static boolean tryAcquire(Connection connection, String sourceCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select pg_try_advisory_lock(hashtextextended(?, 3))")) {
            statement.setString(1, sourceCode);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }

    private static void unlock(Connection connection, String sourceCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select pg_advisory_unlock(hashtextextended(?, 3))")) {
            statement.setString(1, sourceCode);
            statement.execute();
        }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for source lock", exception);
        }
    }

    @Override @PreDestroy public void close() { dataSource.close(); }
}
