package com.careeros.application.agent;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.DecisionPorts.DecisionBundle;
import com.careeros.application.DecisionPorts.JobContext;
import com.careeros.application.DecisionRankingService;
import com.careeros.application.ToolCallBudget;
import com.careeros.application.agent.AgentTooling.ToolCall;
import com.careeros.application.agent.AgentTooling.ToolContext;
import com.careeros.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 工具面的参数处理。
 *
 * <p>规划器会给出各种参数，包括认不出来的。<b>认不出来必须失败，而不是降级成"没筛选"。</b>
 * 之前这里的约定是"认不出来当作没筛选"，理由是"不猜"；但那实际上也是猜——
 * 猜用户不在乎这个条件。{@code tier=T9} 返回全量结果，读起来和"T9 就是这些"没有区别，
 * 用户问的范围被静默换掉，回答却看不出任何异常。失败观察是规划器能看见、能改、能重试的。
 *
 * <p>内部的逐岗评估都要扣执行器给的那份共享预算，所以每次调用都带一个 {@link ToolContext}。
 */
class ReadOnlyToolsTest {
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T15:00:00Z"), ZoneOffset.UTC);

    private DecisionRankingService.RankingPage page(List<DecisionBundle> items) {
        return new DecisionRankingService.RankingPage(items, 0, 5, items.size());
    }

    private static ToolContext context(String tool, String... keyValues) {
        return new ToolContext(CANDIDATE, ToolCall.of(tool, keyValues), ToolCallBudget.standard());
    }

    private static ToolContext context(ToolCallBudget budget, String tool, String... keyValues) {
        return new ToolContext(CANDIDATE, ToolCall.of(tool, keyValues), budget);
    }

    private AtomicReference<DecisionRankingService.RankingQuery> queryCaptor;

    private AgentTooling.ReadOnlyTool searchWith(List<DecisionBundle> items) {
        var captor = new AtomicReference<DecisionRankingService.RankingQuery>();
        this.queryCaptor = captor;
        return ReadOnlyTools.searchJobs((candidateId, query, now, budget) -> {
            captor.set(query);
            // 真实实现会在这里一岗一扣；测试里照做，才能验证扇出真的计进了预算。
            for (int index = 0; index < items.size(); index++) budget.tryConsume();
            return page(items);
        }, CLOCK);
    }

    // --- 参数认不出来就不筛选 ---

    @Test void aRecognisedFilterIsPassedThrough() {
        var tool = searchWith(List.of(bundle(EligibilityStatus.ELIGIBLE)));

        tool.invoke(context("search_jobs", "location", "杭州", "jobFamily", "DATA", "tier", "T1"));

        assertThat(queryCaptor.get().location()).isEqualTo("杭州");
        assertThat(queryCaptor.get().jobFamily()).isEqualTo(JobFamily.DATA);
        assertThat(queryCaptor.get().tier()).isEqualTo(OpportunityTier.T1);
    }

    /**
     * 认不出来的取值直接失败，不降级成"没筛选"。
     *
     * <p>降级返回的是全量结果，而它读起来和"这个分层就是这些"完全一样。用户问的范围
     * 被静默换掉，回答却看不出任何异常——这是最难发现的一类错。失败观察至少是可见的。
     */
    @Test void anUnrecognisedFilterFailsInsteadOfSilentlyBecomingNoFilter() {
        var tool = searchWith(List.of());

        var observation = tool.invoke(context("search_jobs", "tier", "T9"));

        assertThat(observation.ok()).isFalse();
        assertThat(observation.summary()).contains("tier").contains("T9");
        // 允许的取值要写出来，规划器才改得掉。
        assertThat(observation.summary()).contains("T1");
        // 查询根本没有发出去。
        assertThat(queryCaptor.get()).isNull();
    }

    @Test void anUnrecognisedJobFamilyFailsToo() {
        var tool = searchWith(List.of());

        var observation = tool.invoke(context("search_jobs", "jobFamily", "NOT_A_FAMILY"));

        assertThat(observation.ok()).isFalse();
        assertThat(queryCaptor.get()).isNull();
    }

