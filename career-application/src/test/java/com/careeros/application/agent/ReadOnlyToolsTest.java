package com.careeros.application.agent;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.DecisionPorts.DecisionBundle;
import com.careeros.application.DecisionPorts.JobContext;
import com.careeros.application.DecisionRankingService;
import com.careeros.application.agent.AgentTooling.ToolCall;
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
 * <p>规划器会给出各种参数，包括认不出来的。认不出来必须当作"没筛选"，不能猜——
 * 猜错会静默把用户问的范围换掉，而回答看起来完全正常。
 */
class ReadOnlyToolsTest {
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T15:00:00Z"), ZoneOffset.UTC);

    private DecisionRankingService.RankingPage page(List<DecisionBundle> items) {
        return new DecisionRankingService.RankingPage(items, 0, 5, items.size());
    }

    private AtomicReference<DecisionRankingService.RankingQuery> queryCaptor;

    private AgentTooling.ReadOnlyTool searchWith(List<DecisionBundle> items) {
        var captor = new AtomicReference<DecisionRankingService.RankingQuery>();
        this.queryCaptor = captor;
        return ReadOnlyTools.searchJobs((candidateId, query, now) -> {
            captor.set(query);
            return page(items);
        }, CLOCK);
    }

    // --- 参数认不出来就不筛选 ---

    @Test void aRecognisedFilterIsPassedThrough() {
        var tool = searchWith(List.of(bundle(EligibilityStatus.ELIGIBLE)));

        tool.invoke(CANDIDATE, ToolCall.of("search_jobs", "location", "杭州", "jobFamily", "DATA", "tier", "T1"));

        assertThat(queryCaptor.get().location()).isEqualTo("杭州");
        assertThat(queryCaptor.get().jobFamily()).isEqualTo(JobFamily.DATA);
        assertThat(queryCaptor.get().tier()).isEqualTo(OpportunityTier.T1);
    }

    /**
     * 认不出来的取值当作"没筛选"。猜一个最接近的会静默换掉用户问的范围，
     * 而回答读起来完全正常——那是最难发现的一类错。
     */
    @Test void anUnrecognisedFilterBecomesNoFilterRatherThanAGuess() {
        var tool = searchWith(List.of());

        tool.invoke(CANDIDATE, ToolCall.of("search_jobs", "jobFamily", "NOT_A_FAMILY", "tier", "T9"));

        assertThat(queryCaptor.get().jobFamily()).isNull();
        assertThat(queryCaptor.get().tier()).isNull();
    }

    /** 规划器报再大的 limit 也要截断，否则它能一次把预算外的负载放大。 */
    @Test void anOversizedLimitIsClamped() {
        var tool = searchWith(List.of());

        tool.invoke(CANDIDATE, ToolCall.of("search_jobs", "limit", "500"));

        assertThat(queryCaptor.get().size()).isEqualTo(ReadOnlyTools.MAX_LIMIT);
    }

    @Test void anUnparseableLimitFallsBackToTheDefault() {
        var tool = searchWith(List.of());

        tool.invoke(CANDIDATE, ToolCall.of("search_jobs", "limit", "很多"));

        assertThat(queryCaptor.get().size()).isEqualTo(5);
    }

    // --- 观察里要有可分支的东西 ---

    /** 规划器要能据此选下一步，所以数量必须是结构化的，不能只有一句话。 */
    @Test void theObservationCarriesACountThePlannerCanBranchOn() {
        var withJobs = searchWith(List.of(bundle(EligibilityStatus.ELIGIBLE)));
        var withJobsResult = withJobs.invoke(CANDIDATE, ToolCall.of("search_jobs"));
        var none = searchWith(List.of());
        var noneResult = none.invoke(CANDIDATE, ToolCall.of("search_jobs"));

        assertThat(withJobsResult.<Integer>value("count", -1)).isEqualTo(1);
        assertThat(noneResult.<Integer>value("count", -1)).isZero();
        assertThat(noneResult.summary()).contains("没有岗位");
    }

    // --- 单岗位 ---

    /** jobId 不合法时返回失败观察，不抛异常——规划器要能看到并改走别的路。 */
    @Test void anInvalidJobIdBecomesAFailedObservation() {
        var tool = ReadOnlyTools.jobFacts((candidateId, jobId, now) -> bundle(EligibilityStatus.ELIGIBLE), CLOCK);

        var observation = tool.invoke(CANDIDATE, ToolCall.of("job_facts", "jobId", "not-a-uuid"));

        assertThat(observation.ok()).isFalse();
        assertThat(observation.summary()).contains("合法");
    }

    /** 未通过的硬条件要逐条出现在观察里——规划器据此决定要不要转去追问。 */
    @Test void unmetConditionsAreListedForTheJob() {
        var tool = ReadOnlyTools.jobFacts((candidateId, jobId, now) ->
            bundle(EligibilityStatus.NEEDS_CONFIRMATION), CLOCK);

        var observation = tool.invoke(CANDIDATE, ToolCall.of("job_facts", "jobId", JOB.toString()));

        assertThat(observation.<Integer>value("restrictionCount", 0)).isEqualTo(1);
        assertThat(observation.<List<String>>value("restrictions", List.of()))
            .singleElement().asString().contains("政治面貌");
    }

    // --- 待确认 ---

    @Test void pendingConfirmationsCarryTheFieldAndTheAskingJob() {
        var tool = ReadOnlyTools.pendingConfirmations(candidateId -> List.of(
            new com.careeros.application.AgentSession.PendingConfirmation(
                CandidateFacts.CandidateFactKey.POLITICAL_AFFILIATION, "候选人政治面貌尚未确认", JOB)));

        var observation = tool.invoke(CANDIDATE, ToolCall.of("pending_confirmations"));

        assertThat(observation.<Integer>value("count", 0)).isEqualTo(1);
        assertThat(observation.summary()).contains("1 项");
    }

    @Test void anEmptyPendingListSaysSoPlainly() {
        var tool = ReadOnlyTools.pendingConfirmations(candidateId -> List.of());

        var observation = tool.invoke(CANDIDATE, ToolCall.of("pending_confirmations"));

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
