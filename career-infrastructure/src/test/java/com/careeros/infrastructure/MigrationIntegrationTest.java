package com.careeros.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.List;
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
        assertThat(result.migrationsExecuted).isEqualTo(9);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var tables = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('recruitment_event','organization','job_posting','candidate_profile','policy_rule','evidence','eligibility_assessment','opportunity','source_artifact','evidence_fragment','extraction_run','review_item','review_issue','review_action')");
             var candidates = connection.prepareStatement("select count(*) from candidate_profile where profile_version='master-spec-v1'");
             var pendingIndex = connection.prepareStatement("select indexdef from pg_indexes where schemaname='public' and indexname='idx_review_pending'");
             var nullablePayload = connection.prepareStatement("select is_nullable from information_schema.columns where table_schema='public' and table_name='extraction_run' and column_name='proposed_payload'");
             var eventConstraint = connection.prepareStatement("select count(*) from pg_constraint where conname='uk_recruitment_event_source_url'");
             var acquisitionTables = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('recruitment_source','source_crawl_run','acquired_document','acquisition_change')");
             var sourceSeeds = connection.prepareStatement("select count(*) from recruitment_source where enabled and code in ('ZJ_HRSS_INSTITUTION','HZ_HRSS_INSTITUTION')");
             var officialAttachmentHosts = connection.prepareStatement("select count(*) from recruitment_source where code in ('ZJ_HRSS_INSTITUTION','HZ_HRSS_INSTITUTION') and configuration -> 'allowedHosts' @> '[\"zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn\"]'::jsonb");
             var decisionTables = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('fit_assessment','stability_assessment','decision_assessment','assessment_dimension','organization_stability_fact')");
             var candidateInputs = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='candidate_profile' and column_name in ('skills','research_keywords','target_job_families','preferred_organization_types')");
             var decisionInputKey = connection.prepareStatement("select count(*) from pg_constraint where conname='uk_decision_assessment_input'");
             var dimensionFkIndex = connection.prepareStatement("select count(*) from pg_indexes where schemaname='public' and indexname='idx_assessment_dimension_decision'")) {
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
            try (var rows = acquisitionTables.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (var rows = sourceSeeds.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = officialAttachmentHosts.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = decisionTables.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(5); }
            try (var rows = candidateInputs.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(4); }
            try (var rows = decisionInputKey.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = dimensionFkIndex.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
        }
    }

    @Test
    void v9BackfillsLegacyJobsAndResetsAdmissionWhenContentChanges() throws Exception {
        String schema = "admission_upgrade";
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .target(MigrationVersion.fromVersion("8"))
            .load()
            .migrate();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var searchPath = connection.prepareStatement("set search_path to " + schema);
             var insertEvent = connection.prepareStatement("""
                 insert into recruitment_event
                     (id, title, recruitment_year, event_type, source_url)
                 values (?, 'legacy-event', 2026, 'PUBLIC_INSTITUTION', 'https://example.gov.cn/event')
                 """);
             var insertOrganization = connection.prepareStatement("""
                 insert into organization (id, name, organization_type)
                 values (?, 'legacy-organization', 'PUBLIC_INSTITUTION')
                 """);
             var insertJob = connection.prepareStatement("""
                 insert into job_posting
                     (id, recruitment_event_id, organization_id, title, job_family,
                      minimum_education, source_url, content_fingerprint)
                 values (?, ?, ?, 'legacy-job', 'SOFTWARE', 'BACHELOR',
                         'https://example.gov.cn/job', ?)
                 """)) {
            searchPath.execute();
            insertEvent.setObject(1, eventId);
            insertEvent.executeUpdate();
            insertOrganization.setObject(1, organizationId);
            insertOrganization.executeUpdate();
            insertJob.setObject(1, jobId);
            insertJob.setObject(2, eventId);
            insertJob.setObject(3, organizationId);
            insertJob.setString(4, "a".repeat(64));
            insertJob.executeUpdate();
        }

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .load()
            .migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var query = connection.prepareStatement("""
                 select data_quality_status, target_scope_status, reason_codes, human_verified
                 from admission_upgrade.job_admission where job_posting_id = ?
                 """);
             var verify = connection.prepareStatement("""
                 update admission_upgrade.job_admission
                 set data_quality_status='VERIFIED', target_scope_status='INCLUDED',
                     reason_codes='["TARGET_TECHNICAL_ROLE"]'::jsonb, human_verified=true
                 where job_posting_id = ?
                 """);
             var change = connection.prepareStatement("""
                 update admission_upgrade.job_posting set content_fingerprint = ? where id = ?
                 """)) {
            query.setObject(1, jobId);
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("data_quality_status")).isEqualTo("RAW");
                assertThat(rows.getString("target_scope_status")).isEqualTo("NEEDS_REVIEW");
                assertThat(rows.getString("reason_codes")).contains("LEGACY_UNVERIFIED");
                assertThat(rows.getBoolean("human_verified")).isFalse();
            }
            verify.setObject(1, jobId);
            verify.executeUpdate();
            change.setString(1, "b".repeat(64));
            change.setObject(2, jobId);
            change.executeUpdate();
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("data_quality_status")).isEqualTo("RAW");
                assertThat(rows.getString("target_scope_status")).isEqualTo("NEEDS_REVIEW");
                assertThat(rows.getString("reason_codes")).contains("CONTENT_CHANGED");
                assertThat(rows.getBoolean("human_verified")).isFalse();
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

    @Test
    void v8PreservesExistingAllowedHostsAndDeduplicatesOfficialStorageHost() throws Exception {
        String schema = "allowed_hosts_upgrade";
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .target(MigrationVersion.fromVersion("7"))
            .load()
            .migrate();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var searchPath = connection.prepareStatement("set search_path to " + schema);
             var update = connection.prepareStatement("""
                 update recruitment_source
                 set configuration = jsonb_set(
                     configuration,
                     '{allowedHosts}',
                     '["existing.example", "zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn", "zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"]'::jsonb,
                     true
                 )
                 where code in ('ZJ_HRSS_INSTITUTION', 'HZ_HRSS_INSTITUTION')
                 """)) {
            searchPath.execute();
            update.executeUpdate();
        }

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .load()
            .migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var query = connection.prepareStatement("""
                 select code, host, count(*)
                 from allowed_hosts_upgrade.recruitment_source
                 cross join lateral jsonb_array_elements_text(configuration -> 'allowedHosts') hosts(host)
                 where code in ('ZJ_HRSS_INSTITUTION', 'HZ_HRSS_INSTITUTION')
                 group by code, host
                 order by code, host
                 """);
             var rows = query.executeQuery()) {
            for (String code : List.of("HZ_HRSS_INSTITUTION", "ZJ_HRSS_INSTITUTION")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("code")).isEqualTo(code);
                assertThat(rows.getString("host")).isEqualTo("existing.example");
                assertThat(rows.getInt("count")).isEqualTo(1);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("code")).isEqualTo(code);
                assertThat(rows.getString("host"))
                    .isEqualTo("zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn");
                assertThat(rows.getInt("count")).isEqualTo(1);
            }
            assertThat(rows.next()).isFalse();
        }
    }
}
