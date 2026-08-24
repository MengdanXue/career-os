package com.careeros.application.personal;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.RepositoryPorts;
import com.careeros.domain.*;
import com.careeros.domain.EligibilityAssessment.RuleResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CandidateDecisionDiffServiceTest {
    private static final UUID CANDIDATE_ID = UUID.randomUUID();
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 24);

    @Test
    void comparesTheSameJobsAndExplainsAllMaterialQualificationChanges() {
        UUID newlyEligible = UUID.randomUUID();
        UUID newlyIneligible = UUID.randomUUID();
        UUID changedReason = UUID.randomUUID();
        var old = List.of(
            bundle(newlyEligible, "old-v1", EligibilityStatus.UNCERTAIN,
                Map.of(RuleType.EXPERIENCE, rule(EligibilityStatus.UNCERTAIN, "工作年限待确认"))),
            bundle(newlyIneligible, "old-v1", EligibilityStatus.ELIGIBLE,
                Map.of(RuleType.EXACT_MAJOR, rule(EligibilityStatus.ELIGIBLE, "专业满足"))),
            bundle(changedReason, "old-v1", EligibilityStatus.INELIGIBLE,
                Map.of(RuleType.AGE, rule(EligibilityStatus.INELIGIBLE, "年龄超过限制")))
        );
        var current = Map.of(
            newlyEligible, bundle(newlyEligible, "current-v2", EligibilityStatus.ELIGIBLE,
                Map.of(RuleType.EXPERIENCE, rule(EligibilityStatus.ELIGIBLE, "经历已核验"))),
            newlyIneligible, bundle(newlyIneligible, "current-v2", EligibilityStatus.INELIGIBLE,
                Map.of(RuleType.EXACT_MAJOR, rule(EligibilityStatus.INELIGIBLE, "专业不在目录"))),
            changedReason, bundle(changedReason, "current-v2", EligibilityStatus.INELIGIBLE,
                Map.of(RuleType.AGE, rule(EligibilityStatus.INELIGIBLE, "基准日年龄超过限制")))
        );
        var assessedJobs = new LinkedHashSet<UUID>();
        var service = new CandidateDecisionDiffService(candidates("current-v2"), snapshots(old),
            (candidateId, jobId, now) -> { assessedJobs.add(jobId); return current.get(jobId); });

        var result = service.recompute(CANDIDATE_ID, "old-v1", AS_OF);

        assertThat(result.available()).isTrue();
        assertThat(result.previousProfileVersion()).isEqualTo("old-v1");
        assertThat(result.currentProfileVersion()).isEqualTo("current-v2");
        assertThat(result.newlyEligibleCount()).isEqualTo(1);
        assertThat(result.resolvedUncertaintyCount()).isEqualTo(1);
        assertThat(result.newlyIneligibleCount()).isEqualTo(1);
        assertThat(assessedJobs).containsExactlyInAnyOrder(newlyEligible, newlyIneligible, changedReason);
        assertThat(result.affectedJobs()).hasSize(3);
        assertThat(result.affectedJobs().stream().flatMap(item -> item.reasons().stream()))
            .anyMatch(reason -> reason.contains("工作经历") && reason.contains("待确认") && reason.contains("可报"));
        assertThat(result.affectedJobs()).allMatch(item -> item.deepLink().equals("/opportunities/" + item.jobId()));
    }

    @Test
    void reportsUnavailableHistoryWithoutInventingZeroDifferencesOrRecomputingJobs() {
        var calls = new AtomicInteger();
        var service = new CandidateDecisionDiffService(candidates("current-v2"), snapshots(List.of()),
            (candidateId, jobId, now) -> { calls.incrementAndGet(); throw new AssertionError("must not assess"); });

        var result = service.recompute(CANDIDATE_ID, "missing-v1", AS_OF);

        assertThat(result.available()).isFalse();
        assertThat(result.message()).contains("历史");
        assertThat(result.newlyEligibleCount()).isNull();
        assertThat(result.resolvedUncertaintyCount()).isNull();
        assertThat(result.newlyIneligibleCount()).isNull();
        assertThat(result.affectedJobs()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    private static RepositoryPorts.CandidateProfiles candidates(String version) {
        var candidate = new CandidateProfile(CANDIDATE_ID, "候选人", PartialDate.month(1992, 12),
            EducationLevel.MASTER, Set.of("计算机科学"), 2027, 0, Set.of(), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), version);
        return new RepositoryPorts.CandidateProfiles() {
            public CandidateProfile save(CandidateProfile value) { return value; }
            public Optional<CandidateProfile> findById(UUID id) { return id.equals(CANDIDATE_ID) ? Optional.of(candidate) : Optional.empty(); }
            public Optional<CandidateProfile> findByIdForUpdate(UUID id) { return findById(id); }
            public List<CandidateProfile> findAll() { return List.of(candidate); }
            public void deleteById(UUID id) { }
        };
    }

    private static DecisionSnapshots snapshots(List<DecisionBundle> old) {
        return new DecisionSnapshots() {
            public Optional<DecisionBundle> findByInput(DecisionInputKey input) { return Optional.empty(); }
            public DecisionBundle save(DecisionInputKey input, DecisionBundle bundle) { return bundle; }
            public List<DecisionBundle> findCurrentByCandidate(UUID candidateId) { return old; }
            public List<DecisionBundle> findByCandidateAndProfileVersion(UUID candidateId, String profileVersion) {
                return old.stream().filter(value -> value.decision().profileVersion().equals(profileVersion)).toList();
            }
        };
    }

    private static DecisionBundle bundle(UUID jobId, String profileVersion, EligibilityStatus status,
                                         Map<RuleType, RuleResult> rules) {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant assessedAt = Instant.parse("2026-08-24T00:00:00Z");
        var evidence = List.of(UUID.randomUUID());
        var job = new JobPosting(jobId, eventId, organizationId, "J-" + jobId, "信息技术岗位",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, "杭州", 1,
            EducationLevel.BACHELOR, Set.of(), Set.of(), null, null, null, Set.of(), "",
            "https://example.gov.cn/" + jobId, evidence);
        var organization = new Organization(organizationId, "杭州市信息中心", OrganizationType.PUBLIC_INSTITUTION,
            "市级", "浙江", "杭州", null, null, "https://example.gov.cn");
        var event = new RecruitmentEvent(eventId, "公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            AS_OF.minusDays(10), AS_OF.minusDays(5), AS_OF.plusDays(5), job.sourceUrl(),
            EmploymentType.ESTABLISHMENT, evidence);
        var eligibility = new EligibilityAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, status, rules,
            evidence, "test", assessedAt, profileVersion, "a".repeat(64));
        var fit = new FitAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, List.of(), "test",
            profileVersion, "a".repeat(64), assessedAt);
        var stability = new StabilityAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, List.of(), "test",
            profileVersion, "a".repeat(64), assessedAt);
        var decision = new DecisionAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, eligibility.id(), fit.id(),
            stability.id(), status, status == EligibilityStatus.INELIGIBLE ? OpportunityTier.EXCLUDED : OpportunityTier.T1,
            status == EligibilityStatus.INELIGIBLE ? RecommendationStatus.EXCLUDED : RecommendationStatus.REVIEW,
            fit.score(), stability.score(), 0, "test", profileVersion, "a".repeat(64), assessedAt);
        return new DecisionBundle(eligibility, fit, stability, decision,
            new JobContext(job, organization, event, "a".repeat(64), true));
    }

    private static RuleResult rule(EligibilityStatus status, String explanation) {
        return new RuleResult(status, explanation);
    }
}
