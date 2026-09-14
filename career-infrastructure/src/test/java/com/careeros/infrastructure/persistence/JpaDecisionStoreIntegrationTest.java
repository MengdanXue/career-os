package com.careeros.infrastructure.persistence;

import static com.careeros.domain.CandidateFacts.CandidateFactKey.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.*;
import com.careeros.application.DecisionPorts.*;
import com.careeros.domain.*;
import com.careeros.domain.GraduateEligibilityRule.EvidenceState;
import com.careeros.infrastructure.extraction.PostgresFingerprintLock;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The production projection and assessment path, backed only by a disposable PostgreSQL database. */
@SpringBootTest(classes = JpaDecisionStoreIntegrationTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class JpaDecisionStoreIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private static final LocalDate CUTOFF = LocalDate.of(2026, 9, 30);
    private static final String FINGERPRINT = "b".repeat(64);
    private static final String OLD_VERSION = "decision-v3-qualification-cutoff|eligibility-hard-verdict-v7@cutoff=" + CUTOFF;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("stage1_decision_projection")
        .withUsername("projection_test").withPassword("projection_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> false);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired JpaDecisionStore store;
    @Autowired RepositoryPorts.CandidateProfiles candidates;
    @Autowired RepositoryPorts.CandidateFactConfirmations confirmations;
    @Autowired RepositoryPorts.EligibilityAssessments assessments;
    @Autowired JobAdmissionPorts.JobAdmissions admissions;
    @Autowired JobAdmissionPorts.JobFieldEvidence fieldEvidence;
    @Autowired DecisionInputLock inputLock;
    @Autowired AgentSessionPorts.Sessions sessionStore;

    @ParameterizedTest
    @CsvSource({"true,true,CONFIRMED", "true,false,CONFIRMED", "false,true,CONFIRMED",
        "false,false,CONFIRMED", "true,true,REVIEW_REQUIRED"})
    void persistedGraduateFlagsReachActualAssessmentAndOnlyTheirApplicablePendingQuestions(
        boolean employer, boolean insurance, EvidenceState evidence
    ) {
        var fixture = insert(employer, insurance, evidence);
        var projected = store.findByJobId(fixture.jobId()).orElseThrow();

        assertThat(projected.graduateClause()).isNotNull();
        assertThat(projected.graduateClause().requiresNoEmployer()).isEqualTo(employer);
        assertThat(projected.graduateClause().restrictsSocialInsurance()).isEqualTo(insurance);
        assertThat(projected.graduateClause().evidenceState()).isEqualTo(evidence);
        assertThat(store.findActiveByJobIds(Set.of(fixture.jobId())).getFirst().graduateClause())
            .isEqualTo(projected.graduateClause());
        assertThat(projected.event().processFacts().notice().state()).isEqualTo(EvidenceState.CONFIRMED);
        assertThat(projected.event().processFacts().application().state()).isEqualTo(EvidenceState.CONFIRMED);
        assertThat(projected.event().writtenExamState()).isEqualTo(EvidenceState.NOT_REQUIRED);
        assertThat(projected.event().interviewState()).isEqualTo(EvidenceState.REVIEW_REQUIRED);

        var decision = service().assess(fixture.candidateId(), fixture.jobId(), NOW);
        assertThat(decision.jobContext().graduateClause()).isEqualTo(projected.graduateClause());
        assertThat(decision.eligibility().ruleResults().get(RuleType.FRESH_GRADUATE_STATUS).explanation())
            .doesNotContain("尚未解析");
        var pending = sessionService().pendingFor(fixture.candidateId(), List.of(decision));
        var expected = new java.util.ArrayList<CandidateFacts.CandidateFactKey>();
        if (evidence == EvidenceState.CONFIRMED && employer) expected.add(EMPLOYER_SETTLEMENT_AT_APPLICATION);
        if (evidence == EvidenceState.CONFIRMED && insurance) expected.add(SOCIAL_INSURANCE_AT_APPLICATION);
        assertThat(pending).extracting(AgentSession.PendingConfirmation::factKey)
            .containsExactlyElementsOf(expected);
    }

    @Test void anOldUnknownProjectionSnapshotCannotMaskTheCorrectedGraduateClause() {
        var fixture = insert(true, true, EvidenceState.CONFIRMED);
        var legacy = saveOldUnknownSnapshot(fixture);
        entities.flush();
        entities.clear();

        assertThatThrownBy(() -> service().current(fixture.candidateId(), fixture.jobId()))
            .isInstanceOf(DecisionExceptions.DecisionNotFoundException.class);
        var fresh = service().assess(fixture.candidateId(), fixture.jobId(), NOW.plusSeconds(1));
        var repeated = service().assess(fixture.candidateId(), fixture.jobId(), NOW.plusSeconds(2));

        assertThat(fresh.decision().id()).isNotEqualTo(legacy.decision().id());
        assertThat(fresh.decision().profileVersion()).isEqualTo(legacy.decision().profileVersion());
        assertThat(fresh.decision().jobContentFingerprint()).isEqualTo(legacy.decision().jobContentFingerprint());
        assertThat(fresh.decision().evaluatorVersion()).isNotEqualTo(OLD_VERSION).hasSizeLessThanOrEqualTo(80);
        assertThat(fresh.eligibility().ruleResults().get(RuleType.FRESH_GRADUATE_STATUS).explanation())
            .doesNotContain("尚未解析");
        assertThat(sessionService().pendingFor(fixture.candidateId(), List.of(fresh)))
            .extracting(AgentSession.PendingConfirmation::factKey)
            .containsExactly(EMPLOYER_SETTLEMENT_AT_APPLICATION, SOCIAL_INSURANCE_AT_APPLICATION);
        assertThat(repeated.decision().id()).isEqualTo(fresh.decision().id());
        assertThat(store.findByInput(new DecisionInputKey(fixture.candidateId(), fixture.jobId(),
            fixture.profileVersion(), FINGERPRINT, OLD_VERSION))).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from decision_assessment where job_posting_id=?",
            Long.class, fixture.jobId())).isEqualTo(2);
    }

    private DecisionBundle saveOldUnknownSnapshot(Fixture fixture) {
        var context = store.findByJobId(fixture.jobId()).orElseThrow();
        var candidate = candidates.findById(fixture.candidateId()).orElseThrow();
        var facts = CandidateFacts.resolve(candidate, confirmations.findByCandidateId(candidate.id()));
        var eligibility = assessments.save(new EligibilityEvaluator().evaluate(candidate, facts, context.job(),
            FINGERPRINT, CUTOFF, NOW, OLD_VERSION, Set.of(), null));
        assertThat(eligibility.ruleResults().get(RuleType.FRESH_GRADUATE_STATUS).explanation())
            .contains("尚未解析");
        var fit = new FitEvaluator().evaluate(candidate, facts, context.job(), context.organization(),
            FINGERPRINT, CUTOFF, NOW, OLD_VERSION);
        var stability = new StabilityEvaluator().evaluate(candidate, context.job(), context.organization(),
            List.of(), FINGERPRINT, NOW, OLD_VERSION);
        var decision = new DecisionAssessment(UUID.randomUUID(), candidate.id(), fixture.jobId(), eligibility.id(),
            fit.id(), stability.assessment().id(), eligibility.status(), stability.tier(), RecommendationStatus.REVIEW,
            fit.score(), stability.assessment().score(), 0, OLD_VERSION, candidate.profileVersion(), FINGERPRINT, NOW);
        return store.save(new DecisionInputKey(candidate.id(), fixture.jobId(), candidate.profileVersion(), FINGERPRINT,
            OLD_VERSION), new DecisionBundle(eligibility, fit, stability.assessment(), decision, context));
    }

    private Fixture insert(boolean employer, boolean insurance, EvidenceState state) {
        UUID event = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        String version = "profile-" + candidateId;
        String clause = """
            {"recruitmentYear":2026,"explicitGraduationYears":[2026],"cohorts":["CURRENT_YEAR"],
             "includesOverseasGraduates":false,"degreeTiming":"UNSPECIFIED","degreeDeadline":null,
             "credentialTiming":"UNSPECIFIED","credentialDeadline":null,"requiresNoEmployer":%s,
             "restrictsSocialInsurance":%s,"rawText":"2026届报名时未落实工作单位及社保限制","evidenceState":"%s"}
            """.formatted(employer, insurance, state);
        jdbc.update("""
            insert into recruitment_event(id,title,recruitment_year,event_type,application_ends_on,source_url,
                graduate_rule,graduate_rule_json,notice_state,application_state,written_exam_state,interview_state)
            values (?, '已解析的2026招聘条款', 2026, 'PUBLIC_INSTITUTION', ?, ?,
                '2026届报名时未落实工作单位及社保限制', ?::jsonb, 'CONFIRMED', 'CONFIRMED', 'NOT_REQUIRED', 'REVIEW_REQUIRED')
            """, event, CUTOFF, "https://example.gov.cn/event/" + event, clause);
        jdbc.update("insert into organization(id,name,organization_type) values (?, '测试事业单位', 'PUBLIC_INSTITUTION')",
            organization);
        jdbc.update("""
            insert into job_posting(id,recruitment_event_id,organization_id,title,job_family,employment_type,
                minimum_education,source_url,content_fingerprint,location)
            values (?, ?, ?, '信息技术岗', 'INFORMATION_SYSTEMS', 'ESTABLISHMENT', 'BACHELOR', ?, ?, '杭州')
            """, job, event, organization, "https://example.gov.cn/job/" + job, FINGERPRINT);
        admissions.save(new JobAdmission(job, DataQualityStatus.VERIFIED, TargetScopeStatus.INCLUDED,
            Set.of(JobAdmissionReason.TARGET_TECHNICAL_ROLE), "projection-test", NOW, true));
        candidates.save(new CandidateProfile(candidateId, "投影回归候选人", PartialDate.month(1997, 4),
            EducationLevel.MASTER, Set.of("计算机科学"), 2026, 0, Set.of(), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), version));
        entities.flush();
        entities.clear();
        return new Fixture(candidateId, job, version);
    }

    private DecisionIntelligenceService service() {
        return new DecisionIntelligenceService(candidates, confirmations, assessments, store, store, store,
            admissions, fieldEvidence, inputLock, new EligibilityEvaluator(), new FitEvaluator(), new StabilityEvaluator());
    }

    private AgentSessionService sessionService() {
        return new AgentSessionService(sessionStore, candidates, confirmations, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private record Fixture(UUID candidateId, UUID jobId, String profileVersion) {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.careeros.infrastructure.persistence")
    @EnableJpaRepositories(basePackages = "com.careeros.infrastructure.persistence")
    @Import({JpaDecisionStore.class, JpaJobAdmissionStore.class, JpaJobFieldEvidenceReader.class,
        PostgresFingerprintLock.class})
    static class TestApplication {
        @Bean TransactionTemplate transactions(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
        @Bean RepositoryPorts.CandidateProfiles candidateProfiles(CandidateProfileJpaRepository repository) {
            return new PersistenceAdaptersConfiguration().candidateProfiles(repository);
        }
        @Bean RepositoryPorts.CandidateFactConfirmations candidateFacts(CandidateFactConfirmationJpaRepository repository) {
            return new PersistenceAdaptersConfiguration().candidateFactConfirmations(repository);
        }
        @Bean RepositoryPorts.EligibilityAssessments assessments(EligibilityAssessmentJpaRepository repository) {
            return new PersistenceAdaptersConfiguration().eligibilityAssessments(repository);
        }
        @Bean AgentSessionPorts.Sessions sessions(AgentSessionJpaRepository repository, PlatformTransactionManager manager,
                                                 EntityManager entities) {
            return new PersistenceAdaptersConfiguration().agentSessions(repository, manager, entities);
        }
    }
}
