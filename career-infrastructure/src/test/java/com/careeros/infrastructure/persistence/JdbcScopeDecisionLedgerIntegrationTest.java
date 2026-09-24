package com.careeros.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.AdmissionScopeReviewService.Decision;
import com.careeros.application.AdmissionScopeReviewService.RecordedDecision;
import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = JdbcScopeDecisionLedgerIntegrationTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class JdbcScopeDecisionLedgerIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os")
        .withUsername("career_os")
        .withPassword("career_os");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    void decisionsAreAppendedAndReadBackNewestFirst(@Autowired JdbcScopeDecisionLedger ledger,
                                                    @Autowired JdbcTemplate jdbc) {
        UUID jobId = insertJob(jdbc);
        var include = decision(jobId, Decision.INCLUDE, DataQualityStatus.VERIFIED, TargetScopeStatus.INCLUDED, NOW);
        var exclude = decision(jobId, Decision.EXCLUDE, DataQualityStatus.VERIFIED, TargetScopeStatus.EXCLUDED,
            NOW.plusSeconds(60));

        ledger.append(include);
        ledger.append(exclude);

        assertThat(ledger.findByJobId(jobId)).containsExactly(exclude, include);
    }

    /** 台账只增不改：改写一条历史决定，等于抹掉"它曾经为什么在池子里"。 */
    @Test
    void theLedgerRefusesUpdates(@Autowired JdbcScopeDecisionLedger ledger, @Autowired JdbcTemplate jdbc) {
        UUID jobId = insertJob(jdbc);
        var include = decision(jobId, Decision.INCLUDE, DataQualityStatus.VERIFIED, TargetScopeStatus.INCLUDED, NOW);
        ledger.append(include);

        assertThatThrownBy(() -> jdbc.update(
                "update job_admission_scope_decision set reviewer = 'someone-else' where id = ?",
                include.decisionId()))
            .hasStackTraceContaining("append-only");
    }

    /** 纳入只可能落在证据齐全的岗位上。绕过服务直接写库也过不去。 */
    @Test
    void anIncludeOnIncompleteEvidenceIsRefusedByTheDatabase(@Autowired JdbcScopeDecisionLedger ledger,
                                                             @Autowired JdbcTemplate jdbc) {
        UUID jobId = insertJob(jdbc);

        assertThatThrownBy(() -> ledger.append(decision(jobId, Decision.INCLUDE,
                DataQualityStatus.NORMALIZED, TargetScopeStatus.INCLUDED, NOW)))
            .hasStackTraceContaining("ck_scope_decision_include_verified");
    }

    private static RecordedDecision decision(UUID jobId, Decision decision, DataQualityStatus quality,
                                             TargetScopeStatus scope, Instant at) {
        return new RecordedDecision(UUID.randomUUID(), jobId, decision, "f".repeat(64), "reviewer-a",
            "信息中心岗", quality, scope, at);
    }

    private static UUID insertJob(JdbcTemplate jdbc) {
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
            insert into recruitment_event (id, title, recruitment_year, event_type, source_url)
            values (?, 'event', 2026, 'PUBLIC_INSTITUTION', ?)
            """, eventId, "https://example.gov.cn/event/" + eventId);
        jdbc.update("""
            insert into organization (id, name, organization_type)
            values (?, 'organization', 'PUBLIC_INSTITUTION')
            """, organizationId);
        jdbc.update("""
            insert into job_posting
                (id, recruitment_event_id, organization_id, title, job_family,
                 employment_type, minimum_education, source_url, content_fingerprint)
            values (?, ?, ?, 'job', 'SOFTWARE', 'ESTABLISHMENT', 'BACHELOR', 'https://example.gov.cn/job', ?)
            """, jobId, eventId, organizationId, "a".repeat(64));
        return jobId;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.careeros.infrastructure.persistence")
    @EnableJpaRepositories(basePackages = "com.careeros.infrastructure.persistence")
    @Import(JdbcScopeDecisionLedger.class)
    static class TestApplication {}
}