    /**
     * 参数名打错也要失败。
     *
     * <p>忽略不认识的键，等于把那个筛选条件静默丢掉：{@code locaiton=杭州} 查的是全国。
     * {@code candidateId=...} 更是想越权——执行器本来就会忽略它，但"忽略"和"拒绝"
     * 给规划器的信号完全不同。
     */
    @Test void anUnknownArgumentNameFailsRatherThanBeingDropped() {
        var tool = searchWith(List.of());

        var typo = tool.invoke(context("search_jobs", "locaiton", "杭州"));
        var impersonation = tool.invoke(context("search_jobs", "candidateId", UUID.randomUUID().toString()));

        assertThat(typo.ok()).isFalse();
        assertThat(typo.summary()).contains("locaiton");
        assertThat(impersonation.ok()).isFalse();
        assertThat(queryCaptor.get()).isNull();
    }

    /** 超范围的 limit 直接拒绝。悄悄截断到上限，等于把用户问的范围换掉还不说。 */
    @Test void anOversizedLimitFailsInsteadOfBeingClamped() {
        var tool = searchWith(List.of());

        var observation = tool.invoke(context("search_jobs", "limit", "500"));

        assertThat(observation.ok()).isFalse();
        assertThat(observation.summary()).contains("limit").contains(String.valueOf(ReadOnlyTools.MAX_LIMIT));
        assertThat(queryCaptor.get()).isNull();
    }

    @Test void anUnparseableLimitFailsInsteadOfFallingBackToADefault() {
        var tool = searchWith(List.of());

        var observation = tool.invoke(context("search_jobs", "limit", "很多"));

        assertThat(observation.ok()).isFalse();
        assertThat(observation.summary()).contains("整数");
        assertThat(queryCaptor.get()).isNull();
    }

    /** 没给 limit 时才取默认值——不给和给错是两回事。 */
    @Test void anAbsentLimitTakesTheDefault() {
        var tool = searchWith(List.of());

        tool.invoke(context("search_jobs"));

        assertThat(queryCaptor.get().size()).isEqualTo(ReadOnlyTools.DEFAULT_LIMIT);
    }

    // --- 逐岗评估计入共享预算 ---

    /** 一次 search_jobs 背后是几十次评估。预算必须按真实扇出扣，否则形同虚设。 */
    @Test void theFanOutBehindASearchIsChargedToTheSharedBudget() {
        var budget = ToolCallBudget.of(20);
        var tool = searchWith(List.of(bundle(EligibilityStatus.ELIGIBLE), bundle(EligibilityStatus.ELIGIBLE)));

        tool.invoke(context(budget, "search_jobs"));

        assertThat(budget.spent()).isEqualTo(2);
    }

    /** 单岗位评估也是一次评估，照扣；预算见底时明说没评估，不返回一个空结论。 */
    @Test void aSingleJobAssessmentIsChargedAndRefusedOnceTheBudgetIsGone() {
        var assessed = new java.util.concurrent.atomic.AtomicInteger();
        var tool = ReadOnlyTools.jobFacts((candidateId, jobId, now) -> {
            assessed.incrementAndGet();
            return bundle(EligibilityStatus.ELIGIBLE);
        }, CLOCK);
        var budget = ToolCallBudget.of(1);

        var first = tool.invoke(context(budget, "job_facts", "jobId", JOB.toString()));
        var second = tool.invoke(context(budget, "job_facts", "jobId", JOB.toString()));

        assertThat(first.ok()).isTrue();
        assertThat(second.ok()).isFalse();
        assertThat(second.summary()).contains("预算");
        // 预算用完之后不该再打下游。
        assertThat(assessed).hasValue(1);
    }

    /**
     * 待确认清单扫不完时返回失败，不返回一份短清单。
     *
     * <p>"还差哪些确认"是个结论。少列几项读起来就是"这些都齐了"，用户据此以为可以直接投。
     */
    @Test void anIncompletePendingScanRefusesRatherThanReturningAShortList() {
        var tool = ReadOnlyTools.pendingConfirmations((candidateId, budget) ->
            new ReadOnlyTools.PendingList(List.of(), false));

        var observation = tool.invoke(context("pending_confirmations"));

        assertThat(observation.ok()).isFalse();
        assertThat(observation.summary()).contains("不完整");
    }

    /** 预算吃完只评估了一部分时，结果要标成不完整，不能读成"就这么多"。 */
    @Test void aTruncatedSearchSaysSoInsteadOfLookingComplete() {
        var tool = ReadOnlyTools.searchJobs((candidateId, query, now, budget) ->
            new DecisionRankingService.RankingPage(List.of(), 0, 5, 0, 7), CLOCK);

        var observation = tool.invoke(context("search_jobs"));

        assertThat(observation.<Boolean>value("complete", true)).isFalse();
        assertThat(observation.<Integer>value("notAssessed", 0)).isEqualTo(7);
        assertThat(observation.summary()).contains("不完整");
    }

