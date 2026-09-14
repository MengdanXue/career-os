package com.careeros.application;

import static com.careeros.application.DecisionPorts.DecisionBundle;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.domain.AnswerBlock;
import com.careeros.domain.AnswerFact;
import com.careeros.domain.EligibilityEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AgentQueryService {
    private final RankingPort rankings;
    private final Optional<DecisionPorts.DecisionAssessor> assessor;

    public AgentQueryService(RankingPort rankings,DecisionExplanationService explanations,Optional<AgentPhraser> phraser) {
        this(rankings,explanations,phraser,Optional.empty());
    }

    public AgentQueryService(RankingPort rankings,DecisionExplanationService explanations,Optional<AgentPhraser> phraser,Optional<DecisionPorts.DecisionAssessor> assessor) {
        // Stage 1 exposes only typed, deterministically rendered facts. The legacy
        // phraser parameter remains source-compatible but is deliberately never called.
        this.rankings=rankings; this.assessor=assessor;
    }

    /**
     * 回答"第 N 个怎么样"——针对用户指着的那一个岗位。
     *
     * <p>岗位是会话按记下的顺序解析出来的，这里不重新排名：重新排出来的第 N 个可能是
     * 另一个岗位，而用户看不出系统换了个对象在回答。
     */
    public AgentResponse describe(UUID candidateId,UUID jobPostingId,String question,int limit,Instant now) {
        validateRequest(question,limit);
        if (assessor.isEmpty()) {
            return cannotResolve(question,"当前无法单独评估这个岗位，请重新查询列表。",limit);
        }
        var bundle=assessor.get().assess(candidateId,jobPostingId,now);
        var decisions=List.of(bundle);
        AnswerBlock block=block(decisions);
        var filters=new AgentSession.SessionFilters(null,null,null,limit);
        String deterministic=block.render();
        return new AgentResponse(question,deterministic,decisions,false,false,block.disclaimer(),List.of(),filters);
    }

    /**
     * 序号指不回任何岗位时的回答。
     *
     * <p>这里刻意不回落去重新排名一次。用户指的是他屏幕上那一份列表；那份列表已经不作数了，
     * 就说它不作数，而不是拿一份新排的列表冒充它。
     */
    public AgentResponse cannotResolve(String question,String reason,int limit) {
        return new AgentResponse(question,reason,List.of(),false,false,
            AnswerBlock.DEFAULT_DISCLAIMER,List.of(),new AgentSession.SessionFilters(null,null,null,limit));
    }

    /**
     * 核心答案由 {@link AnswerBlock} 从类型化事实确定性渲染，模型不参与。
     *
     * <p>首阶段不生成或展示自由模型叙述；模型开关、API key 或旧 phraser bean 都不能
     * 改变这个出口。限制条件与单位始终来自同一份确定性事实块。
     */
    public AgentResponse query(UUID candidateId,String question,int limit,Instant now) {
        validateRequest(question,limit);
        var filters=new AgentSession.SessionFilters(tier(question),location(question),jobFamily(question),limit);
        var query=new DecisionRankingService.RankingQuery(filters.tier(),filters.location(),filters.jobFamily(),0,limit,false);
        var page=rankings.rank(candidateId,query,now);
        AnswerBlock block=block(page.items());
        String deterministic=block.render();
        return new AgentResponse(question,deterministic,page.items(),false,false,block.disclaimer(),List.of(),filters);
    }

    private static void validateRequest(String question,int limit) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question is required");
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between 1 and 20");
    }

    /**
     * 把决策结果转成类型化事实。每条事实绑定岗位、评估版本、字段、值与单位——
     * 光看数值分不出 72 分适配和 72% 覆盖率，也分不出它属于哪个岗位、来自哪次评估。
     */
    AnswerBlock block(List<DecisionBundle> decisions) {
        var jobs=new java.util.ArrayList<AnswerBlock.JobFacts>();
        for (DecisionBundle value : decisions) {
            var decision=value.decision();
            UUID jobId=decision.jobPostingId();
            String version=decision.evaluatorVersion();
            var facts=new java.util.ArrayList<AnswerFact>();
            var eligibility=value.eligibility();
            facts.add(new AnswerFact(jobId,version,AnswerFact.FactField.ELIGIBILITY,
                decision.eligibilityStatus().name(),AnswerFact.FactUnit.ENUM,
                value.jobContext().event().applicationEndsOn(),
                eligibility == null ? List.of() : eligibility.evidenceIds()));
            facts.add(new AnswerFact(jobId,version,AnswerFact.FactField.TIER,
                decision.tier().name(),AnswerFact.FactUnit.ENUM,null,List.of()));
            facts.add(new AnswerFact(jobId,version,AnswerFact.FactField.RECOMMENDATION,
                decision.recommendationStatus().name(),AnswerFact.FactUnit.ENUM,null,List.of()));
            facts.add(new AnswerFact(jobId,version,AnswerFact.FactField.FIT_SCORE,
                String.valueOf(decision.fitScore()),AnswerFact.FactUnit.POINTS,null,List.of()));
            facts.add(new AnswerFact(jobId,version,AnswerFact.FactField.STABILITY_SCORE,
                String.valueOf(decision.stabilityScore()),AnswerFact.FactUnit.POINTS,null,List.of()));
            facts.add(new AnswerFact(jobId,version,AnswerFact.FactField.EVIDENCE_COVERAGE,
                String.valueOf(decision.coveragePercent()),AnswerFact.FactUnit.PERCENT,null,List.of()));
            // 未通过的硬条件按限制条件逐条列出，一条不省——它们最容易被"读起来更肯定"的改写吃掉。
            if (eligibility != null) {
                eligibility.ruleResults().forEach((rule,result)->{
                    if (result.status()==EligibilityStatus.ELIGIBLE) return;
                    facts.add(new AnswerFact(jobId,version,AnswerFact.FactField.RESTRICTION,
                        result.explanation(),AnswerFact.FactUnit.TEXT,null,result.evidenceIds()));
                });
            }
            jobs.add(new AnswerBlock.JobFacts(jobId,value.jobContext().job().title(),
                value.jobContext().organization().name(),List.copyOf(facts)));
        }
        return AnswerBlock.of(List.copyOf(jobs),EligibilityEvaluator.VERSION);
    }

    private static OpportunityTier tier(String q) { return q.toUpperCase().contains("T1") || q.contains("编制") ? OpportunityTier.T1 : q.toUpperCase().contains("T2") ? OpportunityTier.T2 : q.toUpperCase().contains("T3") ? OpportunityTier.T3 : null; }
    private static String location(String q) { if (q.contains("杭州")) return "杭州"; if (q.contains("浙江")) return "浙江"; return null; }
    private static JobFamily jobFamily(String q) {
        String lower=q.toLowerCase();
        if (q.contains("信息化") || q.contains("信息中心")) return JobFamily.INFORMATION_SYSTEMS;
        if (q.contains("人工智能") || lower.contains("ai")) return JobFamily.AI;
        if (q.contains("数据")) return JobFamily.DATA;
        if (q.contains("运维")) return JobFamily.IT_OPERATIONS;
        if (q.contains("软件") || lower.contains("java")) return JobFamily.SOFTWARE;
        return null;
    }

    @FunctionalInterface public interface RankingPort { DecisionRankingService.RankingPage rank(UUID candidateId,DecisionRankingService.RankingQuery query,Instant now); }
    @FunctionalInterface public interface AgentPhraser { String phrase(PhrasingContext context); }
    public record PhrasingContext(String question,String deterministicAnswer,List<DecisionSummary> decisions) { public PhrasingContext { decisions=List.copyOf(decisions); } }
    public record DecisionSummary(UUID jobId,String title,String organization,EligibilityStatus eligibilityStatus,OpportunityTier tier,int fitScore,int stabilityScore,int coveragePercent) {}
    /**
     * @param modelPhrased  模型叙述是否被采用
     * @param fallbackUsed  是否整块回落到确定性事实块
     * @param violations    回落原因，用于留痕——没人看得见的拦截等于没拦截
     * @param filters       这一轮实际使用的筛选条件。返回它是为了让会话能记下"用户看到的是哪一份列表"，
     *                      下一句"还有别的吗"才落在同一个范围里。
     */
    public record AgentResponse(String question,String answer,List<DecisionBundle> decisions,boolean modelPhrased,boolean fallbackUsed,String disclaimer,List<String> violations,AgentSession.SessionFilters filters) {
        public AgentResponse { decisions=List.copyOf(decisions); violations=violations==null?List.of():List.copyOf(violations); }
    }
}
