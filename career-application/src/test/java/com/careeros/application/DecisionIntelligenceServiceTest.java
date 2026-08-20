package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.JobAdmissionPorts.AdmissionSummary;
import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DecisionIntelligenceServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void hardIneligibleJobIsExcludedRegardlessOfSoftFit() {
        var fixture = fixture(candidate("profile-v1", Set.of("不匹配专业")));

        var result = fixture.service.assess(fixture.candidateId, fixture.jobId, NOW);

        assertThat(result.decision().eligibilityStatus()).isEqualTo(EligibilityStatus.INELIGIBLE);
        assertThat(result.decision().tier()).isEqualTo(OpportunityTier.EXCLUDED);
        assertThat(result.decision().recommendationStatus()).isEqualTo(RecommendationStatus.EXCLUDED);
    }

    @Test
    void identicalInputReusesSnapshotButProfileVersionChangeCreatesAnother() {
        var fixture = fixture(candidate("profile-v1", Set.of("计算机科学与技术")));

        var first = fixture.service.assess(fixture.candidateId, fixture.jobId, NOW);
        var repeated = fixture.service.assess(fixture.candidateId, fixture.jobId, NOW.plusSeconds(60));
        fixture.candidates.save(candidateWithId(fixture.candidateId, "profile-v2", Set.of("计算机科学与技术")));
        var changed = fixture.service.assess(fixture.candidateId, fixture.jobId, NOW.plusSeconds(120));

        assertThat(repeated.decision().id()).isEqualTo(first.decision().id());
        assertThat(changed.decision().id()).isNotEqualTo(first.decision().id());
        assertThat(changed.eligibility().profileVersion()).isEqualTo("profile-v2");
        assertThat(changed.eligibility().jobContentFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(fixture.snapshots.saved).isEqualTo(2);
    }

    @Test
    void currentDecisionNeverCreatesAMissingSnapshot() {
        var fixture = fixture(candidate("profile-v1", Set.of("计算机科学与技术")));

        assertThatThrownBy(() -> fixture.service.current(fixture.candidateId, fixture.jobId))
            .isInstanceOf(DecisionExceptions.DecisionNotFoundException.class);

        assertThat(fixture.snapshots.saved).isZero();
    }

    @Test
    void rawJobIsRejectedBeforeAnyAssessmentIsSaved() {
        var fixture = fixture(candidate("profile-v1", Set.of("计算机科学与技术")));
        fixture.admissions.save(JobAdmission.raw(
            fixture.jobId, NOW, JobAdmissionReason.LEGACY_UNVERIFIED));

        assertThatThrownBy(() -> fixture.service.assess(fixture.candidateId, fixture.jobId, NOW))
            .isInstanceOf(DecisionExceptions.JobNotAdmittedException.class);

        assertThat(fixture.snapshots.saved).isZero();
    }

    @Test
    void concurrentIdenticalAssessmentsShareOneSnapshot() throws Exception {
        var fixture = fixture(candidate("profile-v1", Set.of("计算机科学与技术")));
        fixture.snapshots.slowFind = true;

        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8)
                .mapToObj(ignored -> (java.util.concurrent.Callable<DecisionBundle>)
                    () -> fixture.service.assess(fixture.candidateId, fixture.jobId, NOW))
                .toList();
            var results = executor.invokeAll(tasks).stream().map(future -> {
                try { return future.get(); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            }).toList();

            assertThat(results).extracting(result -> result.decision().id()).containsOnly(results.getFirst().decision().id());
        }
        assertThat(fixture.snapshots.saved).isEqualTo(1);
        assertThat(fixture.inputLock.keys).allMatch(key -> key.matches("[0-9a-f]{64}"));
    }

    private static Fixture fixture(CandidateProfile initialCandidate) {
        var candidates = new MemoryCandidates();
        candidates.save(initialCandidate);
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        var organization = new Organization(organizationId, "杭州市信息中心", OrganizationType.PUBLIC_INSTITUTION, "市级", "浙江", "杭州", null, null, "https://example.gov.cn");
        var event = new RecruitmentEvent(eventId, "公开招聘", 2026, EventType.PUBLIC_INSTITUTION, LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 9, 1), "https://example.gov.cn", EmploymentType.ESTABLISHMENT, List.of(UUID.randomUUID()));
        var job = new JobPosting(jobId, eventId, organizationId, "A01", "Java工程师", JobFamily.SOFTWARE, EmploymentType.ESTABLISHMENT, "杭州", 1, EducationLevel.BACHELOR, Set.of("计算机科学与技术"), Set.of(), null, null, 3, Set.of(), "Java PostgreSQL 数据治理", "https://example.gov.cn", List.of(UUID.randomUUID()));
        var contexts = new MemoryContexts(new JobContext(job, organization, event, FINGERPRINT, true));
        var snapshots = new MemorySnapshots();
        var assessments = new MemoryEligibility();
        var admissions = new MemoryAdmissions();
        admissions.save(new JobAdmission(
            jobId, DataQualityStatus.VERIFIED, TargetScopeStatus.INCLUDED,
            Set.of(JobAdmissionReason.TARGET_TECHNICAL_ROLE), "admission-v1", NOW, true));
        var inputLock = new SynchronizedDecisionInputLock();
        var service = new DecisionIntelligenceService(candidates, assessments, contexts, organizationId1 -> List.of(), snapshots, admissions, inputLock, new EligibilityEvaluator(), new FitEvaluator(), new StabilityEvaluator());
        return new Fixture(initialCandidate.id(), jobId, candidates, snapshots, admissions, inputLock, service);
    }

    private static CandidateProfile candidate(String version, Set<String> majors) {
        return candidateWithId(UUID.randomUUID(), version, majors);
    }

    private static CandidateProfile candidateWithId(UUID id, String version, Set<String> majors) {
        return new CandidateProfile(id, "候选人", new PartialDate(1992, 12, null), EducationLevel.MASTER, majors, 2018, 6,
            Set.of(), List.of("杭州"), Set.of(EmploymentType.ESTABLISHMENT), version,
            Set.of("Java", "PostgreSQL"), Set.of("数据治理"), Set.of(JobFamily.SOFTWARE), Set.of(OrganizationType.PUBLIC_INSTITUTION));
    }

    private record Fixture(UUID candidateId, UUID jobId, MemoryCandidates candidates, MemorySnapshots snapshots, MemoryAdmissions admissions, SynchronizedDecisionInputLock inputLock, DecisionIntelligenceService service) {}

    private abstract static class MemoryRepository<T> implements RepositoryPorts.Repository<T> {
        final Map<UUID,T> values = new LinkedHashMap<>();
        abstract UUID id(T value);
        public T save(T value) { values.put(id(value), value); return value; }
        public Optional<T> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        public List<T> findAll() { return List.copyOf(values.values()); }
        public void deleteById(UUID id) { values.remove(id); }
    }
    private static final class MemoryCandidates extends MemoryRepository<CandidateProfile> implements RepositoryPorts.CandidateProfiles { UUID id(CandidateProfile value) { return value.id(); } }
    private static final class MemoryEligibility extends MemoryRepository<EligibilityAssessment> implements RepositoryPorts.EligibilityAssessments { UUID id(EligibilityAssessment value) { return value.id(); } }
    private static final class MemoryContexts implements JobContexts {
        private final JobContext context;
        MemoryContexts(JobContext context) { this.context = context; }
        public Optional<JobContext> findByJobId(UUID id) { return context.job().id().equals(id) ? Optional.of(context) : Optional.empty(); }
        public List<JobContext> findActive() { return context.active() ? List.of(context) : List.of(); }
    }
    private static final class MemoryAdmissions implements JobAdmissions {
        private final Map<UUID, JobAdmission> values = new LinkedHashMap<>();
        public Optional<JobAdmission> findByJobId(UUID jobId) { return Optional.ofNullable(values.get(jobId)); }
        public JobAdmission save(JobAdmission value) { values.put(value.jobPostingId(), value); return value; }
        public AdmissionSummary summarize() { throw new UnsupportedOperationException(); }
    }
    private static final class MemorySnapshots implements DecisionSnapshots {
        private final Map<DecisionInputKey,DecisionBundle> values = new LinkedHashMap<>();
        int saved;
        boolean slowFind;
        public Optional<DecisionBundle> findByInput(DecisionInputKey input) {
            if (slowFind) try { Thread.sleep(20); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return Optional.ofNullable(values.get(input));
        }
        public DecisionBundle save(DecisionInputKey input, DecisionBundle bundle) { saved++; values.put(input, bundle); return bundle; }
        public List<DecisionBundle> findCurrentByCandidate(UUID candidateId) { return values.values().stream().filter(v -> v.decision().candidateProfileId().equals(candidateId)).toList(); }
    }
    private static final class SynchronizedDecisionInputLock implements DecisionInputLock {
        private final Set<String> keys = new LinkedHashSet<>();
        @Override public synchronized <T> T execute(String inputFingerprint, Supplier<T> operation) {
            keys.add(inputFingerprint);
            return operation.get();
        }
    }
}
