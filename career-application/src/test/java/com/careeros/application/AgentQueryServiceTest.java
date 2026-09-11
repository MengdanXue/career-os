package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentQueryServiceTest {
    private static final UUID CANDIDATE_ID=UUID.randomUUID();
    private static final Instant NOW=Instant.parse("2026-08-20T12:00:00Z");

    @Test void naturalLanguageBecomesBoundedToolFiltersAndWorksWithoutModel() {
        var captured=new AtomicReference<DecisionRankingService.RankingQuery>();
        AgentQueryService.RankingPort port=(candidateId,query,now)->{captured.set(query);return new DecisionRankingService.RankingPage(List.of(bundle()),0,5,1);};

        var result=new AgentQueryService(port,new DecisionExplanationService(),Optional.empty())
            .query(CANDIDATE_ID,"帮我找杭州 T1 信息化岗位",5,NOW);

        assertThat(captured.get().location()).isEqualTo("杭州");
        assertThat(captured.get().tier()).isEqualTo(OpportunityTier.T1);
        assertThat(captured.get().jobFamily()).isEqualTo(JobFamily.INFORMATION_SYSTEMS);
        assertThat(result.answer()).contains("信息中心技术岗").contains("机会分层：T1").contains("岗位适配：70 分");
        assertThat(result.modelPhrased()).isFalse();
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test void modelFailureFallsBackAndContradictoryPhrasingCannotChangeStructuredDecision() {
        AgentQueryService.RankingPort port=(candidateId,query,now)->new DecisionRankingService.RankingPage(List.of(bundle()),0,5,1);
        var failing=new AgentQueryService(port,new DecisionExplanationService(),Optional.of(context->{throw new IllegalStateException("model down");}));
        var fallback=failing.query(CANDIDATE_ID,"杭州稳定岗位",5,NOW);

        assertThat(fallback.fallbackUsed()).isTrue();
        assertThat(fallback.decisions().getFirst().decision().tier()).isEqualTo(OpportunityTier.T1);

        // 此前这句矛盾表述会被原样展示，只有底层结构化数据还是对的——用户看到的仍是"T3岗位、匹配0分"。
        // 现在它写入了判定词与数值，整段叙述被拒，展示的只有确定性事实块。
        var contradictory=new AgentQueryService(port,new DecisionExplanationService(),Optional.of(context->"这是T3岗位，匹配0分"))
            .query(CANDIDATE_ID,"杭州稳定岗位",5,NOW);
        assertThat(contradictory.answer()).doesNotContain("T3岗位");
        assertThat(contradictory.fallbackUsed()).isTrue();
        assertThat(contradictory.violations()).isNotEmpty();
        assertThat(contradictory.answer()).contains("机会分层：T1");
        assertThat(contradictory.decisions().getFirst().decision().tier()).isEqualTo(OpportunityTier.T1);
        assertThat(contradictory.decisions().getFirst().decision().fitScore()).isEqualTo(70);
    }

    /**
     * 决策快照的版本串是复合的：{@code decision-v3-...|eligibility-hard-verdict-v7@cutoff=2026-09-01}。
     * 资格参考日逐岗不同，所以"是否过期"必须比对代号而不是整串——整串相等比较会把每一条
     * 事实都标成过期，而领域层的单元测试用的是干净版本串，根本照不到这个接线问题。
     */
    @Test void aDecisionCarryingTheRealCompositeVersionIsNotReportedStale() {
        AgentQueryService.RankingPort port=(candidateId,query,now)->new DecisionRankingService.RankingPage(List.of(bundle()),0,5,1);

        var result=new AgentQueryService(port,new DecisionExplanationService(),Optional.empty())
            .query(CANDIDATE_ID,"杭州稳定岗位",5,NOW);

        assertThat(result.answer()).doesNotContain("已过期");
    }

    /** 资格评估器升代后，旧快照不能当作当前结论呈现。 */
    @Test void aDecisionFromAnEarlierEligibilityGenerationIsReportedStale() {
        AgentQueryService.RankingPort port=(candidateId,query,now)->new DecisionRankingService.RankingPage(
            List.of(bundle(DecisionIntelligenceService.VERSION+"|eligibility-hard-verdict-v5@cutoff=2026-09-01")),0,5,1);

        var result=new AgentQueryService(port,new DecisionExplanationService(),Optional.empty())
            .query(CANDIDATE_ID,"杭州稳定岗位",5,NOW);

        assertThat(result.answer()).contains("基于已过期的评估版本");
    }

    /**
     * 未通过的硬条件必须逐条出现在事实块里，并且在叙述被拒时照样保留。
     * 逐句删减最危险的失败方式正是删掉这一句——回答会读起来更肯定，而不是更谨慎。
     */
    @Test void unmetHardConditionsSurviveARejectedNarrative() {
        var restricted=bundle(DecisionIntelligenceService.VERSION+"|"+EligibilityEvaluator.VERSION+"@cutoff=2026-09-01",
            Map.of(RuleType.POLITICAL_AFFILIATION,
                new EligibilityAssessment.RuleResult(EligibilityStatus.NEEDS_CONFIRMATION,"公告限中共党员，你的政治面貌待确认",List.of())));
        AgentQueryService.RankingPort port=(candidateId,query,now)->new DecisionRankingService.RankingPage(List.of(restricted),0,5,1);

        var result=new AgentQueryService(port,new DecisionExplanationService(),
            Optional.of(context->"这个岗位你是可报的，放心投。")).query(CANDIDATE_ID,"杭州稳定岗位",5,NOW);

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.answer()).doesNotContain("放心投");
        assertThat(result.answer()).contains("限制条件：公告限中共党员，你的政治面貌待确认");
    }

    /** 覆盖率按百分比、适配分按分渲染，两者单位不同，覆盖率不会被读成录取概率。 */
    @Test void coverageAndFitKeepTheirOwnUnits() {
        AgentQueryService.RankingPort port=(candidateId,query,now)->new DecisionRankingService.RankingPage(List.of(bundle()),0,5,1);

        var answer=new AgentQueryService(port,new DecisionExplanationService(),Optional.empty())
            .query(CANDIDATE_ID,"杭州稳定岗位",5,NOW).answer();

        assertThat(answer).contains("岗位适配：70 分").contains("证据覆盖率：100%").contains("不是录取概率");
    }

    /** 默认夹具带真实的复合版本串，否则每个用例都会莫名其妙地渲染出"已过期"。 */
    private static DecisionBundle bundle() {
        return bundle(DecisionIntelligenceService.VERSION+"|"+EligibilityEvaluator.VERSION+"@cutoff=2026-09-01");
    }

    private static DecisionBundle bundle(String evaluatorVersion) { return bundle(evaluatorVersion,Map.of()); }

    private static DecisionBundle bundle(String evaluatorVersion,Map<RuleType,EligibilityAssessment.RuleResult> ruleResults) {
        UUID jobId=UUID.randomUUID(),eventId=UUID.randomUUID(),organizationId=UUID.randomUUID(),evidenceId=UUID.randomUUID();
        UUID eligibilityId=UUID.randomUUID(),fitId=UUID.randomUUID(),stabilityId=UUID.randomUUID();
        var job=new JobPosting(jobId,eventId,organizationId,"A","信息中心技术岗",JobFamily.INFORMATION_SYSTEMS,EmploymentType.ESTABLISHMENT,"杭州",1,EducationLevel.BACHELOR,Set.of(),Set.of(),null,null,null,Set.of(),"Java","https://example.gov.cn",List.of(evidenceId));
        var org=new Organization(organizationId,"杭州市信息中心",OrganizationType.PUBLIC_INSTITUTION,null,"浙江","杭州",null,null,null);
        var event=new RecruitmentEvent(eventId,"招聘",2026,EventType.PUBLIC_INSTITUTION,LocalDate.of(2026,8,1),null,LocalDate.of(2026,9,1),"https://example.gov.cn",EmploymentType.ESTABLISHMENT,List.of(evidenceId));
        var eligibility=new EligibilityAssessment(eligibilityId,CANDIDATE_ID,jobId,EligibilityStatus.ELIGIBLE,ruleResults,List.of(evidenceId),EligibilityEvaluator.VERSION,NOW,"v2","a".repeat(64));
        var fit=new FitAssessment(fitId,CANDIDATE_ID,jobId,List.of(new AssessmentDimension(AssessmentDimensionType.MAJOR_FIT,70,100,AssessmentFactStatus.EXPLICIT,"FIXTURE","fixture",List.of(evidenceId))),FitEvaluator.VERSION,"v2","a".repeat(64),NOW);
        var stability=new StabilityAssessment(stabilityId,CANDIDATE_ID,jobId,List.of(new AssessmentDimension(AssessmentDimensionType.EMPLOYMENT_SECURITY,40,100,AssessmentFactStatus.EXPLICIT,"FIXTURE","fixture",List.of(evidenceId))),StabilityEvaluator.VERSION,"v2","a".repeat(64),NOW);
        var decision=new DecisionAssessment(UUID.randomUUID(),CANDIDATE_ID,jobId,eligibilityId,fitId,stabilityId,EligibilityStatus.ELIGIBLE,OpportunityTier.T1,RecommendationStatus.RECOMMENDED,70,40,100,evaluatorVersion,"v2","a".repeat(64),NOW);
        return new DecisionBundle(eligibility,fit,stability,decision,new JobContext(job,org,event,"a".repeat(64),true));
    }
}
