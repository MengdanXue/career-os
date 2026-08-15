package com.careeros.infrastructure.persistence;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class PostgresEventSourceLock {
    private final JdbcTemplate jdbc;

    PostgresEventSourceLock(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    void lock(String sourceUrl) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(?, 1))",
            statement -> statement.setString(1, sourceUrl),
            resultSet -> null);
    }
}
