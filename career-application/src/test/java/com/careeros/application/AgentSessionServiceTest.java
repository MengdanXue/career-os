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
    private AgentSessionService service;

    @BeforeEach void setUp() {
        sessions = new InMemorySessions();
        profiles = new InMemoryProfiles(candidate("profile-1"));
        service = new AgentSessionService(sessions, profiles, CLOCK);
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

    // --- 归属：会话 ID 可猜，每个入口都要核对 ---

    /**
     * 报出别人的会话 ID 解析不出任何东西。
     *
     * <p>会话 ID 是随机 UUID，但"随机"不是访问控制。不核对归属的话，拿到一个别人的会话 ID
     * 就能把它的"第二个"解析成一个岗位，再顺着讲下去——讲的是别人那一轮的内容。
     * 而且要和"没有这轮会话"返回同一个结果，否则分得出来就等于可以枚举。
     */
    @Test void anotherCandidatesSessionResolvesNothingAndLooksLikeAMissingOne() {
        UUID other = UUID.randomUUID();
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));

        assertThat(service.resolveOrdinal(other, SESSION_ID, 1).outcome()).isEqualTo(Outcome.NO_SESSION);
        assertThat(service.resolveOrdinal(other, UUID.randomUUID(), 1).outcome()).isEqualTo(Outcome.NO_SESSION);
    }

    /** 也拿不到别人会话里的资料版本——那是用来过版本检查的，等于绕开检查。 */
    @Test void anotherCandidateCannotReadTheProfileVersionASessionSaw() {
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));

        assertThat(service.profileVersionSeenBy(UUID.randomUUID(), SESSION_ID)).isEmpty();
    }

    /** 更不能推进别人会话里的版本：那会让那个人的下一次确认静默跳过版本检查。 */
    @Test void anotherCandidateCannotAdvanceSomeoneElsesSessionVersion() {
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));

        assertThat(service.advanceProfileVersion(UUID.randomUUID(), SESSION_ID, "profile-1", "profile-2")).isFalse();
        assertThat(service.profileVersionSeenBy(CANDIDATE_ID, SESSION_ID)).contains("profile-1");
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

        service.advanceProfileVersion(CANDIDATE_ID, SESSION_ID, "profile-1", "profile-2");
        profiles.stored = candidate("profile-2");

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
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("another candidate");
    }

    // --- G5：列表更新与待答任务更新不能互斥 ---

    /**
     * 一轮既查出了新列表、又以追问收场时，两样都要存下来。
     *
     * <p>这是最容易出事的一种：用户问"宁波有什么合适的"，系统真的查了，一个都没查到，
     * 于是反问"要不要放宽范围"。这一轮<b>既有新范围</b>（宁波，空的），<b>也欠着一件事</b>。
     * 把"存新列表"和"存待答任务"写成二选一，这一轮就只会存前者——用户刷新之后
     * 页面上那句追问没了，他答一句"杭州"，系统不知道这是在回答什么。
     */
    @Test void aRoundThatSearchedAndStillAskedKeepsBothTheListingAndTheOpenTask() {
        service.rememberRun(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(), List.of());
        var session = service.rememberAsk(SESSION_ID, CANDIDATE_ID, "宁波有什么合适的", "要不要放宽范围？");

        assertThat(session.filters()).isEqualTo(FILTERS);
        assertThat(session.openTask()).isEqualTo("宁波有什么合适的");
        assertThat(session.pendingQuestion()).isEqualTo("要不要放宽范围？");
        assertThat(session.hasOpenTask()).isTrue();
    }

    /**
     * 存新列表不能顺手把还欠着的那件事清掉。
     *
     * <p>顺序反过来也一样：先记下追问，这一轮又查出了新列表，那件事还没办完。
     * 清掉的话，用户答完"杭州"之后系统仍然不知道他原本要办什么。
     */
    @Test void recordingANewListingDoesNotDiscardAnUnfinishedTask() {
        service.rememberAsk(SESSION_ID, CANDIDATE_ID, "宁波有什么合适的", "要不要放宽范围？");

        var session = service.rememberRun(SESSION_ID, CANDIDATE_ID, FILTERS,
            List.of(UUID.randomUUID()), List.of());

        assertThat(session.openTask()).isEqualTo("宁波有什么合适的");
        assertThat(session.pendingQuestion()).isEqualTo("要不要放宽范围？");
    }

    /** 那件事确实办完了才清掉待答状态——清早了，用户下一句会被当成新问题。 */
    @Test void onlyACompletedTaskClearsThePendingState() {
        service.rememberAsk(SESSION_ID, CANDIDATE_ID, "宁波有什么合适的", "要不要放宽范围？");

        var session = service.completeOpenTask(CANDIDATE_ID, SESSION_ID);

        assertThat(session.hasOpenTask()).isFalse();
        assertThat(session.pendingQuestion()).isNull();
    }

    /** 没有待答任务时不写一次空的：那只会把 updatedAt 推着走，看不出发生过什么。 */
    @Test void completingATaskThatWasNeverOpenedChangesNothing() {
        var opened = service.rememberRun(SESSION_ID, CANDIDATE_ID, FILTERS,
            List.of(UUID.randomUUID()), List.of());

        var session = service.completeOpenTask(CANDIDATE_ID, SESSION_ID);

        assertThat(session).isEqualTo(opened);
    }

    /** 清理别人的待答状态同样要挡住：会话 ID 是可猜的 UUID。 */
    @Test void anotherCandidateCannotClearSomeoneElsesPendingState() {
        service.rememberAsk(SESSION_ID, CANDIDATE_ID, "宁波有什么合适的", "要不要放宽范围？");
        UUID other = UUID.randomUUID();
        profiles.others.put(other, candidate("profile-other", other));

        assertThat(service.completeOpenTask(other, SESSION_ID)).isNull();
        assertThat(service.find(CANDIDATE_ID, SESSION_ID).orElseThrow().hasOpenTask()).isTrue();
    }

    /**
     * 连续追问不能把原来那件事换掉。
     *
     * <p>用户原本要办的是"有什么合适的"；系统问他哪个城市，他只答一句"余杭"。
     * 这一轮查出了新范围和新列表，但还要再问一句（"你指的是第几个"）——
     * 把"余杭"当成他要办的那件事存下去，刷新之后页面会摆出"为了：余杭"，
     * 而他从头到尾要办的是"有什么合适的"。"余杭"是他给的一个回答，不是一件事。
     */
    @Test void aSecondQuestionKeepsTheOriginalTaskAndOnlyUpdatesTheQuestion() {
        service.rememberAsk(SESSION_ID, CANDIDATE_ID, "有什么合适的", "你想看哪个城市或区县的岗位？");

        var session = service.rememberAsk(SESSION_ID, CANDIDATE_ID, "余杭", "你指的是上面列表里的第几个？");

        assertThat(session.openTask()).isEqualTo("有什么合适的");
        assertThat(session.pendingQuestion()).isEqualTo("你指的是上面列表里的第几个？");
    }

    /** 那件事办完之后再追问，才轮到新的任务接上——否则原任务会一直挂着。 */
    @Test void aQuestionAfterTheTaskIsDoneStartsANewTask() {
        service.rememberAsk(SESSION_ID, CANDIDATE_ID, "有什么合适的", "你想看哪个城市或区县的岗位？");
        service.completeOpenTask(CANDIDATE_ID, SESSION_ID);

        var session = service.rememberAsk(SESSION_ID, CANDIDATE_ID, "我关注的有动静吗", "这次读不到，要不要稍后再试？");

        assertThat(session.openTask()).isEqualTo("我关注的有动静吗");
    }

    /** 中途查出新列表也不影响：范围和顺序更新，原任务照旧是原任务。 */
    @Test void aNewListingBetweenTwoQuestionsDoesNotChangeTheOriginalTask() {
        service.rememberAsk(SESSION_ID, CANDIDATE_ID, "有什么合适的", "你想看哪个城市或区县的岗位？");
        UUID job = UUID.randomUUID();

        service.rememberRun(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(job), List.of());
        var session = service.rememberAsk(SESSION_ID, CANDIDATE_ID, "余杭", "你指的是上面列表里的第几个？");

        assertThat(session.openTask()).isEqualTo("有什么合适的");
        assertThat(session.lastJobIdsInOrder()).containsExactly(job);
        assertThat(session.filters()).isEqualTo(FILTERS);
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
            "https://example.gov.cn/job", evidence);
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
        public Optional<AgentSession> find(UUID sessionId) { return Optional.ofNullable(stored.get(sessionId)); }
        public AgentSession save(AgentSession session) { stored.put(session.sessionId(), session); return session; }
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
