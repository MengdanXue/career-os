package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.JobAdmissionPorts.AdmissionSummary;
import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.*;
import com.careeros.domain.CandidateFacts.CandidateFactConfirmation;
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
        var repeated = fixture.service.assess(fixture.candidateId, fixture.jobId, NOW.plus(java.time.Duration.ofDays(30)));
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
    void legacyEligibilitySnapshotIsNotExposedAsTheCurrentDecision() {
        var fixture = fixture(candidate("profile-v1", Set.of("计算机科学与技术")));
        seedLegacyEligibleSnapshotWithUnknownRequirements(fixture);

        assertThatThrownBy(() -> fixture.service.current(fixture.candidateId, fixture.jobId))
            .isInstanceOf(DecisionExceptions.DecisionNotFoundException.class);

        assertThat(fixture.snapshots.saved).isEqualTo(1);
    }

    @Test
    void legacyEligibleSnapshotIsReassessedForUnchangedUnknownAgeAndExperience() {
        var fixture = fixture(candidate("profile-v1", Set.of("计算机科学与技术")));
        var legacy = seedLegacyEligibleSnapshotWithUnknownRequirements(fixture);

        var reassessed = fixture.service.assess(fixture.candidateId, fixture.jobId, NOW.plusSeconds(60));
        var repeated = fixture.service.assess(fixture.candidateId, fixture.jobId, NOW.plusSeconds(120));

        assertThat(reassessed.decision().eligibilityStatus()).isEqualTo(EligibilityStatus.UNCERTAIN);
        assertThat(reassessed.decision().recommendationStatus()).isEqualTo(RecommendationStatus.REVIEW);
        assertThat(reassessed.eligibility().ruleResults().get(RuleType.AGE).status())
            .isEqualTo(EligibilityStatus.UNCERTAIN);
        assertThat(reassessed.eligibility().ruleResults().get(RuleType.EXPERIENCE).status())
            .isEqualTo(EligibilityStatus.UNCERTAIN);
        assertThat(reassessed.decision().id()).isNotEqualTo(legacy.decision().id());
        assertThat(reassessed.decision().jobContentFingerprint()).isEqualTo(legacy.decision().jobContentFingerprint());
        assertThat(reassessed.decision().profileVersion()).isEqualTo(legacy.decision().profileVersion());
        assertThat(reassessed.decision().evaluatorVersion()).contains(EligibilityEvaluator.VERSION);
        assertThat(reassessed.eligibility().evaluatorVersion()).isEqualTo(reassessed.decision().evaluatorVersion());
        assertThat(reassessed.eligibility().evaluatorVersion())
            .as("eligibility_assessment.evaluator_version is VARCHAR(80)").hasSizeLessThanOrEqualTo(80);
        assertThat(repeated.decision().id()).isEqualTo(reassessed.decision().id());
        assertThat(fixture.service.current(fixture.candidateId, fixture.jobId).decision().id())
            .isEqualTo(reassessed.decision().id());
        assertThat(fixture.snapshots.saved).isEqualTo(2);
        assertThat(fixture.snapshots.values.values()).contains(legacy);
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
    void admissionIsRecheckedInsideTheTransactionalLock() {
        var fixture = fixture(candidate("profile-v1", Set.of("计算机科学与技术")));
        fixture.inputLock.beforeOperation = () -> fixture.admissions.save(JobAdmission.raw(
            fixture.jobId, NOW, JobAdmissionReason.CONTENT_CHANGED));

        assertThatThrownBy(() -> fixture.service.assess(fixture.candidateId, fixture.jobId, NOW))
            .isInstanceOf(DecisionExceptions.JobNotAdmittedException.class);

        assertThat(fixture.snapshots.saved).isZero();
    }

    @Test
    void verifiedIncludedJobWithUnknownEmploymentTypeIsNotDecisionReady() {
        var fixture = fixture(candidate("profile-v1", Set.of("计算机科学与技术")), EmploymentType.UNKNOWN);

        assertThatThrownBy(() -> fixture.service.assess(fixture.candidateId, fixture.jobId, NOW))
            .isInstanceOf(DecisionExceptions.JobNotAdmittedException.class)
            .hasMessageContaining("employment identity");

        assertThat(fixture.snapshots.saved).isZero();
    }

    @Test
    void storedCandidateValuesRemainUncertainUntilTheirCurrentFingerprintsAreConfirmed() {
        var fixture = fixture(candidate("profile-v1", Set.of("计算机科学与技术")));
        fixture.facts.values.clear();

        var result = fixture.service.assess(fixture.candidateId, fixture.jobId, NOW);

        assertThat(result.eligibility().status()).isEqualTo(EligibilityStatus.UNCERTAIN);
        assertThat(result.fit().score()).isZero();
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

    @Test
    void correctedApplicationDeadlineCreatesANewDecisionInput() {
        var fixture = fixture(candidate("profile-v1", Set.of("计算机科学与技术")));
        var first = fixture.service.assess(fixture.candidateId, fixture.jobId, NOW);
        var original = fixture.contexts.context;
        var event = original.event();
        fixture.contexts.context = new JobContext(original.job(), original.organization(),
            new RecruitmentEvent(event.id(), event.title(), event.recruitmentYear(), event.eventType(),
                event.publishedOn(), event.applicationStartsOn(), LocalDate.of(2026, 9, 15),
                event.sourceUrl(), event.defaultEmploymentType(), event.evidenceIds()),
            original.contentFingerprint(), original.active());

        var corrected = fixture.service.assess(fixture.candidateId, fixture.jobId, NOW.plusSeconds(60));

        assertThat(corrected.decision().id()).isNotEqualTo(first.decision().id());
        assertThat(corrected.decision().evaluatorVersion()).isNotEqualTo(first.decision().evaluatorVersion());
        assertThat(corrected.eligibility().evaluatorVersion()).isEqualTo(corrected.decision().evaluatorVersion());
        assertThat(corrected.fit().evaluatorVersion()).isEqualTo(corrected.decision().evaluatorVersion());
        assertThat(corrected.stability().evaluatorVersion()).isEqualTo(corrected.decision().evaluatorVersion());
        assertThat(first.eligibility().evaluatorVersion()).isNotEqualTo(corrected.eligibility().evaluatorVersion());
        assertThat(first.fit().evaluatorVersion()).isNotEqualTo(corrected.fit().evaluatorVersion());
        assertThat(first.stability().evaluatorVersion()).isNotEqualTo(corrected.stability().evaluatorVersion());
        assertThat(fixture.snapshots.saved).isEqualTo(2);
    }

    private static Fixture fixture(CandidateProfile initialCandidate) {
        return fixture(initialCandidate, EmploymentType.ESTABLISHMENT);
    }

    private static DecisionBundle seedLegacyEligibleSnapshotWithUnknownRequirements(Fixture fixture) {
        var original = fixture.contexts.context;
        var job = original.job();
        var unknownRequirements = new JobPosting(job.id(), job.recruitmentEventId(), job.organizationId(),
            job.externalJobCode(), job.title(), job.jobFamily(), job.employmentType(), job.location(),
            job.headcount(), job.minimumEducation(), job.exactMajors(), job.acceptedGraduationYears(),
            null, null, null, job.requiredProfessionalTitles(), job.duties(), job.sourceUrl(), job.evidenceIds());
        fixture.contexts.context = new JobContext(unknownRequirements, original.organization(), original.event(),
            original.contentFingerprint(), original.active());
        // Literal identity and verdict from before missing qualification evidence became uncertain.
        String oldVersion = "decision-v3-qualification-cutoff@qualification=2026-09-01";
        String profileVersion = "profile-v1";
        var rules = new EnumMap<RuleType, EligibilityAssessment.RuleResult>(RuleType.class);
        for (var type : RuleType.values()) {
            rules.put(type, new EligibilityAssessment.RuleResult(EligibilityStatus.ELIGIBLE, "旧版判定符合"));
        }
        var eligibility = new EligibilityAssessment(UUID.randomUUID(), fixture.candidateId, fixture.jobId,
            EligibilityStatus.ELIGIBLE, rules, job.evidenceIds(), oldVersion, NOW, profileVersion, FINGERPRINT);
        var fit = new FitAssessment(UUID.randomUUID(), fixture.candidateId, fixture.jobId, List.of(),
            oldVersion, profileVersion, FINGERPRINT, NOW);
        var stability = new StabilityAssessment(UUID.randomUUID(), fixture.candidateId, fixture.jobId, List.of(),
            oldVersion, profileVersion, FINGERPRINT, NOW);
        var decision = new DecisionAssessment(UUID.randomUUID(), fixture.candidateId, fixture.jobId,
            eligibility.id(), fit.id(), stability.id(), EligibilityStatus.ELIGIBLE, OpportunityTier.T2,
            RecommendationStatus.REVIEW, 0, 0, 0, oldVersion, profileVersion, FINGERPRINT, NOW);
        var bundle = new DecisionBundle(eligibility, fit, stability, decision, fixture.contexts.context);
        return fixture.snapshots.save(new DecisionInputKey(fixture.candidateId, fixture.jobId, profileVersion,
            FINGERPRINT, oldVersion), bundle);
    }

    private static Fixture fixture(CandidateProfile initialCandidate, EmploymentType employmentType) {
        var candidates = new MemoryCandidates();
        candidates.save(initialCandidate);
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        var organization = new Organization(organizationId, "杭州市信息中心", OrganizationType.PUBLIC_INSTITUTION, "市级", "浙江", "杭州", null, null, "https://example.gov.cn");
        var event = new RecruitmentEvent(eventId, "公开招聘", 2026, EventType.PUBLIC_INSTITUTION, LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 9, 1), "https://example.gov.cn", EmploymentType.ESTABLISHMENT, List.of(UUID.randomUUID()));
        var job = new JobPosting(jobId, eventId, organizationId, "A01", "Java工程师", JobFamily.SOFTWARE, employmentType, "杭州", 1, EducationLevel.BACHELOR, Set.of("计算机科学与技术"), Set.of(), null, null, 3, Set.of(), "Java PostgreSQL 数据治理", "https://example.gov.cn", List.of(UUID.randomUUID()));
        var contexts = new MemoryContexts(new JobContext(job, organization, event, FINGERPRINT, true));
        var snapshots = new MemorySnapshots();
        var assessments = new MemoryEligibility();
        var admissions = new MemoryAdmissions();
        admissions.save(new JobAdmission(
            jobId, DataQualityStatus.VERIFIED, TargetScopeStatus.INCLUDED,
            Set.of(JobAdmissionReason.TARGET_TECHNICAL_ROLE), "admission-v1", NOW, true));
        var inputLock = new SynchronizedDecisionInputLock();
        var facts = new MemoryFacts();
        for (var key : CandidateFacts.CandidateFactKey.values()) {
            facts.values.add(new CandidateFactConfirmation(initialCandidate.id(), key,
                CandidateFacts.CandidateFactStatus.CONFIRMED, CandidateFacts.fingerprint(initialCandidate, key),
                CandidateFacts.CandidateFactSource.USER_CONFIRMED, NOW, NOW));
        }
        var service = new DecisionIntelligenceService(candidates, facts, assessments, contexts, organizationId1 -> List.of(), snapshots, admissions, inputLock, new EligibilityEvaluator(), new FitEvaluator(), new StabilityEvaluator());
        return new Fixture(initialCandidate.id(), jobId, candidates, facts, contexts, snapshots, admissions, inputLock, service);
    }

    private static CandidateProfile candidate(String version, Set<String> majors) {
        return candidateWithId(UUID.randomUUID(), version, majors);
    }

    private static CandidateProfile candidateWithId(UUID id, String version, Set<String> majors) {
        return new CandidateProfile(id, "候选人", new PartialDate(1992, 12, null), EducationLevel.MASTER, majors, 2018, 6,
            Set.of(), List.of("杭州"), Set.of(EmploymentType.ESTABLISHMENT), version,
            Set.of("Java", "PostgreSQL"), Set.of("数据治理"), Set.of(JobFamily.SOFTWARE), Set.of(OrganizationType.PUBLIC_INSTITUTION));
    }

    private record Fixture(UUID candidateId, UUID jobId, MemoryCandidates candidates, MemoryFacts facts, MemoryContexts contexts, MemorySnapshots snapshots, MemoryAdmissions admissions, SynchronizedDecisionInputLock inputLock, DecisionIntelligenceService service) {}

    private abstract static class MemoryRepository<T> implements RepositoryPorts.Repository<T> {
        final Map<UUID,T> values = new LinkedHashMap<>();
        abstract UUID id(T value);
        public T save(T value) { values.put(id(value), value); return value; }
        public Optional<T> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        public List<T> findAll() { return List.copyOf(values.values()); }
        public void deleteById(UUID id) { values.remove(id); }
    }
    private static final class MemoryCandidates extends MemoryRepository<CandidateProfile> implements RepositoryPorts.CandidateProfiles {
        UUID id(CandidateProfile value) { return value.id(); }
        public Optional<CandidateProfile> findByIdForUpdate(UUID id) { return findById(id); }
    }
    private static final class MemoryFacts implements RepositoryPorts.CandidateFactConfirmations {
        private final List<CandidateFactConfirmation> values = new ArrayList<>();
        public List<CandidateFactConfirmation> findByCandidateId(UUID candidateId) { return values.stream().filter(value -> value.candidateProfileId().equals(candidateId)).toList(); }
        public List<CandidateFactConfirmation> saveAll(List<CandidateFactConfirmation> confirmations) { values.addAll(confirmations); return confirmations; }
    }
    private static final class MemoryEligibility extends MemoryRepository<EligibilityAssessment> implements RepositoryPorts.EligibilityAssessments { UUID id(EligibilityAssessment value) { return value.id(); } }
    private static final class MemoryContexts implements JobContexts {
        private JobContext context;
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
        private Runnable beforeOperation = () -> {};
        @Override public synchronized <T> T execute(String inputFingerprint, Supplier<T> operation) {
            keys.add(inputFingerprint);
            beforeOperation.run();
            beforeOperation = () -> {};
            return operation.get();
        }
    }
}
