package com.careeros.application;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.AgentSession.SessionFilters;
import com.careeros.application.AgentSessionService.Reference.Outcome;
import com.careeros.application.DecisionPorts.DecisionBundle;
import com.careeros.application.DecisionPorts.JobContext;
import com.careeros.domain.*;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentSessionServiceTest {
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID CANDIDATE_ID = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T15:00:00Z"), ZoneOffset.UTC);
    private static final SessionFilters FILTERS =
        new SessionFilters(OpportunityTier.T1, "杭州", JobFamily.INFORMATION_SYSTEMS, 5);

    private InMemorySessions sessions;
    private InMemoryProfiles profiles;
    private InMemoryConfirmations confirmations;
    private AgentSessionService service;

    @BeforeEach void setUp() {
        sessions = new InMemorySessions();
        profiles = new InMemoryProfiles(candidate("profile-1"));
        confirmations = new InMemoryConfirmations();
        service = new AgentSessionService(sessions, profiles, confirmations, CLOCK);
    }

    // --- 序号只在记下来的顺序里解析 ---

    /** 用户说"第二个"，指的是他屏幕上那一份列表的第二个。 */
    @Test void anOrdinalResolvesAgainstTheListTheUserActuallySaw() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(first), bundle(second)));

        assertThat(service.resolveOrdinal(CANDIDATE_ID, SESSION_ID, 2))
            .isEqualTo(new AgentSessionService.Reference(Outcome.RESOLVED, second));
        assertThat(service.resolveOrdinal(CANDIDATE_ID, SESSION_ID, 1).jobPostingId()).isEqualTo(first);
    }

    /**
     * 资料改了，名次和结论都可能变。此时"第二个"不能重新排名求解——
     * 重新排出来的第二个可能是另一个岗位，而用户看不出系统换了个岗位在回答。
     */
    @Test void anOrdinalIsRefusedRatherThanReRankedAfterTheProfileChanges() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(first), bundle(second)));

        profiles.stored = candidate("profile-2");

        var reference = service.resolveOrdinal(CANDIDATE_ID, SESSION_ID, 2);
        assertThat(reference.outcome()).isEqualTo(Outcome.STALE_LISTING);
        assertThat(reference.jobPostingId()).isNull();
    }

    @Test void anOrdinalOutsideTheListIsNotGuessed() {
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));

        assertThat(service.resolveOrdinal(CANDIDATE_ID, SESSION_ID, 4).outcome()).isEqualTo(Outcome.OUT_OF_RANGE);
        assertThat(service.resolveOrdinal(CANDIDATE_ID, SESSION_ID, 0).outcome()).isEqualTo(Outcome.OUT_OF_RANGE);
    }

    @Test void anUnknownSessionResolvesNothing() {
        assertThat(service.resolveOrdinal(CANDIDATE_ID, UUID.randomUUID(), 1).outcome()).isEqualTo(Outcome.NO_SESSION);
    }

    /** 同一个岗位不能在一份列表里出现两次，否则序号不再唯一指向一个岗位。 */
    @Test void aRepeatedJobInOneListingIsRejected() {
        UUID job = UUID.randomUUID();
        assertThatThrownBy(() -> new AgentSession(SESSION_ID, CANDIDATE_ID, FILTERS,
            List.of(job, job), List.of(), "profile-1", Instant.now()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("repeat");
    }

    // --- 筛选条件与资料版本 ---

    @Test void theFiltersAndProfileVersionAreRemembered() {
        var session = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));

        assertThat(session.filters()).isEqualTo(FILTERS);
        assertThat(session.profileVersion()).isEqualTo("profile-1");
    }

    /**
     * 确认写入的乐观版本检查必须以"用户看到问题时的版本"为准。若拿此刻库里的版本去比，
     * 那个检查恒真，等于没检查。
     */
    @Test void theRememberedVersionIsTheOneTheUserSawNotTheCurrentOne() {
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));
        profiles.stored = candidate("profile-2");

        assertThat(service.profileVersionSeenBy(CANDIDATE_ID, SESSION_ID)).contains("profile-1");
    }

    @Test void aSecondListingReplacesTheOrderButKeepsTheSession() {
        UUID first = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(first)));

        var narrowed = new SessionFilters(OpportunityTier.T2, "浙江", JobFamily.DATA, 3);
        var session = service.remember(SESSION_ID, CANDIDATE_ID, narrowed, List.of(bundle(later)));

        assertThat(session.sessionId()).isEqualTo(SESSION_ID);
        assertThat(session.filters()).isEqualTo(narrowed);
        assertThat(service.resolveOrdinal(CANDIDATE_ID, SESSION_ID, 1).jobPostingId()).isEqualTo(later);
    }

    // --- 连续确认：会话版本要跟着自己的写入推进 ---

    /**
     * 用户自己的一次确认把资料版本推高了。不推进会话里记的版本，第二个待确认问题
     * 必定撞上版本检查——每一条确认单独测都是对的，连起来才暴露。
     */
    @Test void theSessionVersionAdvancesWithTheUsersOwnConfirmation() {
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));
        profiles.stored = candidate("profile-2");

        assertThat(service.advanceProfileVersion(CANDIDATE_ID, SESSION_ID, "profile-1", "profile-2")).isTrue();
        assertThat(service.profileVersionSeenBy(CANDIDATE_ID, SESSION_ID)).contains("profile-2");
    }

    /**
     * 只从这次写入的 before 推进到 after，不是"刷成当前值"。期间若有别处改动，
     * 会话版本已不是 before，这里什么都不做——版本检查照样会拦，那正是它要拦的情况。
     */
    @Test void anAdvanceFromAVersionTheSessionNoLongerHoldsIsRefused() {
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));

        assertThat(service.advanceProfileVersion(CANDIDATE_ID, SESSION_ID, "someone-elses-version", "profile-9")).isFalse();
        assertThat(service.profileVersionSeenBy(CANDIDATE_ID, SESSION_ID)).contains("profile-1");
    }

    /** 推进版本不能顺手清掉岗位顺序：用户看到的还是同一份列表，序号仍然有效。 */
    @Test void advancingTheVersionKeepsTheRecordedOrderAndPendingItems() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(first), bundle(second,
            Map.of(RuleType.POLITICAL_AFFILIATION, new EligibilityAssessment.RuleResult(
                EligibilityStatus.NEEDS_CONFIRMATION, "候选人政治面貌尚未确认")))));

        profiles.stored = candidate("profile-2");
        service.advanceProfileVersion(CANDIDATE_ID, SESSION_ID, "profile-1", "profile-2");

        assertThat(service.resolveOrdinal(CANDIDATE_ID, SESSION_ID, 2).jobPostingId()).isEqualTo(second);
        assertThat(sessions.stored.get(SESSION_ID).pendingConfirmations()).hasSize(1);
    }

    // --- 归属：会话读取要按候选人校验 ---

    /** 会话 ID 是可猜的 UUID；读别人的会话就能看到别人的待确认事项和岗位顺序。 */
    @Test void aSessionCannotBeReadByAnotherCandidate() {
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));

        assertThat(service.find(CANDIDATE_ID, SESSION_ID)).isPresent();
        assertThat(service.find(UUID.randomUUID(), SESSION_ID)).isEmpty();
    }

    // --- 待确认事项 ---

    /** 待确认事项要记下是哪个字段、哪个岗位问的、原话是什么，用户回答时才对得上。 */
    @Test void pendingConfirmationsCarryTheFieldTheJobAndTheQuestion() {
        UUID job = UUID.randomUUID();
        var session = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(job,
            Map.of(RuleType.POLITICAL_AFFILIATION, new EligibilityAssessment.RuleResult(
                EligibilityStatus.NEEDS_CONFIRMATION, "候选人政治面貌尚未确认")))));

        assertThat(session.pendingConfirmations()).singleElement().satisfies(pending -> {
            assertThat(pending.factKey()).isEqualTo(CandidateFactKey.POLITICAL_AFFILIATION);
            assertThat(pending.jobPostingId()).isEqualTo(job);
            assertThat(pending.question()).contains("政治面貌");
        });
        assertThat(session.pendingFor(CandidateFactKey.POLITICAL_AFFILIATION)).isPresent();
    }

    /**
     * 要凭材料判断的条件不进"回一句就能解决"的清单。把学历列在那里，会让用户以为
     * 说一句"我是硕士"就算数。
     */
    @Test void aConditionThatNeedsDocumentsIsNotListedAsConversationallyAnswerable() {
        var session = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID(),
            Map.of(RuleType.EDUCATION, new EligibilityAssessment.RuleResult(
                       EligibilityStatus.NEEDS_CONFIRMATION, "候选人学历尚未确认"),
                   RuleType.EXPERIENCE, new EligibilityAssessment.RuleResult(
                       EligibilityStatus.NEEDS_CONFIRMATION, "候选人工作经历尚未确认")))));

        assertThat(session.pendingConfirmations()).isEmpty();
    }

    /** 已经满足的条件不该出现在待确认清单里。 */
    @Test void aSatisfiedConditionIsNotPending() {
        var session = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID(),
            Map.of(RuleType.POLITICAL_AFFILIATION, new EligibilityAssessment.RuleResult(
                EligibilityStatus.ELIGIBLE, "公告未限制政治面貌")))));

        assertThat(session.pendingConfirmations()).isEmpty();
    }

    /** 同一个字段被多个岗位问到，只列一次——用户回答一次就够了。 */
    @Test void oneFieldAskedByTwoJobsIsListedOnce() {
        var rule = Map.of(RuleType.POLITICAL_AFFILIATION, new EligibilityAssessment.RuleResult(
            EligibilityStatus.NEEDS_CONFIRMATION, "候选人政治面貌尚未确认"));
        var session = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS,
            List.of(bundle(UUID.randomUUID(), rule), bundle(UUID.randomUUID(), rule)));

        assertThat(session.pendingConfirmations()).hasSize(1);
    }

    @Test void aSessionCannotBeReusedForAnotherCandidate() {
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));
        UUID other = UUID.randomUUID();
        profiles.others.put(other, candidate("profile-other", other));

        assertThatThrownBy(() -> service.remember(SESSION_ID, other, FILTERS, List.of(bundle(UUID.randomUUID()))))
            .isInstanceOf(AgentSessionService.SessionNotFoundException.class)
            .hasMessageContaining("session not found");
    }

    @Test void everySessionOperationKeepsTheCandidateOwnershipBoundary() {
        var before = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));
        UUID foreign = UUID.randomUUID();

        assertThat(service.profileVersionSeenBy(foreign, SESSION_ID)).isEmpty();
        assertThat(service.resolveOrdinal(foreign, SESSION_ID, 1).outcome()).isEqualTo(Outcome.NO_SESSION);
        assertThatThrownBy(() -> service.requireOwned(foreign, SESSION_ID))
            .isInstanceOf(AgentSessionService.SessionNotFoundException.class);
        assertThatThrownBy(() -> service.rememberDescription(foreign, SESSION_ID, List.of()))
            .isInstanceOf(AgentSessionService.SessionNotFoundException.class);
        assertThatThrownBy(() -> service.advanceProfileVersion(foreign, SESSION_ID, "profile-1", "profile-2"))
            .isInstanceOf(AgentSessionService.SessionNotFoundException.class);
        assertThat(sessions.stored.get(SESSION_ID)).isEqualTo(before);
    }

    @Test void aFocusedExplanationRefreshesItsQuestionsWithoutReplacingTheSavedOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var political = needs(RuleType.POLITICAL_AFFILIATION);
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS,
            List.of(bundle(first, political), bundle(second, political)));

        var focused = service.rememberDescription(CANDIDATE_ID, SESSION_ID, List.of(bundle(second, political)));

        assertThat(focused.lastJobIdsInOrder()).containsExactly(first, second);
        assertThat(focused.filters()).isEqualTo(FILTERS);
        assertThat(focused.profileVersion()).isEqualTo("profile-1");
        assertThat(focused.pendingConfirmations()).singleElement().satisfies(item -> {
            assertThat(item.factKey()).isEqualTo(CandidateFactKey.POLITICAL_AFFILIATION);
            assertThat(item.jobPostingId()).isEqualTo(second);
        });
        assertThat(service.resolveOrdinal(CANDIDATE_ID, SESSION_ID, 2).jobPostingId()).isEqualTo(second);
    }

    @Test void aFocusedExplanationCannotSilentlyReplaceAConcurrentSessionUpdate() {
        UUID job = UUID.randomUUID();
        var before = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(job)));
        sessions.rejectNextCas = true;
        assertThatThrownBy(() -> service.rememberDescription(CANDIDATE_ID, SESSION_ID,
            List.of(bundle(job, needs(RuleType.GENDER)))))
            .isInstanceOf(AgentSessionService.SessionChangedException.class);
        assertThat(sessions.stored.get(SESSION_ID)).isEqualTo(before);
    }

    @Test void aNewListingCannotSilentlyReplaceAConcurrentConfirmationUpdate() {
        var before = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));
        sessions.rejectNextCas = true;
        assertThatThrownBy(() -> service.remember(SESSION_ID, CANDIDATE_ID, FILTERS,
            List.of(bundle(UUID.randomUUID())))).isInstanceOf(AgentSessionService.SessionChangedException.class);
        assertThat(sessions.stored.get(SESSION_ID)).isEqualTo(before);
    }

    @Test void focusedQuestionsRejectStaleProfilesAndJobsOutsideTheSavedList() {
        UUID job = UUID.randomUUID();
        var before = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(job)));
        assertThatThrownBy(() -> service.rememberDescription(CANDIDATE_ID, SESSION_ID,
            List.of(bundle(UUID.randomUUID())))).isInstanceOf(AgentSessionService.SessionChangedException.class);
        profiles.stored = candidate("profile-2");
        assertThatThrownBy(() -> service.rememberDescription(CANDIDATE_ID, SESSION_ID,
            List.of(bundle(job)))).isInstanceOf(AgentSessionService.SessionChangedException.class);
        assertThat(sessions.stored.get(SESSION_ID)).isEqualTo(before);
    }

    @Test void advancementRemovesOnlyResolvedFieldsAndSupportsTheNextConfirmation() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(
            bundle(first, needs(RuleType.POLITICAL_AFFILIATION)), bundle(second, needs(RuleType.GENDER))));
        profiles.stored = candidate("profile-2").withPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER);
        confirm(CandidateFactKey.POLITICAL_AFFILIATION);

        assertThat(service.advanceProfileVersion(CANDIDATE_ID, SESSION_ID, "profile-1", "profile-2")).isTrue();
        assertThat(sessions.stored.get(SESSION_ID).pendingConfirmations())
            .extracting(AgentSession.PendingConfirmation::factKey).containsExactly(CandidateFactKey.GENDER);
        profiles.stored = candidate("profile-3").withPoliticalAffiliation(PoliticalAffiliation.CPC_MEMBER)
            .withGender(Gender.MALE);
        confirm(CandidateFactKey.GENDER);

        assertThat(service.advanceProfileVersion(CANDIDATE_ID, SESSION_ID, "profile-2", "profile-3")).isTrue();
        assertThat(sessions.stored.get(SESSION_ID).pendingConfirmations()).isEmpty();
        assertThat(sessions.stored.get(SESSION_ID).lastJobIdsInOrder()).containsExactly(first, second);
    }

    @Test void advancementDoesNotPromoteOverAnUnrelatedProfileEditOrFailedCas() {
        var before = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));
        profiles.stored = candidate("profile-unrelated");
        assertThat(service.advanceProfileVersion(CANDIDATE_ID, SESSION_ID, "profile-1", "profile-2")).isFalse();
        profiles.stored = candidate("profile-2");
        sessions.rejectNextCas = true;
        assertThat(service.advanceProfileVersion(CANDIDATE_ID, SESSION_ID, "profile-1", "profile-2")).isFalse();
        assertThat(sessions.stored.get(SESSION_ID)).isEqualTo(before);
    }

    @Test void unknownAndUndeclaredAnswersRemainPendingEvenWithAnErroneousConfirmedMarker() {
        UUID job = UUID.randomUUID();
        var decision = withGraduateClause(bundle(job, Map.of(
            RuleType.GENDER, needs(RuleType.GENDER).get(RuleType.GENDER),
            RuleType.POLITICAL_AFFILIATION, needs(RuleType.POLITICAL_AFFILIATION).get(RuleType.POLITICAL_AFFILIATION))),
            graduateClause(true, true, GraduateEligibilityRule.EvidenceState.CONFIRMED));
        for (var key : List.of(CandidateFactKey.GENDER, CandidateFactKey.POLITICAL_AFFILIATION,
            CandidateFactKey.EMPLOYER_SETTLEMENT_AT_APPLICATION, CandidateFactKey.SOCIAL_INSURANCE_AT_APPLICATION)) confirm(key);

        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(decision));
        profiles.stored = candidate("profile-2");
        service.advanceProfileVersion(CANDIDATE_ID, SESSION_ID, "profile-1", "profile-2");

        assertThat(sessions.stored.get(SESSION_ID).pendingConfirmations())
            .extracting(AgentSession.PendingConfirmation::factKey).containsExactlyInAnyOrder(
                CandidateFactKey.GENDER, CandidateFactKey.POLITICAL_AFFILIATION,
                CandidateFactKey.EMPLOYER_SETTLEMENT_AT_APPLICATION, CandidateFactKey.SOCIAL_INSURANCE_AT_APPLICATION);
    }

    @Test void applicationQuestionsRequireEachActualConfirmedSourceRestrictionIndependently() {
        UUID job = UUID.randomUUID();
        var empty = bundle(job);
        assertThat(service.pendingFor(CANDIDATE_ID, List.of(empty))).isEmpty();
        assertThat(service.pendingFor(CANDIDATE_ID, List.of(withGraduateClause(empty,
            graduateClause(true, true, GraduateEligibilityRule.EvidenceState.REVIEW_REQUIRED))))).isEmpty();
        assertThat(service.pendingFor(CANDIDATE_ID, List.of(withGraduateClause(empty,
            graduateClause(true, false, GraduateEligibilityRule.EvidenceState.CONFIRMED)))))
            .extracting(AgentSession.PendingConfirmation::factKey)
            .containsExactly(CandidateFactKey.EMPLOYER_SETTLEMENT_AT_APPLICATION);
        assertThat(service.pendingFor(CANDIDATE_ID, List.of(withGraduateClause(empty,
            graduateClause(false, true, GraduateEligibilityRule.EvidenceState.CONFIRMED)))))
            .extracting(AgentSession.PendingConfirmation::factKey)
            .containsExactly(CandidateFactKey.SOCIAL_INSURANCE_AT_APPLICATION);
    }

    @Test void unsupportedSourceRulesAreNotTurnedIntoPersonalQuestions() {
        var original = bundle(UUID.randomUUID());
        var wrongRule = bundle(original.decision().jobPostingId(), needs(RuleType.POLITICAL_AFFILIATION));
        var noSourceRestriction = new DecisionBundle(wrongRule.eligibility(), wrongRule.fit(), wrongRule.stability(),
            wrongRule.decision(), original.jobContext());
        assertThat(service.pendingFor(CANDIDATE_ID, List.of(noSourceRestriction))).isEmpty();
    }

    private static Map<RuleType, EligibilityAssessment.RuleResult> needs(RuleType rule) {
        return Map.of(rule, new EligibilityAssessment.RuleResult(EligibilityStatus.NEEDS_CONFIRMATION, "请确认候选人资料"));
    }

    private void confirm(CandidateFactKey key) {
        var values = new ArrayList<>(confirmations.stored);
        values.removeIf(item -> item.factKey() == key);
        values.add(new CandidateFacts.CandidateFactConfirmation(CANDIDATE_ID, key,
            CandidateFacts.CandidateFactStatus.CONFIRMED, CandidateFacts.fingerprint(profiles.stored, key),
            CandidateFacts.CandidateFactSource.USER_CONFIRMED, CLOCK.instant(), CLOCK.instant()));
        confirmations.stored = values;
    }

    private static GraduateEligibilityRule graduateClause(boolean employer, boolean insurance,
                                                          GraduateEligibilityRule.EvidenceState evidence) {
        return new GraduateEligibilityRule(2026, Set.of(2026), Set.of(GraduateEligibilityRule.CohortScope.CURRENT_YEAR),
            false, GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null,
            GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null, employer, insurance,
            "报名时未落实工作单位及社保限制", evidence);
    }

    private static DecisionBundle withGraduateClause(DecisionBundle bundle, GraduateEligibilityRule clause) {
        var context = bundle.jobContext();
        var old = context.event();
        var event = new RecruitmentEvent(old.id(), old.title(), old.recruitmentYear(), old.eventType(),
            old.publishedOn(), old.applicationStartsOn(), old.applicationEndsOn(), old.sourceUrl(),
            old.defaultEmploymentType(), old.evidenceIds(), null, null, null, null, null, null, null, null, null,
            List.of(), clause.rawText(), null, null, null, null, clause, null, null, null, null, null, null);
        return new DecisionBundle(bundle.eligibility(), bundle.fit(), bundle.stability(), bundle.decision(),
            new JobContext(context.job(), context.organization(), event, context.contentFingerprint(), context.active()));
    }

    // --- 固定装置 ---

    private static CandidateProfile candidate(String version) { return candidate(version, CANDIDATE_ID); }

    private static CandidateProfile candidate(String version, UUID id) {
        return new CandidateProfile(id, "候选人", PartialDate.month(1997, 4), EducationLevel.MASTER,
            Set.of("计算机科学"), 2027, 0, Set.of(), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), version);
    }

    private static DecisionBundle bundle(UUID jobId) { return bundle(jobId, Map.of()); }

    private static DecisionBundle bundle(UUID jobId, Map<RuleType, EligibilityAssessment.RuleResult> rules) {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant assessedAt = Instant.parse("2026-08-24T00:00:00Z");
        String fingerprint = "a".repeat(64);
        var evidence = List.of(UUID.randomUUID());
        var job = new JobPosting(jobId, eventId, organizationId, "J-1", "信息技术岗位",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, "杭州", 1,
            EducationLevel.BACHELOR, Set.of(), Set.of(), null, null, null, Set.of(), "",
            "https://example.gov.cn/job", evidence,
            null, null, null, null, null, null, null,
            rules.containsKey(RuleType.GENDER) ? "男性" : null,
            rules.containsKey(RuleType.POLITICAL_AFFILIATION) ? "限中共党员" : null,
            null, null, null, null, null);
        var organization = new Organization(organizationId, "杭州市信息中心", OrganizationType.PUBLIC_INSTITUTION,
            "市级", "浙江", "杭州", null, null, "https://example.gov.cn");
        var event = new RecruitmentEvent(eventId, "公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 9, 1), job.sourceUrl(),
            EmploymentType.ESTABLISHMENT, evidence);
        var eligibility = new EligibilityAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId,
            EligibilityStatus.NEEDS_CONFIRMATION, rules, evidence, "test", assessedAt, "profile-1", fingerprint);
        var fit = new FitAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, List.of(), "test",
            "profile-1", fingerprint, assessedAt);
        var stability = new StabilityAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, List.of(), "test",
            "profile-1", fingerprint, assessedAt);
        var decision = new DecisionAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, eligibility.id(), fit.id(),
            stability.id(), EligibilityStatus.NEEDS_CONFIRMATION, OpportunityTier.T1, RecommendationStatus.REVIEW,
            fit.score(), stability.score(), 0, "test", "profile-1", fingerprint, assessedAt);
        return new DecisionBundle(eligibility, fit, stability, decision,
            new JobContext(job, organization, event, fingerprint, true));
    }

    private static final class InMemorySessions implements AgentSessionPorts.Sessions {
        private final Map<UUID, AgentSession> stored = new HashMap<>();
        boolean rejectNextCas;
        public Optional<AgentSession> find(UUID sessionId) { return Optional.ofNullable(stored.get(sessionId)); }
        public AgentSession save(AgentSession session) { stored.put(session.sessionId(), session); return session; }
        public boolean compareAndSet(AgentSession expected, AgentSession replacement) {
            if (rejectNextCas) { rejectNextCas = false; return false; }
            return stored.replace(expected.sessionId(), expected, replacement);
        }
    }

    private static final class InMemoryConfirmations implements RepositoryPorts.CandidateFactConfirmations {
        private List<CandidateFacts.CandidateFactConfirmation> stored = List.of();
        public List<CandidateFacts.CandidateFactConfirmation> findByCandidateId(UUID id) { return stored; }
        public List<CandidateFacts.CandidateFactConfirmation> saveAll(List<CandidateFacts.CandidateFactConfirmation> values) {
            stored = List.copyOf(values); return stored;
        }
    }

    private static final class InMemoryProfiles implements RepositoryPorts.CandidateProfiles {
        private CandidateProfile stored;
        private final Map<UUID, CandidateProfile> others = new HashMap<>();
        InMemoryProfiles(CandidateProfile seed) { this.stored = seed; }
        public CandidateProfile save(CandidateProfile value) { stored = value; return value; }
        public Optional<CandidateProfile> findById(UUID id) {
            if (id.equals(CANDIDATE_ID)) return Optional.of(stored);
            return Optional.ofNullable(others.get(id));
        }
        public Optional<CandidateProfile> findByIdForUpdate(UUID id) { return findById(id); }
        public List<CandidateProfile> findAll() { return List.of(stored); }
        public void deleteById(UUID id) { }
    }
}