    // --- 工具目录 ---

    /** 参数定义是契约：模型收到的目录和工具校验用的是同一份。 */
    @Test void everyDeclaredEnumParameterListsItsAllowedValues() {
        var tool = searchWith(List.of());

        var tier = tool.parameters().stream().filter(p -> p.name().equals("tier")).findFirst().orElseThrow();

        assertThat(tier.allowedValues()).contains("T1", "T2", "T3");
        assertThat(tool.parameters()).extracting(AgentTooling.ToolParameter::name)
            .containsExactly("location", "jobFamily", "tier", "limit");
    }

    // --- 观察里要有可分支的东西 ---

    /** 规划器要能据此选下一步，所以数量必须是结构化的，不能只有一句话。 */
    @Test void theObservationCarriesACountThePlannerCanBranchOn() {
        var withJobs = searchWith(List.of(bundle(EligibilityStatus.ELIGIBLE)));
        var withJobsResult = withJobs.invoke(context("search_jobs"));
        var none = searchWith(List.of());
        var noneResult = none.invoke(context("search_jobs"));

        assertThat(withJobsResult.<Integer>value("count", -1)).isEqualTo(1);
        assertThat(noneResult.<Integer>value("count", -1)).isZero();
        assertThat(noneResult.summary()).contains("没有岗位");
    }

    // --- 单岗位 ---

    /** jobId 不合法时返回失败观察，不抛异常——规划器要能看到并改走别的路。 */
    @Test void anInvalidJobIdBecomesAFailedObservation() {
        var tool = ReadOnlyTools.jobFacts((candidateId, jobId, now) -> bundle(EligibilityStatus.ELIGIBLE), CLOCK);

        var observation = tool.invoke(context("job_facts", "jobId", "not-a-uuid"));

        assertThat(observation.ok()).isFalse();
        assertThat(observation.summary()).contains("合法");
    }

    /** 未通过的硬条件要逐条出现在观察里——规划器据此决定要不要转去追问。 */
    @Test void unmetConditionsAreListedForTheJob() {
        var tool = ReadOnlyTools.jobFacts((candidateId, jobId, now) ->
            bundle(EligibilityStatus.NEEDS_CONFIRMATION), CLOCK);

        var observation = tool.invoke(context("job_facts", "jobId", JOB.toString()));

        assertThat(observation.<Integer>value("restrictionCount", 0)).isEqualTo(1);
        assertThat(observation.<List<String>>value("restrictions", List.of()))
            .singleElement().asString().contains("政治面貌");
    }

    // --- "第几个"只在上一轮的顺序里解析 ---

    /** 序号解析回上一轮那份列表的岗位，不重新排名。 */
    @Test void anOrdinalResolvesAgainstThePreviousListing() {
        var asked = new java.util.ArrayList<UUID>();
        var tool = ReadOnlyTools.jobFacts((candidateId, jobId, now) -> {
            asked.add(jobId);
            return bundle(EligibilityStatus.ELIGIBLE);
        }, CLOCK);
        UUID second = UUID.randomUUID();
        var session = new AgentTooling.SessionContext(UUID.randomUUID(), "杭州", null, null,
            List.of(new AgentTooling.JobRef(1, UUID.randomUUID(), "第一个", "机构", null, null),
                new AgentTooling.JobRef(2, second, "第二个", "机构", null, null)),
            List.of(), "profile-1");

        tool.invoke(new ToolContext(CANDIDATE, ToolCall.of("job_facts", "ordinal", "2"),
            ToolCallBudget.standard(), session));

        assertThat(asked).containsExactly(second);
    }

    /** 越界的序号要说清楚一共有几个，而不是给一个最接近的。 */
    @Test void anOrdinalPastTheEndSaysHowManyThereWere() {
        var tool = ReadOnlyTools.jobFacts((candidateId, jobId, now) -> {
            throw new AssertionError("不该评估任何岗位");
        }, CLOCK);
        var session = new AgentTooling.SessionContext(UUID.randomUUID(), "杭州", null, null,
            List.of(new AgentTooling.JobRef(1, UUID.randomUUID(), "唯一一个", "机构", null, null)),
            List.of(), "profile-1");

        var observation = tool.invoke(new ToolContext(CANDIDATE, ToolCall.of("job_facts", "ordinal", "5"),
            ToolCallBudget.standard(), session));

        assertThat(observation.ok()).isFalse();
        assertThat(observation.summary()).contains("第 5 个").contains("只有 1 个");
    }

