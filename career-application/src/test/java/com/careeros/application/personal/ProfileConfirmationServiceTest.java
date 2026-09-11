package com.careeros.application.personal;

import static com.careeros.domain.CandidateFacts.CandidateFactKey.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.CandidateProfileService;
import com.careeros.application.DecisionPorts.DecisionBundle;
import com.careeros.application.DecisionPorts.DecisionInputKey;
import com.careeros.application.DecisionPorts.DecisionSnapshots;
import com.careeros.application.DecisionPorts.JobContext;
import com.careeros.application.RepositoryPorts;
import com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength;
import com.careeros.application.personal.ProfileConfirmationPorts.ConfirmationLedger;
import com.careeros.application.personal.ProfileConfirmationPorts.LedgerEntry;
import com.careeros.application.personal.ProfileConfirmationPorts.Stage;
import com.careeros.application.personal.ProfileConfirmationService.ConfirmationRequest;
import com.careeros.application.personal.ProfileConfirmationService.DeclaredValue;
import com.careeros.application.personal.ProfileConfirmationService.Result;
import com.careeros.domain.*;
import com.careeros.domain.CandidateFacts.CandidateFactConfirmation;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileConfirmationServiceTest {
    private static final UUID CANDIDATE_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 24);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T15:00:00Z"), ZoneOffset.UTC);

    private InMemoryProfiles profiles;
    private InMemoryConfirmations confirmations;
    private InMemoryLedger ledger;
    private CandidateProfileService profileService;

    @BeforeEach void setUp() {
        profiles = new InMemoryProfiles(candidate(PoliticalAffiliation.UNKNOWN));
        confirmations = new InMemoryConfirmations();
        ledger = new InMemoryLedger();
        profileService = new CandidateProfileService(profiles, confirmations, CLOCK);
    }

    // --- 确认回答不等于官方核实 ---

    /**
     * 用户在对话里说"我是党员"，只能作为本人声明记录。系统没有见过任何材料，
     * 把它记成官方核实会让后续每一个结论都建立在一句话上。
     */
    @Test void aConversationalAnswerIsRecordedAsSelfReportedAndSaysSo() {
        var outcome = service(recomputes()).record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)), AS_OF);

        assertThat(outcome.result()).isEqualTo(Result.RECORDED);
        assertThat(outcome.evidenceStrength()).isEqualTo(EvidenceStrength.SELF_REPORTED);
        assertThat(outcome.message()).contains("本人的声明").contains("不是官方核实");
        assertThat(profiles.stored.politicalAffiliation()).isEqualTo(PoliticalAffiliation.CPC_MEMBER);
    }

    /** 写入的来源恒为本人确认；没有任何入参能把它抬成经过核验的证据。 */
    @Test void nothingInTheRequestCanRaiseTheEvidenceLevelAboveSelfReported() {
        service(recomputes()).record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)), AS_OF);

        assertThat(confirmations.stored.get(POLITICAL_AFFILIATION).source())
            .isEqualTo(CandidateFacts.CandidateFactSource.USER_CONFIRMED);
        // 更强的保证：这条路径的返回值根本表达不了"已核验"，构造出来就会失败。
        assertThatThrownBy(() -> new ProfileConfirmationService.ConfirmationOutcome(
            Result.RECORDED, EvidenceStrength.VERIFIED, "a", "b", "已核验", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never verified");
    }

    /** 要材料才能判断的字段不能靠一句话确认，并且一个字都不写。 */
    @Test void aFactThatNeedsDocumentsIsRefusedWithoutWritingAnything() {
        String before = profiles.stored.profileVersion();

        var outcome = service(recomputes()).record(new ConfirmationRequest(CANDIDATE_ID, EDUCATION_RECORDS,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER),
            before, "key-1", false), AS_OF);

        assertThat(outcome.result()).isEqualTo(Result.REQUIRES_DOCUMENT);
        assertThat(outcome.message()).contains("材料");
        assertThat(profiles.stored.profileVersion()).isEqualTo(before);
        assertThat(ledger.entries).isEmpty();
    }

    // --- 不承诺"必然解锁" ---

    /**
     * 改写既有答案前只说"这会改什么"，不说"确认后会解锁几个岗位"。
     * 会不会变、变成什么，只有真的重算过才知道。
     */
    @Test void aPendingChangePromisesNothingAboutUnlockedJobs() {
        profiles.stored = candidate(PoliticalAffiliation.NON_MEMBER);
        var outcome = service(recomputes()).record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)), AS_OF);

        assertThat(outcome.result()).isEqualTo(Result.CHANGE_REQUIRES_ACKNOWLEDGEMENT);
        assertThat(outcome.pendingChange().from()).isEqualTo("NON_MEMBER");
        assertThat(outcome.pendingChange().to()).isEqualTo("CPC_MEMBER");
        assertThat(outcome.message()).contains("重算才知道");
        assertThat(outcome.message()).doesNotContain("解锁");
        assertThat(outcome.changes()).isNull();
        assertThat(profiles.stored.politicalAffiliation()).isEqualTo(PoliticalAffiliation.NON_MEMBER);
    }

    @Test void anAcknowledgedChangeIsApplied() {
        profiles.stored = candidate(PoliticalAffiliation.NON_MEMBER);
        var outcome = service(recomputes()).record(new ConfirmationRequest(CANDIDATE_ID, POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER),
            profiles.stored.profileVersion(), "key-1", true), AS_OF);

        assertThat(outcome.result()).isEqualTo(Result.RECORDED);
        assertThat(profiles.stored.politicalAffiliation()).isEqualTo(PoliticalAffiliation.CPC_MEMBER);
    }

    /** 报告的是真的算出来的结果，不是承诺。 */
    @Test void reportedChangesComeFromAnActualRecomputation() {
        var outcome = service(recomputes()).record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)), AS_OF);

        assertThat(outcome.changes()).isNotNull();
        assertThat(outcome.changes().available()).isTrue();
        assertThat(outcome.changes().newlyEligibleCount()).isEqualTo(1);
        assertThat(outcome.changes().affectedJobs()).extracting(
            DecisionChangeSummary.AffectedJob::previousStatus, DecisionChangeSummary.AffectedJob::currentStatus)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(
                EligibilityStatus.NEEDS_CONFIRMATION, EligibilityStatus.ELIGIBLE));
    }

    // --- 重算由业务服务保证 ---

    /** 调用方只调一次；重算不需要谁记得再调一次工具。 */
    @Test void recomputationHappensInTheSameCallWithoutASecondToolCall() {
        var recomputations = new AtomicInteger();
        var outcome = service(counting(recomputations)).record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)), AS_OF);

        assertThat(outcome.result()).isEqualTo(Result.RECORDED);
        assertThat(recomputations).hasValue(1);
    }

    /**
     * 重算失败不能把已记录的回答丢掉，也不能装作结论已经刷新。台账停在 WRITTEN，
     * 带同一把钥匙重试就从重算接着做，而不是重新写一遍资料、再推高一次版本。
     */
    @Test void aFailedRecomputationKeepsTheAnswerAndResumesOnRetry() {
        var failing = new AtomicInteger();
        var service = service((candidateId, jobId, now) -> {
            if (failing.getAndIncrement() == 0) throw new IllegalStateException("assessor unavailable");
            return bundle(profiles.stored.profileVersion(), EligibilityStatus.ELIGIBLE);
        });

        var deferred = service.record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)), AS_OF);

        assertThat(deferred.result()).isEqualTo(Result.RECORDED_RECOMPUTE_DEFERRED);
        assertThat(deferred.message()).contains("尚未重算完成");
        assertThat(profiles.stored.politicalAffiliation()).isEqualTo(PoliticalAffiliation.CPC_MEMBER);
        assertThat(ledger.entries).singleElement().satisfies(entry ->
            assertThat(entry.stage()).isEqualTo(Stage.WRITTEN));
        String afterWrite = profiles.stored.profileVersion();

        var resumed = service.record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)), AS_OF);

        assertThat(resumed.result()).isEqualTo(Result.RECORDED);
        assertThat(resumed.changes()).isNotNull();
        // 没有第二次写入：版本没有再被推高。
        assertThat(profiles.stored.profileVersion()).isEqualTo(afterWrite);
        assertThat(ledger.entries).singleElement().satisfies(entry ->
            assertThat(entry.stage()).isEqualTo(Stage.RECOMPUTED));
    }

    // --- 幂等与版本检查 ---

    @Test void replayingTheSameKeyDoesNotWriteTwiceOrBumpTheVersionAgain() {
        var service = service(recomputes());
        service.record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)), AS_OF);
        String afterFirst = profiles.stored.profileVersion();

        var replay = service.record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)), AS_OF);

        assertThat(replay.result()).isEqualTo(Result.ALREADY_RECORDED);
        assertThat(profiles.stored.profileVersion()).isEqualTo(afterFirst);
        assertThat(ledger.entries).hasSize(1);
    }

    /** 同一把钥匙换个答案是调用方的错误，不能被当作一次新的确认默默写进去。 */
    @Test void reusingAKeyForADifferentAnswerIsRefused() {
        var service = service(recomputes());
        service.record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)), AS_OF);
        String afterFirst = profiles.stored.profileVersion();

        var reused = service.record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.NON_MEMBER)), AS_OF);

        assertThat(reused.result()).isEqualTo(Result.IDEMPOTENCY_KEY_REUSED);
        assertThat(profiles.stored.politicalAffiliation()).isEqualTo(PoliticalAffiliation.CPC_MEMBER);
        assertThat(profiles.stored.profileVersion()).isEqualTo(afterFirst);
    }

    /**
     * 用户是在某个资料版本下看到那个问题的。等他回答时资料已经被别处改过，
     * 此刻写入会静默覆盖那次修改——所以什么都不写，请他重新确认。
     */
    @Test void anAnswerAgainstAStaleProfileVersionWritesNothing() {
        var outcome = service(recomputes()).record(new ConfirmationRequest(CANDIDATE_ID, POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER),
            "profile-the-user-saw", "key-1", false), AS_OF);

        assertThat(outcome.result()).isEqualTo(Result.PROFILE_VERSION_CHANGED);
        assertThat(outcome.message()).contains("没有写入任何内容");
        assertThat(profiles.stored.politicalAffiliation()).isEqualTo(PoliticalAffiliation.UNKNOWN);
        assertThat(ledger.entries).isEmpty();
    }

    @Test void answeringTheSameValueTwiceWithANewKeyWritesNothingFurther() {
        var service = service(recomputes());
        service.record(request(POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)), AS_OF);
        String afterFirst = profiles.stored.profileVersion();

        var again = service.record(new ConfirmationRequest(CANDIDATE_ID, POLITICAL_AFFILIATION,
            new DeclaredValue.OfPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER),
            afterFirst, "key-2", false), AS_OF);

        assertThat(again.result()).isEqualTo(Result.NO_CHANGE_NEEDED);
        assertThat(profiles.stored.profileVersion()).isEqualTo(afterFirst);
    }

    /** 答案类型与字段对不上时不写入——把性别答案记进政治面貌是最难发现的那种错。 */
    @Test void anAnswerOfTheWrongTypeForTheFieldIsRefused() {
        var outcome = service(recomputes()).record(new ConfirmationRequest(CANDIDATE_ID, POLITICAL_AFFILIATION,
            new DeclaredValue.OfGender(Gender.FEMALE), profiles.stored.profileVersion(), "key-1", false), AS_OF);

        assertThat(outcome.result()).isEqualTo(Result.REQUIRES_DOCUMENT);
        assertThat(outcome.message()).contains("类型");
        assertThat(profiles.stored.politicalAffiliation()).isEqualTo(PoliticalAffiliation.UNKNOWN);
    }

    // --- 固定装置 ---

    private ProfileConfirmationService service(com.careeros.application.DecisionPorts.DecisionAssessor assessor) {
        var diffs = new CandidateDecisionDiffService(profiles, snapshots(), assessor, CLOCK);
        return new ProfileConfirmationService(profiles, profileService, diffs, ledger, CLOCK);
    }

    private ConfirmationRequest request(CandidateFactKey key, DeclaredValue value) {
        return new ConfirmationRequest(CANDIDATE_ID, key, value, profiles.stored.profileVersion(), "key-1", false);
    }

    private com.careeros.application.DecisionPorts.DecisionAssessor recomputes() {
        return (candidateId, jobId, now) -> bundle(profiles.stored.profileVersion(), EligibilityStatus.ELIGIBLE);
    }

    private com.careeros.application.DecisionPorts.DecisionAssessor counting(AtomicInteger calls) {
        return (candidateId, jobId, now) -> {
            calls.incrementAndGet();
            return bundle(profiles.stored.profileVersion(), EligibilityStatus.ELIGIBLE);
        };
    }

    private DecisionSnapshots snapshots() {
        return new DecisionSnapshots() {
            public Optional<DecisionBundle> findByInput(DecisionInputKey input) { return Optional.empty(); }
            public DecisionBundle save(DecisionInputKey input, DecisionBundle value) { return value; }
            public List<DecisionBundle> findCurrentByCandidate(UUID candidateId) { return List.of(); }
            public List<DecisionBundle> findByCandidateAndProfileVersion(UUID candidateId, String profileVersion) {
                // 对照基线：回答之前那一版资料下算出来的结论。
                return ledger.baselineVersions.contains(profileVersion)
                    ? List.of(bundle(profileVersion, EligibilityStatus.NEEDS_CONFIRMATION)) : List.of();
            }
        };
    }

    private static CandidateProfile candidate(PoliticalAffiliation affiliation) {
        return new CandidateProfile(CANDIDATE_ID, "候选人", PartialDate.month(1997, 4),
            EducationLevel.MASTER, Set.of("计算机科学"), 2027, 0, Set.of(), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), "profile-seed", Set.of(), Set.of(), Set.of(), Set.of(),
            List.of(), Gender.UNKNOWN, affiliation, List.of(),
            ApplicationTimeStatus.UNDECLARED, ApplicationTimeStatus.UNDECLARED);
    }

    private static DecisionBundle bundle(String profileVersion, EligibilityStatus status) {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant assessedAt = Instant.parse("2026-08-24T00:00:00Z");
        String fingerprint = "a".repeat(64);
        var evidence = List.of(UUID.randomUUID());
        var job = new JobPosting(JOB_ID, eventId, organizationId, "J-1", "信息技术岗位",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, "杭州", 1,
            EducationLevel.BACHELOR, Set.of(), Set.of(), null, null, null, Set.of(), "",
            "https://example.gov.cn/job", evidence);
        var organization = new Organization(organizationId, "杭州市信息中心", OrganizationType.PUBLIC_INSTITUTION,
            "市级", "浙江", "杭州", null, null, "https://example.gov.cn");
        var event = new RecruitmentEvent(eventId, "公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            AS_OF.minusDays(10), AS_OF.minusDays(5), AS_OF.plusDays(5), job.sourceUrl(),
            EmploymentType.ESTABLISHMENT, evidence);
        var rules = Map.of(RuleType.POLITICAL_AFFILIATION, new EligibilityAssessment.RuleResult(status,
            status == EligibilityStatus.ELIGIBLE ? "政治面貌满足" : "候选人政治面貌尚未确认"));
        var eligibility = new EligibilityAssessment(UUID.randomUUID(), CANDIDATE_ID, JOB_ID, status, rules,
            evidence, "test-evaluator", assessedAt, profileVersion, fingerprint);
        var fit = new FitAssessment(UUID.randomUUID(), CANDIDATE_ID, JOB_ID, List.of(), "test-evaluator",
            profileVersion, fingerprint, assessedAt);
        var stability = new StabilityAssessment(UUID.randomUUID(), CANDIDATE_ID, JOB_ID, List.of(), "test-evaluator",
            profileVersion, fingerprint, assessedAt);
        var decision = new DecisionAssessment(UUID.randomUUID(), CANDIDATE_ID, JOB_ID, eligibility.id(), fit.id(),
            stability.id(), status, OpportunityTier.T1, RecommendationStatus.REVIEW, fit.score(),
            stability.score(), 0, "test-evaluator", profileVersion, fingerprint, assessedAt);
        return new DecisionBundle(eligibility, fit, stability, decision,
            new JobContext(job, organization, event, fingerprint, true));
    }

    private static final class InMemoryProfiles implements RepositoryPorts.CandidateProfiles {
        private CandidateProfile stored;
        InMemoryProfiles(CandidateProfile seed) { this.stored = seed; }
        public CandidateProfile save(CandidateProfile value) { stored = value; return value; }
        public Optional<CandidateProfile> findById(UUID id) {
            return id.equals(CANDIDATE_ID) ? Optional.of(stored) : Optional.empty();
        }
        public Optional<CandidateProfile> findByIdForUpdate(UUID id) { return findById(id); }
        public List<CandidateProfile> findAll() { return List.of(stored); }
        public void deleteById(UUID id) { }
    }

    private static final class InMemoryConfirmations implements RepositoryPorts.CandidateFactConfirmations {
        private final Map<CandidateFactKey, CandidateFactConfirmation> stored = new EnumMap<>(CandidateFactKey.class);
        public List<CandidateFactConfirmation> findByCandidateId(UUID candidateId) {
            return List.copyOf(stored.values());
        }
        public List<CandidateFactConfirmation> saveAll(List<CandidateFactConfirmation> values) {
            values.forEach(value -> stored.put(value.factKey(), value));
            return values;
        }
    }

    private static final class InMemoryLedger implements ConfirmationLedger {
        private final List<LedgerEntry> entries = new ArrayList<>();
        private final Set<String> baselineVersions = new HashSet<>();
        public Optional<LedgerEntry> find(UUID candidateId, String idempotencyKey) {
            return entries.stream()
                .filter(entry -> entry.candidateId().equals(candidateId)
                    && entry.idempotencyKey().equals(idempotencyKey))
                .findFirst();
        }
        public LedgerEntry save(LedgerEntry entry) {
            entries.removeIf(stored -> stored.candidateId().equals(entry.candidateId())
                && stored.idempotencyKey().equals(entry.idempotencyKey()));
            entries.add(entry);
            baselineVersions.add(entry.profileVersionBefore());
            return entry;
        }
    }
}
