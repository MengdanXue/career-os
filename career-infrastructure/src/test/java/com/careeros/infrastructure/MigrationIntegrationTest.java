package com.careeros.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
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
    void v53OnboardsHealthCommissionWithoutDowngradingVerifiedProgress() throws Exception {
        String schema = "health_commission_idempotent_onboarding";
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema);
        configuration.target(MigrationVersion.fromVersion("52")).load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var source = connection.prepareStatement("""
                 insert into health_commission_idempotent_onboarding.recruitment_source (
                     id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
                     enabled, cron_expression, time_zone, minimum_request_interval_ms,
                     configuration, next_due_at
                 ) values (
                     '01992f09-0000-7000-8000-000000000416',
                     'HZ_HEALTH_COMMISSION', 'preexisting health commission',
                     'https://wsjkw.hangzhou.gov.cn/',
                     'https://wsjkw.hangzhou.gov.cn/legacy',
                     'OFFICIAL_GOVERNMENT', '浙江杭州', 'STATIC_HTML', true,
                     '0 5 8 * * *', 'Asia/Shanghai', 1500, '{}'::jsonb, now()
                 )
                 """);
             var target = connection.prepareStatement("""
                 insert into health_commission_idempotent_onboarding.target_source_catalog (
                     code, name, route_code, organization_type, region, authority_level,
                     official_root_url, connection_status, recruitment_source_id, enabled,
                     scope_level, scope_code, priority_tier, coverage_role, updated_at
                 ) values (
                     'HZ_HEALTH_COMMISSION', 'preexisting health commission',
                     'UNIVERSITY_HOSPITAL_IT', 'GOVERNMENT', '浙江杭州', 'OFFICIAL_AGGREGATOR',
                     'https://wsjkw.hangzhou.gov.cn/', 'CONNECTED',
                     '01992f09-0000-7000-8000-000000000416', true,
                     'CITY', 'HANGZHOU_HEALTH', 'P0', 'PRIMARY', now()
                 )
                 """);
             var coverage = connection.prepareStatement("""
                 insert into health_commission_idempotent_onboarding.source_year_coverage (
                     source_id, recruitment_year, status, discovered_count, fetched_count,
                     parsed_count, target_job_count, completion_basis, completed_at,
                     listing_page_count, filtered_count, failed_count,
                     earliest_published_on, latest_published_on, stop_reason, updated_at
                 ) values (
                     '01992f09-0000-7000-8000-000000000416', 2025, 'COMPLETE',
                     475, 475, 475, 3, 'operator verified health lifecycle', now(),
                     33, 420, 0, '2025-01-01', '2025-12-31',
                     'REPORTED_TOTAL_REACHED', now()
                 )
                 """);
             var checkpoint = connection.prepareStatement("""
                 insert into health_commission_idempotent_onboarding.source_onboarding_checkpoint (
                     source_id, checkpoint, status, evidence, verified_at
                 ) values (
                     '01992f09-0000-7000-8000-000000000416', 'CONTRACT_VERIFIED',
                     'VERIFIED', 'operator verified 201 and 274 row contracts', now()
                 )
                 """)) {
            assertThat(source.executeUpdate()).isEqualTo(1);
            assertThat(target.executeUpdate()).isEqualTo(1);
            assertThat(coverage.executeUpdate()).isEqualTo(1);
            assertThat(checkpoint.executeUpdate()).isEqualTo(1);
        }

        configuration.target(MigrationVersion.LATEST).load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var source = connection.prepareStatement("""
                 select target.connection_status,
                        jsonb_array_length(source.configuration -> 'listingEntries') as entries
                 from health_commission_idempotent_onboarding.recruitment_source source
                 join health_commission_idempotent_onboarding.target_source_catalog target
                   on target.recruitment_source_id=source.id
                 where source.code='HZ_HEALTH_COMMISSION'
                 """);
             var coverage = connection.prepareStatement("""
                 select count(*) as total,
                        count(*) filter (where recruitment_year=2025 and status='COMPLETE'
                          and discovered_count=475 and completion_basis='operator verified health lifecycle'
                          and listing_page_count=33 and stop_reason='REPORTED_TOTAL_REACHED') as preserved
                 from health_commission_idempotent_onboarding.source_year_coverage
                 where source_id='01992f09-0000-7000-8000-000000000416'
                   and recruitment_year between 2024 and 2027
                 """);
             var checkpoints = connection.prepareStatement("""
                 select count(*) as total,
                        count(*) filter (where checkpoint='CONTRACT_VERIFIED'
                          and status='VERIFIED'
                          and evidence='operator verified 201 and 274 row contracts') as preserved
                 from health_commission_idempotent_onboarding.source_onboarding_checkpoint
                 where source_id='01992f09-0000-7000-8000-000000000416'
                 """)) {
            try (var rows = source.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("connection_status")).isEqualTo("CONNECTED");
                assertThat(rows.getInt("entries")).isEqualTo(2);
            }
            try (var rows = coverage.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("total")).isEqualTo(4);
                assertThat(rows.getInt("preserved")).isEqualTo(1);
            }
            try (var rows = checkpoints.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("total")).isEqualTo(5);
                assertThat(rows.getInt("preserved")).isEqualTo(1);
            }
        }
    }

    @Test
    void v49OnboardsFuyangWithoutDestroyingPreexistingProgress() throws Exception {
        String schema = "fuyang_idempotent_onboarding";
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema);
        configuration.target(MigrationVersion.fromVersion("48")).load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var source = connection.prepareStatement("""
                 insert into fuyang_idempotent_onboarding.recruitment_source (
                     id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
                     enabled, cron_expression, time_zone, minimum_request_interval_ms,
                     configuration, next_due_at
                 ) values (
                     '01992f09-0000-7000-8000-000000000412',
                     'HZ_FUYANG_GOV', 'preexisting fuyang',
                     'https://www.fuyang.gov.cn/', 'https://www.fuyang.gov.cn/legacy',
                     'OFFICIAL_GOVERNMENT', '杭州富阳', 'STATIC_HTML', true,
                     '0 20 9 * * *', 'Asia/Shanghai', 1500, '{}'::jsonb, now()
                 )
                 """);
             var coverage = connection.prepareStatement("""
                 insert into fuyang_idempotent_onboarding.source_year_coverage (
                     source_id, recruitment_year, status, discovered_count, fetched_count,
                     parsed_count, target_job_count, completion_basis, completed_at,
                     listing_page_count, filtered_count, failed_count,
                     earliest_published_on, latest_published_on, stop_reason, updated_at
                 ) values (
                     '01992f09-0000-7000-8000-000000000412', 2025, 'COMPLETE',
                     59, 59, 59, 8, 'controlled backfill evidence', now(),
                     4, 20, 0, '2025-01-01', '2025-12-31',
                     'REPORTED_TOTAL_REACHED', now()
                 )
                 """);
             var checkpoint = connection.prepareStatement("""
                 insert into fuyang_idempotent_onboarding.source_onboarding_checkpoint (
                     source_id, checkpoint, status, evidence, verified_at
                 ) values (
                     '01992f09-0000-7000-8000-000000000412', 'CONTRACT_VERIFIED',
                     'VERIFIED', 'operator-verified 59 and 177 row contracts', now()
                 )
                 """)) {
            assertThat(source.executeUpdate()).isEqualTo(1);
            assertThat(coverage.executeUpdate()).isEqualTo(1);
            assertThat(checkpoint.executeUpdate()).isEqualTo(1);
        }

        configuration.target(MigrationVersion.LATEST).load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var source = connection.prepareStatement("""
                 select source.enabled,
                        jsonb_array_length(source.configuration -> 'listingEntries') as entries,
                        target.connection_status
                 from fuyang_idempotent_onboarding.recruitment_source source
                 join fuyang_idempotent_onboarding.target_source_catalog target
                   on target.recruitment_source_id=source.id
                 where source.code='HZ_FUYANG_GOV'
                 """);
             var coverage = connection.prepareStatement("""
                 select count(*) as total,
                        count(*) filter (where recruitment_year=2025
                          and status='COMPLETE' and discovered_count=59
                          and fetched_count=59 and parsed_count=59 and target_job_count=8
                          and completion_basis='controlled backfill evidence'
                          and listing_page_count=4 and filtered_count=20 and failed_count=0
                          and earliest_published_on='2025-01-01'
                          and latest_published_on='2025-12-31'
                          and stop_reason='REPORTED_TOTAL_REACHED') as preserved
                 from fuyang_idempotent_onboarding.source_year_coverage
                 where source_id='01992f09-0000-7000-8000-000000000412'
                   and recruitment_year between 2024 and 2027
                 """);
             var checkpoints = connection.prepareStatement("""
                 select count(*) as total,
                        count(*) filter (where checkpoint='REGISTERED'
                          and status='VERIFIED') as registered,
                        count(*) filter (where checkpoint='CONTRACT_VERIFIED'
                          and status='VERIFIED'
                          and evidence='operator-verified 59 and 177 row contracts') as preserved
                 from fuyang_idempotent_onboarding.source_onboarding_checkpoint
                 where source_id='01992f09-0000-7000-8000-000000000412'
                 """)) {
            try (var rows = source.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean("enabled")).isTrue();
                assertThat(rows.getInt("entries")).isEqualTo(2);
                assertThat(rows.getString("connection_status")).isEqualTo("PARTIAL");
            }
            try (var rows = coverage.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("total")).isEqualTo(4);
                assertThat(rows.getInt("preserved")).isEqualTo(1);
            }
            try (var rows = checkpoints.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("total")).isEqualTo(5);
                assertThat(rows.getInt("registered")).isEqualTo(1);
                assertThat(rows.getInt("preserved")).isEqualTo(1);
            }
        }
    }

    @Test
    void v48InvalidatesXixiCoverageAfterCorrectingTheHtmlRequestContract() throws Exception {
        String schema = "xixi_request_contract_upgrade";
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema);
        configuration.target(MigrationVersion.fromVersion("47")).load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var legacy = connection.prepareStatement("""
                 update xixi_request_contract_upgrade.source_year_coverage coverage
                 set status='COMPLETE', discovered_count=9, fetched_count=9,
                     parsed_count=9, target_job_count=2,
                     completion_basis='legacy ajax evidence', completed_at=now(),
                     listing_page_count=3, filtered_count=1, failed_count=0,
                     earliest_published_on='2024-01-01', latest_published_on='2024-12-31',
                     stop_reason='LEGACY_AJAX_EVIDENCE', updated_at=now()
                 from xixi_request_contract_upgrade.recruitment_source source
                 where coverage.source_id=source.id
                   and source.code='HZ_XIXI_HOSPITAL'
                   and coverage.recruitment_year=2024
                 """)) {
            assertThat(legacy.executeUpdate()).isEqualTo(1);
        }

        configuration.target(MigrationVersion.LATEST).load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var coverage = connection.prepareStatement("""
                 select count(*) as total,
                        count(*) filter (where status='NOT_DISCOVERED'
                          and discovered_count=0 and fetched_count=0 and parsed_count=0
                          and target_job_count=0 and completion_basis is null
                          and completed_at is null and listing_page_count=0
                          and filtered_count=0 and failed_count=0
                          and earliest_published_on is null and latest_published_on is null
                          and stop_reason is null) as reset_count
                 from xixi_request_contract_upgrade.source_year_coverage coverage
                 join xixi_request_contract_upgrade.recruitment_source source
                   on source.id=coverage.source_id
                 where source.code='HZ_XIXI_HOSPITAL'
                   and recruitment_year between 2024 and 2027
                 """)) {
            try (var rows = coverage.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("total")).isEqualTo(4);
                assertThat(rows.getInt("reset_count")).isEqualTo(4);
            }
        }
    }

    @Test
    void v47InvalidatesLegacyFirstHospitalCoverageBeforeUsingTheNewContract() throws Exception {
        String schema = "first_hospital_contract_upgrade";
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema);
        configuration.target(MigrationVersion.fromVersion("46")).load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var legacy = connection.prepareStatement("""
                 update first_hospital_contract_upgrade.source_year_coverage coverage
                 set status='COMPLETE', discovered_count=9, fetched_count=9,
                     parsed_count=9, target_job_count=2,
                     completion_basis='legacy fixed evidence', completed_at=now(),
                     listing_page_count=3, filtered_count=1, failed_count=0,
                     earliest_published_on='2024-01-01', latest_published_on='2024-12-31',
                     stop_reason='LEGACY_FIXED_EVIDENCE', updated_at=now()
                 from first_hospital_contract_upgrade.recruitment_source source
                 where coverage.source_id=source.id
                   and source.code='HZ_FIRST_HOSPITAL'
                   and coverage.recruitment_year=2024
                 """)) {
            assertThat(legacy.executeUpdate()).isEqualTo(1);
        }

        configuration.target(MigrationVersion.LATEST).load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var coverage = connection.prepareStatement("""
                 select recruitment_year, status, discovered_count, fetched_count,
                        parsed_count, target_job_count, completion_basis, completed_at,
                        listing_page_count, filtered_count, failed_count,
                        earliest_published_on, latest_published_on, stop_reason
                 from first_hospital_contract_upgrade.source_year_coverage coverage
                 join first_hospital_contract_upgrade.recruitment_source source
                   on source.id=coverage.source_id
                 where source.code='HZ_FIRST_HOSPITAL'
                   and recruitment_year between 2024 and 2027
                 order by recruitment_year
                 """)) {
            try (var rows = coverage.executeQuery()) {
                for (int year = 2024; year <= 2027; year++) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt("recruitment_year")).isEqualTo(year);
                    assertThat(rows.getString("status")).isEqualTo("NOT_DISCOVERED");
                    assertThat(rows.getInt("discovered_count")).isZero();
                    assertThat(rows.getInt("fetched_count")).isZero();
                    assertThat(rows.getInt("parsed_count")).isZero();
                    assertThat(rows.getInt("target_job_count")).isZero();
                    assertThat(rows.getString("completion_basis")).isNull();
                    assertThat(rows.getTimestamp("completed_at")).isNull();
                    assertThat(rows.getInt("listing_page_count")).isZero();
                    assertThat(rows.getInt("filtered_count")).isZero();
                    assertThat(rows.getInt("failed_count")).isZero();
                    assertThat(rows.getDate("earliest_published_on")).isNull();
                    assertThat(rows.getDate("latest_published_on")).isNull();
                    assertThat(rows.getString("stop_reason")).isNull();
                }
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void migrationsRegisterFirstHttpsBatchAsPartialUntilBackfillVerification() throws Exception {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sources = connection.prepareStatement("""
                 select count(*)
                 from recruitment_source
                 where enabled
                   and code in ('HZ_TCM_HOSPITAL','HZ_XIXI_HOSPITAL','HZ_DATA_GROUP')
                   and jsonb_array_length(configuration -> 'listingEntries') > 0
                 """);
             var targets = connection.prepareStatement("""
                 select count(*)
                 from target_source_catalog
                 where code in ('HZ_TCM_HOSPITAL','HZ_XIXI_HOSPITAL','HZ_DATA_GROUP')
                   and connection_status='PARTIAL'
                   and recruitment_source_id is not null
                 """);
             var checkpoints = connection.prepareStatement("""
                 select count(*) as total,
                        count(*) filter (where checkpoint='REGISTERED' and status='VERIFIED') as registered,
                        count(*) filter (where checkpoint<>'REGISTERED' and status='PENDING') as pending
                 from source_onboarding_checkpoint checkpoint
                 join recruitment_source source on source.id=checkpoint.source_id
                 where source.code in ('HZ_TCM_HOSPITAL','HZ_XIXI_HOSPITAL','HZ_DATA_GROUP')
                 """)) {
            try (var rows = sources.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(3);
            }
            try (var rows = targets.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(3);
            }
            try (var rows = checkpoints.executeQuery()) {
                rows.next();
                assertThat(rows.getInt("total")).isEqualTo(15);
                assertThat(rows.getInt("registered")).isEqualTo(3);
                assertThat(rows.getInt("pending")).isEqualTo(12);
            }
        }
    }

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
        assertThat(result.migrationsExecuted).isEqualTo(88);
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
             var waveOneSourceSeeds = connection.prepareStatement("select count(*) from recruitment_source where enabled and code in ('HDU_RECRUITMENT','ZJGSU_RECRUITMENT') and configuration ->> 'historicalPaginationMode'='STATIC_PAGE_SUFFIX' and configuration ->> 'adapterType'='STATIC_HTML'");
             var waveOneTargetState = connection.prepareStatement("select count(*) from target_source_catalog where code in ('HDU_RECRUITMENT','ZJGSU_RECRUITMENT') and connection_status='PARTIAL' and recruitment_source_id is not null");
             var legacyConnectedTargets = connection.prepareStatement("select count(*) from target_source_catalog where code in ('ZJ_HRSS_INSTITUTION','HZ_HRSS_INSTITUTION') and connection_status='CONNECTED'");
             var hospitalSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.enabled and source.code='HZ_FIRST_HOSPITAL' and source.entry_uri='https://www.hz-hospital.com/' and jsonb_array_length(source.configuration -> 'listingEntries')=1 and source.configuration -> 'listingEntries' -> 0 -> 'exactAuthorities' @> '[\"124.160.72.42:8080\"]'::jsonb and source.configuration::text not like '%zp.hz-hospital.com%' and target.connection_status='PARTIAL'");
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
             var officialJobFacts = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='job_posting' and column_name in ('supervising_department','job_category','job_grade','education_requirement_text','degree_requirement','major_requirement_text','age_requirement_text','gender_requirement','candidate_scope','other_requirements','original_requirement_text','interview_ratio','professional_test_required','contact_phone','actual_employer','worksite','employment_identity_evidence')");
             var fieldEvidenceTable = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name='job_field_evidence'");
             var eventFieldEvidenceTable = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name='recruitment_event_field_evidence'");
             var fieldEvidenceIndex = connection.prepareStatement("select count(*) from pg_indexes where schemaname='public' and indexname='idx_job_field_evidence_fragment'");
              var processorVersion = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='acquired_document' and column_name='last_processor_version'");
              var reviewActor = connection.prepareStatement("select is_nullable from information_schema.columns where table_schema='public' and table_name='review_action' and column_name='actor'");
              var reviewActorIndex = connection.prepareStatement("select count(*) from pg_indexes where schemaname='public' and indexname='idx_review_action_actor'");
              var eligibilityStatusCheck = connection.prepareStatement("select count(*) from pg_constraint constraint_row join pg_namespace namespace_row on namespace_row.oid=constraint_row.connamespace where namespace_row.nspname='public' and constraint_row.conname in ('ck_eligibility_assessment_status','ck_decision_assessment_eligibility_status')");
              var legacyEligibilityValues = connection.prepareStatement("select count(*) from eligibility_assessment where status in ('LIKELY_ELIGIBLE','LIKELY_INELIGIBLE','UNCERTAIN')");
              var applicationTimeColumns = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='candidate_profile' and column_name in ('employer_settlement_at_application','social_insurance_at_application') and column_default like '%UNDECLARED%'");
              var applicationTimeFactKeys = connection.prepareStatement("select pg_get_constraintdef(constraint_row.oid) from pg_constraint constraint_row where constraint_row.conname='candidate_fact_confirmation_fact_key_check'");
              var confirmationLedgerKey = connection.prepareStatement("select string_agg(column_name, ',' order by ordinal_position) from information_schema.key_column_usage key_usage join information_schema.table_constraints table_constraint on table_constraint.constraint_name=key_usage.constraint_name and table_constraint.constraint_schema=key_usage.constraint_schema and table_constraint.table_name=key_usage.table_name where table_constraint.table_schema='public' and table_constraint.table_name='profile_confirmation_ledger' and table_constraint.constraint_type='PRIMARY KEY'");
              var confirmationLedgerStages = connection.prepareStatement("select pg_get_constraintdef(constraint_row.oid) from pg_constraint constraint_row join pg_namespace namespace_row on namespace_row.oid=constraint_row.connamespace where namespace_row.nspname='public' and constraint_row.conname='ck_profile_confirmation_stage'");
              var confirmationLedgerFactKeys = connection.prepareStatement("select pg_get_constraintdef(constraint_row.oid) from pg_constraint constraint_row join pg_namespace namespace_row on namespace_row.oid=constraint_row.connamespace where namespace_row.nspname='public' and constraint_row.conname='ck_profile_confirmation_fact_key'");
              var agentSessionColumns = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='agent_session' and column_name in ('filter_tier','filter_location','filter_job_family','filter_limit','last_job_ids','pending_confirmations','profile_version')");
              var agentSessionJsonArrays = connection.prepareStatement("select count(*) from pg_constraint constraint_row join pg_namespace namespace_row on namespace_row.oid=constraint_row.connamespace where namespace_row.nspname='public' and constraint_row.conname in ('ck_agent_session_last_job_ids','ck_agent_session_pending')");
              var watchlistKey = connection.prepareStatement("select string_agg(column_name, ',' order by ordinal_position) from information_schema.key_column_usage key_usage join information_schema.table_constraints table_constraint on table_constraint.constraint_name=key_usage.constraint_name and table_constraint.constraint_schema=key_usage.constraint_schema and table_constraint.table_name=key_usage.table_name where table_constraint.table_schema='public' and table_constraint.table_name='candidate_job_watch' and table_constraint.constraint_type='PRIMARY KEY'");
              var watchlistSeenPairing = connection.prepareStatement("select pg_get_constraintdef(constraint_row.oid) from pg_constraint constraint_row join pg_namespace namespace_row on namespace_row.oid=constraint_row.connamespace where namespace_row.nspname='public' and constraint_row.conname='ck_candidate_job_watch_seen'");
              var watchlistApplicationColumns = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='candidate_job_watch' and column_name in ('applied','application_status','submitted_at')");
              var transportRisk = connection.prepareStatement("select is_nullable, column_default from information_schema.columns where table_schema='public' and table_name='acquired_document' and column_name='transport_risk'");
              var acquisitionAuditTables = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('source_onboarding_checkpoint','artifact_import_failure')");
              var coverageAuditColumns = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='source_year_coverage' and column_name in ('listing_page_count','filtered_count','failed_count','earliest_published_on','latest_published_on','stop_reason')");
              var targetScopeColumns = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='target_source_catalog' and column_name in ('scope_level','scope_code','priority_tier','coverage_role')");
              var districtTargets = connection.prepareStatement("select count(*) from target_source_catalog where scope_level='DISTRICT' and code in ('HZ_SHANGCHENG_GOV','HZ_GONGSHU_GOV','HZ_XIHU_GOV','HZ_BINJIANG_GOV','HZ_XIAOSHAN_GOV','HZ_YUHANG_GOV','HZ_LINPING_GOV','HZ_QIANTANG_GOV','HZ_FUYANG_GOV','HZ_LINAN_GOV','HZ_JIANDE_GOV','HZ_TONGLU_GOV','HZ_CHUNAN_GOV')");
              var districtPriority = connection.prepareStatement("select priority_tier, count(*) from target_source_catalog where scope_level='DISTRICT' group by priority_tier order by priority_tier");
              var lifecycleTable = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name='recruitment_lifecycle_document'");
              var xihuSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_XIHU_GOV' and source.enabled and source.configuration ->> 'historicalPaginationMode'='JCMS_PARAM_JSON' and source.configuration ->> 'adapterType'='JCMS_LISTING' and source.configuration ->> 'titleExcludeRegex'='招聘会|培训|讲座' and source.configuration -> 'allowedHosts' @> '[\"zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn\"]'::jsonb and target.connection_status='PARTIAL'");
              var gongshuSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_GONGSHU_GOV' and source.enabled and source.entry_uri='https://www.gongshu.gov.cn/col/col1229226160/index.html' and source.configuration ->> 'historicalPaginationMode'='JCMS_PARAM_JSON' and source.configuration ->> 'adapterType'='JCMS_LISTING' and source.configuration ->> 'titleExcludeRegex'='招聘会|培训|讲座' and source.configuration -> 'allowedHosts' @> '[\"zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn\"]'::jsonb and target.connection_status='PARTIAL'");
              var qiantangSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_QIANTANG_GOV' and source.enabled and source.entry_uri='https://www.qiantang.gov.cn/col/col1657687/index.html' and source.configuration ->> 'historicalPaginationMode'='JCMS_PARAM_JSON' and source.configuration ->> 'adapterType'='JCMS_LISTING' and (source.configuration ->> 'incrementalListingMaxPages')::int=10 and (source.configuration ->> 'historicalMaxPages')::int=200 and target.connection_status='PARTIAL'");
              var shangchengSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_SHANGCHENG_GOV' and source.enabled and source.entry_uri='https://www.hzsc.gov.cn/col/col1229554150/index.html' and jsonb_array_length(source.configuration -> 'listingEntries')=1 and source.configuration -> 'listingEntries' -> 0 -> 'knownArchiveGapYears' @> '[2025]'::jsonb and (source.configuration -> 'listingEntries' -> 0 ->> 'completenessRequired')::boolean and target.connection_status='PARTIAL'");
              var linanSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_LINAN_GOV' and source.enabled and jsonb_array_length(source.configuration -> 'listingEntries')=1 and source.configuration -> 'listingEntries' -> 0 -> 'jcmsSearch' @> '{\"xxgkId\":\"F001\",\"className\":\"人事信息\"}'::jsonb and target.connection_status='PARTIAL'");
              var jiandeSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_JIANDE_GOV' and source.enabled and source.entry_uri like '%col1229535302%number=JD16-JD1602%' and source.configuration -> 'listingEntries' -> 0 -> 'jcmsSearch' @> '{\"xxgkId\":\"JD16-JD1602\",\"className\":\"招聘招录\"}'::jsonb and source.configuration -> 'listingEntries' -> 0 -> 'knownArchiveGapYears' @> '[2024]'::jsonb and target.connection_status='PARTIAL'");
              var healthSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_HEALTH_COMMISSION' and source.enabled and jsonb_array_length(source.configuration -> 'listingEntries')=2 and source.configuration -> 'listingEntries' -> 0 ->> 'entryUri' like '%col1229318903%' and source.configuration -> 'listingEntries' -> 1 ->> 'entryUri' like '%col1229318910%' and target.scope_level='CITY' and target.priority_tier='P0' and target.connection_status='PARTIAL'");
              var yuhangSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_YUHANG_GOV' and source.enabled and source.entry_uri like '%col1229191870%' and source.configuration -> 'listingEntries' -> 0 -> 'jcmsSearch' @> '{\"xxgkId\":\"W001-C001\",\"className\":\"人员考录\"}'::jsonb and (source.configuration -> 'listingEntries' -> 0 ->> 'reconcileReportedTotalByListingItems')::boolean and target.connection_status='PARTIAL'");
              var xiaoshanSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_XIAOSHAN_GOV' and source.enabled and source.configuration -> 'listingEntries' -> 0 ->> 'mode'='STATIC_SUFFIX_TEMPLATE' and source.configuration -> 'listingEntries' -> 0 -> 'knownArchiveGapYears' @> '[2024]'::jsonb and target.connection_status='PARTIAL'");
              var universitySources = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code in ('ZJUT_RECRUITMENT','HZNU_RECRUITMENT') and source.enabled and source.base_uri like 'https://%' and source.entry_uri like 'https://%' and jsonb_array_length(source.configuration -> 'listingEntries') >= 1 and target.connection_status='PARTIAL'");
              var finalP0DistrictSources = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code in ('HZ_BINJIANG_GOV','HZ_LINPING_GOV') and source.enabled and source.configuration -> 'listingEntries' -> 0 -> 'knownArchiveGapYears' @> '[2024,2025,2026]'::jsonb and target.connection_status='PARTIAL'");
               var currentChunanSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_CHUNAN_GOV' and source.enabled and source.entry_uri like '%col1289604%' and source.configuration -> 'listingEntries' -> 0 ->> 'mode'='JCMS_PARAM_JSON' and (source.configuration -> 'listingEntries' -> 0 ->> 'historicalMaxPages')::int=120 and (source.configuration -> 'listingEntries' -> 0 ->> 'completenessRequired')::boolean and target.connection_status='PARTIAL'");
              var tongluSearchSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_TONGLU_GOV' and source.enabled and source.configuration -> 'listingEntries' -> 0 ->> 'mode'='JSON_HTML_FRAGMENTS' and source.configuration -> 'listingEntries' -> 0 ->> 'httpMethod'='POST' and source.configuration -> 'listingEntries' -> 0 ->> 'fragmentRedirectQueryParameter'='url' and target.connection_status='PARTIAL'");
               var childrensHospitalSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='HZ_CHILDRENS_HOSPITAL' and source.enabled and source.entry_uri like 'https://wsjkw.hangzhou.gov.cn/%col1229318903%' and jsonb_array_length(source.configuration -> 'listingEntries')=2 and source.configuration -> 'listingEntries' -> 0 ->> 'mode'='JCMS_PARAM_JSON' and source.configuration -> 'listingEntries' -> 1 ->> 'code'='appointment-publicity' and source.configuration -> 'listingEntries' -> 0 ->> 'titleIncludeRegex' like '%杭州市儿童医院%' and source.configuration -> 'listingEntries' -> 1 ->> 'titleIncludeRegex' like '%杭州市儿童医院%' and (source.configuration ->> 'allowEmptyIncremental')::boolean and target.connection_status='PARTIAL'");
              var hangzhouMedicineSource = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code='CAS_HANGZHOU_MEDICINE' and source.enabled and source.configuration ->> 'scriptAttachmentVariable'='appLinkStr' and source.configuration -> 'listingEntries' -> 0 ->> 'mode'='LINKED_PAGE' and target.priority_tier='P0' and target.connection_status='PARTIAL'");
              var governmentSoeSources = connection.prepareStatement("select count(*) from recruitment_source source join target_source_catalog target on target.recruitment_source_id=source.id where source.code in ('HZ_CAPITAL_GROUP','HZ_METRO_GROUP') and source.enabled and source.configuration -> 'listingEntries' -> 0 ->> 'mode'='JSON_API' and source.configuration -> 'listingEntries' -> 0 -> 'knownArchiveGapYears' @> '[2024,2025]'::jsonb and target.priority_tier='P0' and target.connection_status='PARTIAL'");
              var capitalRequest = connection.prepareStatement("select count(*) from recruitment_source where code='HZ_CAPITAL_GROUP' and source_type='OFFICIAL_ORGANIZATION' and base_uri='https://www.hzzbco.com/' and configuration -> 'listingEntries' -> 0 -> 'requestHeaders' @> '{\"language\":\"1\"}'::jsonb and configuration -> 'listingEntries' -> 0 ->> 'jsonUrlTemplate'='/newDet_{value}_8'");
              var metroRequest = connection.prepareStatement("select count(*) from recruitment_source where code='HZ_METRO_GROUP' and source_type='OFFICIAL_ORGANIZATION' and configuration -> 'listingEntries' -> 0 ->> 'httpMethod'='POST' and configuration -> 'listingEntries' -> 0 ->> 'paginationLocation'='BODY' and configuration -> 'listingEntries' -> 0 ->> 'jsonFetchContentPath'='data.contentHtml' and configuration ->> 'imageEvidenceSelector'='img[src]'");
              var contentPolicyRegexes = connection.prepareStatement("""
                  select code, policy_key, regex
                  from recruitment_source source
                  cross join lateral (
                      select 'responseRejectRegexes' policy_key, value regex
                      from jsonb_array_elements_text(coalesce(source.configuration -> 'responseRejectRegexes', '[]'::jsonb))
                      union all
                      select 'contentFingerprintIgnoreRegexes', value
                      from jsonb_array_elements_text(coalesce(source.configuration -> 'contentFingerprintIgnoreRegexes', '[]'::jsonb))
                  ) policy
                  order by code, policy_key, regex
                  """)) {
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
            try (var rows = waveOneSourceSeeds.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = waveOneTargetState.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = legacyConnectedTargets.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
            try (var rows = hospitalSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
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
            try (var rows = officialJobFacts.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(17); }
            try (var rows = fieldEvidenceTable.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = eventFieldEvidenceTable.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = fieldEvidenceIndex.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = processorVersion.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            // 人工复核是把模型提案提升为已核验官方数据的唯一闸门，操作人必须非空。
            try (var rows = reviewActor.executeQuery()) { rows.next(); assertThat(rows.getString(1)).isEqualTo("NO"); }
            try (var rows = reviewActorIndex.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            // 新的硬资格语义：两张表都必须挡住旧值，否则读取时才会炸在 valueOf 上。
            try (var rows = eligibilityStatusCheck.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = legacyEligibilityValues.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
            // 报名时状态是声明不是事实，缺省必须是"没说"而不是"满足"。
            try (var rows = applicationTimeColumns.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = applicationTimeFactKeys.executeQuery()) {
                rows.next();
                assertThat(rows.getString(1))
                    .contains("EMPLOYER_SETTLEMENT_AT_APPLICATION")
                    .contains("SOCIAL_INSURANCE_AT_APPLICATION")
                    .contains("EMPLOYMENT_HISTORY");
            }
            // 幂等就是主键：同一把钥匙写两次在库里不成立，不靠应用层记得去查一下。
            // 注意这些查询都限定了 public：本用例类共用一个容器，别的方法把整套迁移灌进了各自的
            // schema，只按约束名去连会把同名约束重复捞出来——单 schema 的本地库照不出这个问题。
            try (var rows = confirmationLedgerKey.executeQuery()) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("candidate_profile_id,idempotency_key");
            }
            // 资料已写入但结论未重算，是必须能被看见、能被恢复的中间态。
            try (var rows = confirmationLedgerStages.executeQuery()) {
                rows.next();
                assertThat(rows.getString(1)).contains("WRITTEN").contains("RECOMPUTED");
            }
            // 要凭材料判断的字段进不了这张表：一句"我有的"不能成为硬资格依据。
            try (var rows = confirmationLedgerFactKeys.executeQuery()) {
                rows.next();
                assertThat(rows.getString(1))
                    .contains("POLITICAL_AFFILIATION")
                    .contains("EMPLOYER_SETTLEMENT_AT_APPLICATION")
                    .doesNotContain("EDUCATION_RECORDS")
                    .doesNotContain("EMPLOYMENT_HISTORY");
            }
            // 会话要记住筛选条件、岗位顺序、待确认事项和资料版本，缺一项闭环就断在那里。
            try (var rows = agentSessionColumns.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(7); }
            try (var rows = agentSessionJsonArrays.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            // 关注清单：一个岗位只关注一次；"看过"必须连同看过的时间一起写，否则无从追溯。
            try (var rows = watchlistKey.executeQuery()) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("candidate_profile_id,job_posting_id");
            }
            try (var rows = watchlistSeenPairing.executeQuery()) {
                rows.next();
                assertThat(rows.getString(1)).contains("last_seen_status").contains("last_seen_at");
            }
            // 关注不是报名：这张表里不该出现任何表示"已提交报名"的列。
            try (var rows = watchlistApplicationColumns.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
            try (var rows = transportRisk.executeQuery()) {
                rows.next();
                assertThat(rows.getString("is_nullable")).isEqualTo("NO");
                assertThat(rows.getString("column_default")).contains("NONE");
            }
            try (var rows = acquisitionAuditTables.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = coverageAuditColumns.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(6); }
            try (var rows = targetScopeColumns.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(4); }
            try (var rows = districtTargets.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(13); }
            try (var rows = districtPriority.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("P0");
                assertThat(rows.getInt(2)).isEqualTo(10);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("P2");
                assertThat(rows.getInt(2)).isEqualTo(3);
                assertThat(rows.next()).isFalse();
            }
            try (var rows = lifecycleTable.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = xihuSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = gongshuSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = qiantangSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = shangchengSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = linanSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = jiandeSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = healthSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = yuhangSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = xiaoshanSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = universitySources.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = finalP0DistrictSources.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = currentChunanSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = tongluSearchSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = childrensHospitalSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = hangzhouMedicineSource.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = governmentSoeSources.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = capitalRequest.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = metroRequest.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = contentPolicyRegexes.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    Pattern.compile(rows.getString("regex"), Pattern.DOTALL);
                    count++;
                }
                assertThat(count).isGreaterThanOrEqualTo(7);
            }
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
            try (var rows = coverageRows.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(90); }
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
