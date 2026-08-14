package com.careeros.infrastructure.extraction;

import com.careeros.application.ExtractionPorts.FingerprintLock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public final class PostgresFingerprintLock implements FingerprintLock {
    private final DataSource dataSource;

    public PostgresFingerprintLock(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public <T> T execute(String fingerprint, Supplier<T> operation) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(operation, "operation");
        long key = Long.parseUnsignedLong(fingerprint.substring(0, 16), 16);
        try (Connection connection = dataSource.getConnection()) {
            advisory(connection, "select pg_advisory_lock(?)", key);
            try {
                return operation.get();
            } finally {
                advisory(connection, "select pg_advisory_unlock(?)", key);
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Could not acquire extraction fingerprint lock", exception);
        }
    }

    private static void advisory(Connection connection, String sql, long key) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            statement.execute();
        }
    }
}
