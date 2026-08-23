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
        assertThat(result.migrationsExecuted).isEqualTo(19);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var tables = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('recruitment_event','organization','job_posting','candidate_profile','policy_rule','evidence','eligibility_assessment','opportunity','source_artifact','evidence_fragment','extraction_run','review_item','review_issue','review_action')");
             var candidates = connection.prepareStatement("select count(*) from candidate_profile where profile_version='profile-v18-real-education'");
             var pendingIndex = connection.prepareStatement("select indexdef from pg_indexes where schemaname='public' and indexname='idx_review_pending'");
             var nullablePayload = connection.prepareStatement("select is_nullable from information_schema.columns where table_schema='public' and table_name='extraction_run' and column_name='proposed_payload'");
             var eventConstraint = connection.prepareStatement("""
                 select count(*) from pg_constraint constraint_row
                 join pg_namespace namespace_row on namespace_row.oid=constraint_row.connamespace
                 where constraint_row.conname='uk_recruitment_event_source_url'
                   and namespace_row.nspname='public'
                 """);
             var acquisitionTables = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('recruitment_source','source_crawl_run','acquired_document','acquisition_change')");
             var sourceSeeds = connection.prepareStatement("select count(*) from recruitment_source where enabled and code in ('ZJ_HRSS_INSTITUTION','HZ_HRSS_INSTITUTION')");
             var historicalPagination = connection.prepareStatement("""
                 select count(*) from recruitment_source
                 where code in ('ZJ_HRSS_INSTITUTION','HZ_HRSS_INSTITUTION')
                   and configuration ->> 'historicalPaginationMode' = 'JCMS_PARAM_JSON'
                   and (configuration ->> 'historicalPageSize')::int = 100
                   and (configuration ->> 'historicalMaxPages')::int = 20
                 """);
             var officialAttachmentHosts = connection.prepareStatement("select count(*) from recruitment_source where code in ('ZJ_HRSS_INSTITUTION','HZ_HRSS_INSTITUTION') and configuration -> 'allowedHosts' @> '[\"zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn\"]'::jsonb");
             var decisionTables = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('fit_assessment','stability_assessment','decision_assessment','assessment_dimension','organization_stability_fact')");
             var candidateInputs = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='candidate_profile' and column_name in ('skills','research_keywords','target_job_families','preferred_organization_types')");
             var decisionInputKey = connection.prepareStatement("""
                 select count(*) from pg_constraint constraint_row
                 join pg_namespace namespace_row on namespace_row.oid=constraint_row.connamespace
                 where constraint_row.conname='uk_decision_assessment_input'
                   and namespace_row.nspname='public'
                 """);
             var dimensionFkIndex = connection.prepareStatement("select count(*) from pg_indexes where schemaname='public' and indexname='idx_assessment_dimension_decision'");
             var candidateFacts = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name='candidate_fact_confirmation'");
             var candidateFactKey = connection.prepareStatement("""
                 select count(*) from pg_constraint constraint_row
                 join pg_namespace namespace_row on namespace_row.oid=constraint_row.connamespace
                 where constraint_row.conname='candidate_fact_confirmation_pkey'
                   and namespace_row.nspname='public'
                 """);
             var officialEventFacts = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='recruitment_event' and column_name in ('application_starts_at','application_ends_at','age_reference_date','registration_url','qualification_review_ends_on','payment_ends_on','admission_ticket_starts_on','admission_ticket_ends_on','written_exam_on','written_exam_subjects','graduate_rule','overseas_degree_rule','experience_evidence_rule','employment_statement','interview_rule','legacy_workbook_snapshot')");
             var officialJobFacts = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='job_posting' and column_name in ('supervising_department','job_category','job_grade','education_requirement_text','degree_requirement','major_requirement_text','age_requirement_text','gender_requirement','candidate_scope','other_requirements','original_requirement_text','interview_ratio','professional_test_required','contact_phone')");
             var fieldEvidenceTable = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name='job_field_evidence'");
             var eventFieldEvidenceTable = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name='recruitment_event_field_evidence'");
             var fieldEvidenceIndex = connection.prepareStatement("select count(*) from pg_indexes where schemaname='public' and indexname='idx_job_field_evidence_fragment'");
             var processorVersion = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='acquired_document' and column_name='last_processor_version'")) {
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
            try (var rows = historicalPagination.executeQuery()) {
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
            try (var rows = candidateFacts.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = candidateFactKey.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = officialEventFacts.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(16); }
            try (var rows = officialJobFacts.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(14); }
            try (var rows = fieldEvidenceTable.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = eventFieldEvidenceTable.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = fieldEvidenceIndex.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = processorVersion.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
        }

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var foundationTables = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('candidate_education_record','source_year_coverage')");
             var educationRows = connection.prepareStatement("select completion_status from candidate_education_record where candidate_profile_id='01992f09-0000-7000-8000-000000000001' order by record_order");
             var coverageRows = connection.prepareStatement("select count(*) from source_year_coverage where recruitment_year between 2024 and 2026 and status='NOT_DISCOVERED'");
             var educationFactConstraint = connection.prepareStatement("select pg_get_constraintdef(oid) from pg_constraint where conrelid='candidate_fact_confirmation'::regclass and contype='c' and pg_get_constraintdef(oid) like '%fact_key%'");
             var candidateSummary = connection.prepareStatement("select highest_education, graduation_year from candidate_profile where id='01992f09-0000-7000-8000-000000000001'")) {
            try (var rows = foundationTables.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = educationRows.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("COMPLETED");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("EXPECTED");
                assertThat(rows.next()).isFalse();
            }
            try (var rows = coverageRows.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(6); }
            try (var rows = educationFactConstraint.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).contains("EDUCATION_RECORDS");
            }
            try (var rows = candidateSummary.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("highest_education")).isEqualTo("BACHELOR");
                assertThat(rows.getInt("graduation_year")).isEqualTo(2014);
            }
        }
    }

    @Test
    void v16RepairsAnExistingV15DatabaseMissingAnnouncementFieldEvidence() throws Exception {
        String schema = "official_event_evidence_repair";
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema)
            .target(MigrationVersion.fromVersion("15")).load().migrate();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var table = connection.prepareStatement("""
                 select count(*) from information_schema.tables
                 where table_schema=? and table_name='recruitment_event_field_evidence'
                 """)) {
            table.setString(1, schema);
            try (var rows = table.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
        }

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema).load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var table = connection.prepareStatement("""
                 select count(*) from information_schema.tables
                 where table_schema=? and table_name='recruitment_event_field_evidence'
                 """)) {
            table.setString(1, schema);
            try (var rows = table.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
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
                      employment_type, minimum_education, source_url, content_fingerprint)
                 values (?, ?, ?, 'legacy-job', 'SOFTWARE', 'ESTABLISHMENT', 'BACHELOR',
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
                 """);
             var removeEmploymentIdentity = connection.prepareStatement("""
                 update admission_upgrade.job_posting set employment_type = 'UNKNOWN' where id = ?
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
            verify.executeUpdate();
            removeEmploymentIdentity.setObject(1, jobId);
            removeEmploymentIdentity.executeUpdate();
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("data_quality_status")).isEqualTo("REVIEW_REQUIRED");
                assertThat(rows.getString("target_scope_status")).isEqualTo("NEEDS_REVIEW");
                assertThat(rows.getString("reason_codes")).contains("EMPLOYMENT_IDENTITY_UNKNOWN");
                assertThat(rows.getBoolean("human_verified")).isFalse();
            }
        }
    }

    @Test
    void v16RechecksOnlyAutomatedVerifiedAdmissions() throws Exception {
        String schema = "evidence_admission_upgrade";
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID verifiedJobId = UUID.randomUUID();
        UUID legacyVerifiedWithoutEvidenceId = UUID.randomUUID();
        UUID rejectedJobId = UUID.randomUUID();
        UUID failedJobId = UUID.randomUUID();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema)
            .target(MigrationVersion.fromVersion("15")).load().migrate();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var searchPath = connection.prepareStatement("set search_path to " + schema);
             var insertEvent = connection.prepareStatement("""
                 insert into recruitment_event
                     (id, title, recruitment_year, event_type, source_url)
                 values (?, 'official-event', 2026, 'PUBLIC_INSTITUTION', 'https://example.gov.cn/event')
                 """);
             var insertOrganization = connection.prepareStatement("""
                 insert into organization (id, name, organization_type)
                 values (?, 'official-organization', 'PUBLIC_INSTITUTION')
                 """);
             var insertEvidence = connection.prepareStatement("""
                 insert into evidence
                     (id, evidence_type, source_url, content_hash, captured_at)
                 values (?, 'OFFICIAL_ATTACHMENT', 'https://example.gov.cn/jobs.xlsx', ?, now())
                 """);
             var insertJob = connection.prepareStatement("""
                 insert into job_posting
                     (id, recruitment_event_id, organization_id, title, job_family,
                      employment_type, minimum_education, source_url, content_fingerprint, evidence_ids)
                 values (?, ?, ?, ?, 'SOFTWARE', 'ESTABLISHMENT', 'BACHELOR',
                         'https://example.gov.cn/event', ?, cast(? as jsonb))
                 """);
             var setAdmission = connection.prepareStatement("""
                 update job_admission set data_quality_status=?, evaluator_version='admission-v2',
                     human_verified=false where job_posting_id=?
                 """)) {
            searchPath.execute();
            insertEvent.setObject(1, eventId); insertEvent.executeUpdate();
            insertOrganization.setObject(1, organizationId); insertOrganization.executeUpdate();
            insertEvidence.setObject(1, evidenceId); insertEvidence.setString(2, "d".repeat(64));
            insertEvidence.executeUpdate();
            var jobs = List.of(verifiedJobId, rejectedJobId, failedJobId, legacyVerifiedWithoutEvidenceId);
            for (int index = 0; index < jobs.size(); index++) {
                insertJob.setObject(1, jobs.get(index));
                insertJob.setObject(2, eventId);
                insertJob.setObject(3, organizationId);
                insertJob.setString(4, "job-" + index);
                insertJob.setString(5, String.valueOf(index).repeat(64));
                insertJob.setString(6, index == 3 ? "[]" : "[\"" + evidenceId + "\"]");
                insertJob.executeUpdate();
            }
            for (var value : List.of(
                new Object[]{"VERIFIED", verifiedJobId},
                new Object[]{"REJECTED", rejectedJobId},
                new Object[]{"FAILED", failedJobId},
                new Object[]{"VERIFIED", legacyVerifiedWithoutEvidenceId})) {
                setAdmission.setString(1, (String) value[0]);
                setAdmission.setObject(2, value[1]);
                setAdmission.executeUpdate();
            }
        }

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema).load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var query = connection.prepareStatement(
                 "select data_quality_status from " + schema + ".job_admission where job_posting_id=?")) {
            assertAdmissionStatus(query, verifiedJobId, "NORMALIZED");
            assertAdmissionStatus(query, legacyVerifiedWithoutEvidenceId, "RAW");
            assertAdmissionStatus(query, rejectedJobId, "REJECTED");
            assertAdmissionStatus(query, failedJobId, "FAILED");
        }
    }

    private static void assertAdmissionStatus(
        java.sql.PreparedStatement query, UUID jobId, String expected
    ) throws Exception {
        query.setObject(1, jobId);
        try (var rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(expected);
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
