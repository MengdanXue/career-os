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
    void upgradeRepairsOnlyTheKnownEmptyCandidateFactConfirmations() throws Exception {
        String schema = "repair_empty_candidate_facts";
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .target(MigrationVersion.fromVersion("23"))
            .load()
            .migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var insert = connection.prepareStatement("""
                 insert into repair_empty_candidate_facts.candidate_fact_confirmation (
                     candidate_profile_id, fact_key, status, value_fingerprint,
                     source, confirmed_at, updated_at
                 ) values
                     ('01992f09-0000-7000-8000-000000000001', 'SKILLS', 'CONFIRMED',
                      'ba768b331fd86cec803be04e56ab2b3d4c0e98ef4ee4fcd4e72ad7cce61a1d1f',
                      'USER_CONFIRMED', now(), now()),
                     ('01992f09-0000-7000-8000-000000000001', 'RESEARCH_KEYWORDS', 'CONFIRMED',
                      'ba768b331fd86cec803be04e56ab2b3d4c0e98ef4ee4fcd4e72ad7cce61a1d1f',
                      'USER_CONFIRMED', now(), now())
                 on conflict (candidate_profile_id, fact_key) do update set
                     status=excluded.status,
                     value_fingerprint=excluded.value_fingerprint,
                     source=excluded.source,
                     confirmed_at=excluded.confirmed_at,
                     updated_at=excluded.updated_at
                 """)) {
            assertThat(insert.executeUpdate()).isEqualTo(2);
        }

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .load()
            .migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var repaired = connection.prepareStatement("""
                 select fact_key, status, confirmed_at
                 from repair_empty_candidate_facts.candidate_fact_confirmation
                 where candidate_profile_id='01992f09-0000-7000-8000-000000000001'
                   and fact_key in ('SKILLS', 'RESEARCH_KEYWORDS')
                 order by fact_key
                 """);
             var profile = connection.prepareStatement("""
                 select skills::text, research_keywords::text
                 from repair_empty_candidate_facts.candidate_profile
                 where id='01992f09-0000-7000-8000-000000000001'
                 """)) {
            try (var rows = repaired.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("status")).isEqualTo("UNKNOWN");
                assertThat(rows.getObject("confirmed_at")).isNull();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("status")).isEqualTo("UNKNOWN");
                assertThat(rows.getObject("confirmed_at")).isNull();
            }
            try (var rows = profile.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("[]");
                assertThat(rows.getString(2)).isEqualTo("[]");
            }
        }
    }

    @Test
    void migrationsCreateExtractionAndReviewTablesWithPendingQueueIndex() throws Exception {
        var result = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
        assertThat(result.migrationsExecuted).isEqualTo(28);
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
              var officialEventFacts = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='recruitment_event' and column_name in ('application_starts_at','application_ends_at','age_reference_date','registration_url','qualification_review_ends_on','payment_ends_on','admission_ticket_starts_on','admission_ticket_ends_on','written_exam_on','written_exam_subjects','graduate_rule','overseas_degree_rule','experience_evidence_rule','employment_statement','interview_rule','legacy_workbook_snapshot','graduate_rule_json','written_exam_state','professional_test_state','interview_state','interview_on','interview_method','score_formula','notice_state','application_state','qualification_review_state','payment_state','admission_ticket_state','physical_exam_state','investigation_state','publication_state','appointment_state','physical_exam_rule','investigation_rule','publication_rule','appointment_rule')");
              var processStateConstraints = connection.prepareStatement("select count(*) from pg_constraint constraint_row join pg_namespace namespace_row on namespace_row.oid=constraint_row.connamespace where namespace_row.nspname='public' and constraint_row.conname in ('ck_recruitment_event_written_exam_state','ck_recruitment_event_professional_test_state','ck_recruitment_event_interview_state','ck_recruitment_event_notice_state','ck_recruitment_event_application_state','ck_recruitment_event_qualification_review_state','ck_recruitment_event_payment_state','ck_recruitment_event_admission_ticket_state','ck_recruitment_event_physical_exam_state','ck_recruitment_event_investigation_state','ck_recruitment_event_publication_state','ck_recruitment_event_appointment_state')");
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
            try (var rows = officialEventFacts.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(36); }
            try (var rows = processStateConstraints.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(12); }
            try (var rows = officialJobFacts.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(14); }
            try (var rows = fieldEvidenceTable.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = eventFieldEvidenceTable.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = fieldEvidenceIndex.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = processorVersion.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
        }

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var foundationTables = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('candidate_education_record','candidate_employment_record','source_year_coverage')");
             var educationRows = connection.prepareStatement("select completion_status from candidate_education_record where candidate_profile_id='01992f09-0000-7000-8000-000000000001' order by record_order");
             var coverageRows = connection.prepareStatement("select count(*) from source_year_coverage where recruitment_year between 2024 and 2026 and status='NOT_DISCOVERED'");
             var educationFactConstraint = connection.prepareStatement("select pg_get_constraintdef(oid) from pg_constraint where conrelid='candidate_fact_confirmation'::regclass and contype='c' and pg_get_constraintdef(oid) like '%fact_key%'");
             var candidateSummary = connection.prepareStatement("select display_name, highest_education, graduation_year, birth_day, gender, political_affiliation from candidate_profile where id='01992f09-0000-7000-8000-000000000001'");
             var planningFacts = connection.prepareStatement("select fact_key, status from candidate_fact_confirmation where candidate_profile_id='01992f09-0000-7000-8000-000000000001' and fact_key in ('BIRTH_DATE','GENDER') order by fact_key");
             var alignedFacts = connection.prepareStatement("select count(*) from candidate_fact_confirmation where candidate_profile_id='01992f09-0000-7000-8000-000000000001' and fact_key in ('HIGHEST_EDUCATION','MAJORS','GRADUATION_YEAR','PROFESSIONAL_TITLES','PREFERRED_LOCATIONS','ACCEPTED_EMPLOYMENT_TYPES','TARGET_JOB_FAMILIES','PREFERRED_ORGANIZATION_TYPES','EDUCATION_RECORDS') and status='CONFIRMED'");
             var planningTargets = connection.prepareStatement("select preferred_locations, accepted_employment_types, target_job_families from candidate_profile where id='01992f09-0000-7000-8000-000000000001'");
             var professionalTitleFingerprint = connection.prepareStatement("select value_fingerprint from candidate_fact_confirmation where candidate_profile_id='01992f09-0000-7000-8000-000000000001' and fact_key='PROFESSIONAL_TITLES'")) {
            try (var rows = foundationTables.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(3); }
            try (var rows = educationRows.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("COMPLETED");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("EXPECTED");
                assertThat(rows.next()).isFalse();
            }
            try (var rows = coverageRows.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(6); }
            try (var rows = educationFactConstraint.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).contains("EDUCATION_RECORDS", "GENDER", "POLITICAL_AFFILIATION", "EMPLOYMENT_HISTORY");
            }
            try (var rows = candidateSummary.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("display_name")).isEqualTo("测试候选人");
                assertThat(rows.getString("highest_education")).isEqualTo("BACHELOR");
                assertThat(rows.getInt("graduation_year")).isEqualTo(2014);
                assertThat(rows.getInt("birth_day")).isEqualTo(29);
                assertThat(rows.getString("gender")).isEqualTo("FEMALE");
                assertThat(rows.getString("political_affiliation")).isEqualTo("UNKNOWN");
            }
            try (var rows = planningFacts.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("fact_key")).isEqualTo("BIRTH_DATE"); assertThat(rows.getString("status")).isEqualTo("CONFIRMED");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("fact_key")).isEqualTo("GENDER"); assertThat(rows.getString("status")).isEqualTo("CONFIRMED");
                assertThat(rows.next()).isFalse();
            }
            try (var rows = alignedFacts.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(9); }
            try (var rows = planningTargets.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("preferred_locations")).contains("杭州", "浙江").doesNotContain("广东");
                assertThat(rows.getString("accepted_employment_types")).contains("ESTABLISHMENT", "PUBLIC_INSTITUTION_FORMAL");
                assertThat(rows.getString("target_job_families")).contains("INFORMATION_SYSTEMS", "SOFTWARE");
            }
            try (var rows = professionalTitleFingerprint.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("fa5bbcbd85cdb0ed57f60533c850108e08e6f358db505bdf13ec455ffa9f299c");
            }
        }
    }

    @Test
    void planningFactAlignmentDoesNotOverwriteAnEditedCandidate() throws Exception {
        String schema = "edited_candidate_upgrade";
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema);
        configuration.target(MigrationVersion.fromVersion("21")).load().migrate();
        String customFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var edit = connection.prepareStatement("update " + schema + ".candidate_profile set profile_version='user-edited-v1', preferred_locations='[\"宁波\"]'::jsonb where id='01992f09-0000-7000-8000-000000000001'");
             var confirm = connection.prepareStatement("insert into " + schema + ".candidate_fact_confirmation(candidate_profile_id,fact_key,status,value_fingerprint,source,confirmed_at,updated_at) values ('01992f09-0000-7000-8000-000000000001','PROFESSIONAL_TITLES','CONFIRMED',?,'USER_CONFIRMED',now(),now())");
             var confirmEmptySkill = connection.prepareStatement("insert into " + schema + ".candidate_fact_confirmation(candidate_profile_id,fact_key,status,value_fingerprint,source,confirmed_at,updated_at) values ('01992f09-0000-7000-8000-000000000001','SKILLS','CONFIRMED','ba768b331fd86cec803be04e56ab2b3d4c0e98ef4ee4fcd4e72ad7cce61a1d1f','USER_CONFIRMED',now(),now())")) {
            edit.executeUpdate();
            confirm.setString(1, customFingerprint);
            confirm.executeUpdate();
            confirmEmptySkill.executeUpdate();
        }

        configuration.target(MigrationVersion.LATEST).load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var candidate = connection.prepareStatement("select profile_version, preferred_locations from " + schema + ".candidate_profile where id='01992f09-0000-7000-8000-000000000001'");
             var fact = connection.prepareStatement("select value_fingerprint from " + schema + ".candidate_fact_confirmation where candidate_profile_id='01992f09-0000-7000-8000-000000000001' and fact_key='PROFESSIONAL_TITLES'");
             var emptySkill = connection.prepareStatement("select status, confirmed_at from " + schema + ".candidate_fact_confirmation where candidate_profile_id='01992f09-0000-7000-8000-000000000001' and fact_key='SKILLS'")) {
            try (var rows = candidate.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("profile_version")).isEqualTo("user-edited-v1");
                assertThat(rows.getString("preferred_locations")).contains("宁波").doesNotContain("杭州");
            }
            try (var rows = fact.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(customFingerprint);
            }
            try (var rows = emptySkill.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("status")).isEqualTo("CONFIRMED");
                assertThat(rows.getTimestamp("confirmed_at")).isNotNull();
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
