package com.careeros.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os")
        .withUsername("career_os")
        .withPassword("career_os");

    @Test
    void migrationsCreateEightCoreTablesAndSeedCandidate() throws Exception {
        var result = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
        assertThat(result.migrationsExecuted).isEqualTo(2);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var tables = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('recruitment_event','organization','job_posting','candidate_profile','policy_rule','evidence','eligibility_assessment','opportunity')");
             var candidates = connection.prepareStatement("select count(*) from candidate_profile where profile_version='master-spec-v1'")) {
            try (var rows = tables.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(8); }
            try (var rows = candidates.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
        }
    }
}
