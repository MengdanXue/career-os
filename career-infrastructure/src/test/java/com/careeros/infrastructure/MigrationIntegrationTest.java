package com.careeros.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
    void migrationsCreateExtractionAndReviewTablesWithPendingQueueIndex() throws Exception {
        var result = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
        assertThat(result.migrationsExecuted).isEqualTo(5);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var tables = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('recruitment_event','organization','job_posting','candidate_profile','policy_rule','evidence','eligibility_assessment','opportunity','source_artifact','evidence_fragment','extraction_run','review_item','review_issue','review_action')");
             var candidates = connection.prepareStatement("select count(*) from candidate_profile where profile_version='master-spec-v1'");
             var pendingIndex = connection.prepareStatement("select indexdef from pg_indexes where schemaname='public' and indexname='idx_review_pending'");
             var nullablePayload = connection.prepareStatement("select is_nullable from information_schema.columns where table_schema='public' and table_name='extraction_run' and column_name='proposed_payload'");
             var eventConstraint = connection.prepareStatement("select count(*) from pg_constraint where conname='uk_recruitment_event_source_url'")) {
            try (var rows = tables.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(14); }
            try (var rows = candidates.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = pendingIndex.executeQuery()) {
                rows.next();
                assertThat(rows.getString(1)).contains("WHERE (status = 'PENDING'::text)");
            }
            try (var rows = nullablePayload.executeQuery()) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("YES");
            }
            try (var rows = eventConstraint.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void v5RefusesAmbiguousUpgradeWhenLegacyEventsShareOneSourceUrl() throws Exception {
        String schema = "duplicate_upgrade";
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .target(MigrationVersion.fromVersion("4"))
            .load()
            .migrate();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var searchPath = connection.prepareStatement("set search_path to " + schema);
             var insert = connection.prepareStatement("""
                 insert into recruitment_event
                     (id, title, recruitment_year, event_type, source_url)
                 values (?, 'legacy-a', 2026, 'PUBLIC_INSTITUTION', 'https://duplicate.example'),
                        (?, 'legacy-b', 2026, 'PUBLIC_INSTITUTION', 'https://duplicate.example')
                 """)) {
            searchPath.execute();
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, UUID.randomUUID());
            insert.executeUpdate();
        }

        Flyway upgrade = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .load();
        assertThatThrownBy(upgrade::migrate)
            .hasStackTraceContaining("duplicate source_url values exist");
    }
}