    /** jobId 与 ordinal 同时给出无法确定指的是哪个岗位，直接拒绝。 */
    @Test void givingBothAJobIdAndAnOrdinalIsRefused() {
        var tool = ReadOnlyTools.jobFacts((candidateId, jobId, now) -> {
            throw new AssertionError("不该评估任何岗位");
        }, CLOCK);

        var observation = tool.invoke(context("job_facts", "jobId", JOB.toString(), "ordinal", "1"));

        assertThat(observation.ok()).isFalse();
        assertThat(observation.summary()).contains("只能给一个");
    }

    /** 两个都不给也要拒绝，不能默默取上一轮的第一个。 */
    @Test void givingNeitherIsRefused() {
        var tool = ReadOnlyTools.jobFacts((candidateId, jobId, now) -> {
            throw new AssertionError("不该评估任何岗位");
        }, CLOCK);

        var observation = tool.invoke(context("job_facts"));

        assertThat(observation.ok()).isFalse();
        assertThat(observation.summary()).contains("其中之一");
    }

    // --- 待确认 ---

    @Test void pendingConfirmationsCarryTheFieldAndTheAskingJob() {
        var tool = ReadOnlyTools.pendingConfirmations((candidateId, budget) ->
            new ReadOnlyTools.PendingList(List.of(new com.careeros.application.AgentSession.PendingConfirmation(
                CandidateFacts.CandidateFactKey.POLITICAL_AFFILIATION, "候选人政治面貌尚未确认", JOB)), true));

        var observation = tool.invoke(context("pending_confirmations"));

        assertThat(observation.<Integer>value("count", 0)).isEqualTo(1);
        assertThat(observation.summary()).contains("1 项");
    }

    @Test void anEmptyPendingListSaysSoPlainly() {
        var tool = ReadOnlyTools.pendingConfirmations((candidateId, budget) ->
            new ReadOnlyTools.PendingList(List.of(), true));

        var observation = tool.invoke(context("pending_confirmations"));

        assertThat(observation.<Integer>value("count", -1)).isZero();
        assertThat(observation.summary()).contains("没有待确认");
    }

    private static DecisionBundle bundle(EligibilityStatus status) {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant assessedAt = Instant.parse("2026-08-24T00:00:00Z");
        String fingerprint = "a".repeat(64);
        var evidence = List.of(UUID.randomUUID());
        var job = new JobPosting(JOB, eventId, organizationId, "J-1", "信息中心技术岗",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, "杭州", 1,
            EducationLevel.BACHELOR, Set.of(), Set.of(), null, null, null, Set.of(), "",
            "https://example.gov.cn/job", evidence);
        var organization = new Organization(organizationId, "杭州市信息中心", OrganizationType.PUBLIC_INSTITUTION,
            "市级", "浙江", "杭州", null, null, "https://example.gov.cn");
        var event = new RecruitmentEvent(eventId, "公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 9, 1), job.sourceUrl(),
            EmploymentType.ESTABLISHMENT, evidence);
        var rules = status == EligibilityStatus.ELIGIBLE ? Map.<RuleType, EligibilityAssessment.RuleResult>of()
            : Map.of(RuleType.POLITICAL_AFFILIATION,
                new EligibilityAssessment.RuleResult(status, "候选人政治面貌尚未确认"));
        var eligibility = new EligibilityAssessment(UUID.randomUUID(), CANDIDATE, JOB, status, rules,
            evidence, "v7", assessedAt, "profile-1", fingerprint);
        var fit = new FitAssessment(UUID.randomUUID(), CANDIDATE, JOB, List.of(), "v7",
            "profile-1", fingerprint, assessedAt);
        var stability = new StabilityAssessment(UUID.randomUUID(), CANDIDATE, JOB, List.of(), "v7",
            "profile-1", fingerprint, assessedAt);
        var decision = new DecisionAssessment(UUID.randomUUID(), CANDIDATE, JOB, eligibility.id(), fit.id(),
            stability.id(), status, OpportunityTier.T1, RecommendationStatus.REVIEW, fit.score(),
            stability.score(), 0, "v7", "profile-1", fingerprint, assessedAt);
        return new DecisionBundle(eligibility, fit, stability, decision,
            new JobContext(job, organization, event, fingerprint, true));
    }
}
