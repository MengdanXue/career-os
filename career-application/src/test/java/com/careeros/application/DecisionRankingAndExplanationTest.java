package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class DecisionRankingAndExplanationTest {
    private static final UUID CANDIDATE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void rankingOmitsExcludedAndOrdersTierBeforeScoreWithStablePagination() {
        var t2 = bundle(OpportunityTier.T2, EligibilityStatus.ELIGIBLE, 95, "T2高分", LocalDate.of(2026, 8, 30));
        var t1 = bundle(OpportunityTier.T1, EligibilityStatus.ELIGIBLE, 70, "T1稳定", LocalDate.of(2026, 9, 1));
        var excluded = bundle(OpportunityTier.EXCLUDED, EligibilityStatus.INELIGIBLE, 100, "不合格", LocalDate.of(2026, 8, 25));
        var contexts = new Contexts(List.of(t2.jobContext(), excluded.jobContext(), t1.jobContext()));
        Map<UUID,DecisionBundle> byJob = Map.of(t1.decision().jobPostingId(), t1, t2.decision().jobPostingId(), t2, excluded.decision().jobPostingId(), excluded);

        var page = new DecisionRankingService(contexts, (candidateId, jobId, now) -> byJob.get(jobId))
            .rank(CANDIDATE_ID, new DecisionRankingService.RankingQuery(null, null, null, 0, 10, false), NOW);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(item -> item.decision().tier()).containsExactly(OpportunityTier.T1, OpportunityTier.T2);
    }

    @Test
    void deterministicExplanationIncludesUnknownWarningAndNeverCallsItProbability() {
        var value = bundle(OpportunityTier.T1, EligibilityStatus.ELIGIBLE, 70, "信息中心", LocalDate.of(2026, 9, 1));

        var explanation = new DecisionExplanationService().explain(value);

        assertThat(explanation.text()).contains("资格").contains("T1").contains("匹配度 70");
        assertThat(explanation.text()).doesNotContain("录取概率");
        assertThat(explanation.warnings()).anyMatch(warning -> warning.contains("证据覆盖率"));
    }

    private static DecisionBundle bundle(OpportunityTier tier, EligibilityStatus eligibilityStatus, int fitScore, String title, LocalDate deadline) {
        UUID jobId = UUID.randomUUID(); UUID eventId = UUID.randomUUID(); UUID organizationId = UUID.randomUUID();
        UUID eligibilityId = UUID.randomUUID(); UUID fitId = UUID.randomUUID(); UUID stabilityId = UUID.randomUUID();
        var job = new JobPosting(jobId, eventId, organizationId, "A", title, JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT,
            "杭州", 1, EducationLevel.BACHELOR, Set.of(), Set.of(), null, null, null, Set.of(), "Java", "https://example.gov.cn", List.of(UUID.randomUUID()));
        var organization = new Organization(organizationId, "单位", OrganizationType.PUBLIC_INSTITUTION, null, "浙江", "杭州", null, null, null);
        var event = new RecruitmentEvent(eventId, "招聘", 2026, EventType.PUBLIC_INSTITUTION, NOW.atZone(java.time.ZoneOffset.UTC).toLocalDate(), null, deadline, "https://example.gov.cn", EmploymentType.ESTABLISHMENT, List.of());
        var eligibility = new EligibilityAssessment(eligibilityId, CANDIDATE_ID, jobId, eligibilityStatus, Map.of(), job.evidenceIds(), EligibilityEvaluator.VERSION, NOW);
        var fit = new FitAssessment(fitId, CANDIDATE_ID, jobId, List.of(new AssessmentDimension(AssessmentDimensionType.MAJOR_FIT, fitScore, 100, AssessmentFactStatus.EXPLICIT, "FIXTURE", "fixture", job.evidenceIds())), FitEvaluator.VERSION, "v1", "a".repeat(64), NOW);
        var stability = new StabilityAssessment(stabilityId, CANDIDATE_ID, jobId, List.of(new AssessmentDimension(AssessmentDimensionType.EMPLOYMENT_SECURITY, 40, 100, AssessmentFactStatus.EXPLICIT, "FIXTURE", "fixture", job.evidenceIds())), StabilityEvaluator.VERSION, "v1", "a".repeat(64), NOW);
        var recommendation = tier == OpportunityTier.EXCLUDED ? RecommendationStatus.EXCLUDED : RecommendationStatus.RECOMMENDED;
        var decision = new DecisionAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, eligibilityId, fitId, stabilityId, eligibilityStatus, tier, recommendation, fitScore, 40, 55, DecisionIntelligenceService.VERSION, "v1", "a".repeat(64), NOW);
        return new DecisionBundle(eligibility, fit, stability, decision, new JobContext(job, organization, event, "a".repeat(64), true));
    }

    private record Contexts(List<JobContext> values) implements JobContexts {
        public Optional<JobContext> findByJobId(UUID id) { return values.stream().filter(v -> v.job().id().equals(id)).findFirst(); }
        public List<JobContext> findActive() { return values.stream().filter(JobContext::active).toList(); }
    }
}
