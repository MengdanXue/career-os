package com.careeros.application;

import static com.careeros.application.DecisionPorts.DecisionBundle;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.domain.AnswerBlock;
import com.careeros.domain.AnswerFact;
import com.careeros.domain.AnswerNarrativeValidator;
import com.careeros.domain.EligibilityEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AgentQueryService {
    private static final AnswerNarrativeValidator VALIDATOR=new AnswerNarrativeValidator();

    private final RankingPort rankings;
    private final DecisionExplanationService explanations;
    private final Optional<AgentPhraser> phraser;

    public AgentQueryService(RankingPort rankings,DecisionExplanationService explanations,Optional<AgentPhraser> phraser) {
        this.rankings=rankings; this.explanations=explanations; this.phraser=phraser;
    }

    /**
     * 核心答案由 {@link AnswerBlock} 从类型化事实确定性渲染，模型不参与。
     *
     * <p>此前的做法是把整段确定性答案交给模型润色、再用模型的返回值**替换**它。那样模型
     * 可以静默改掉分数、把"条件可报"说成"可报"、或者删掉一条限制条件，而唯一的约束只是
     * prompt 里一句请求。现在模型只写连接性叙述，写进事实即被拒，整段不展示。
     */
    public AgentResponse query(UUID candidateId,String question,int limit,Instant now) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question is required");
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between 1 and 20");
        var query=new DecisionRankingService.RankingQuery(tier(question),location(question),jobFamily(question),0,limit,false);
        var page=rankings.rank(candidateId,query,now);
        AnswerBlock block=block(page.items());
        String deterministic=block.render();
        if (phraser.isEmpty()) {
            return new AgentResponse(question,deterministic,page.items(),false,false,block.disclaimer(),List.of());
        }
        String narrative;
        try {
            narrative=phraser.get().phrase(new PhrasingContext(question,deterministic,summaries(page.items())));
        } catch (RuntimeException failure) {
            return new AgentResponse(question,deterministic,page.items(),false,true,block.disclaimer(),
                List.of("模型调用失败："+failure.getClass().getSimpleName()));
        }
        var composed=VALIDATOR.compose(narrative,block);
        return new AgentResponse(question,composed.answer(),page.items(),composed.narrativeUsed(),
            !composed.narrativeUsed(),block.disclaimer(),composed.violations());
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

    private static List<DecisionSummary> summaries(List<DecisionBundle> values) { return values.stream().map(value->new DecisionSummary(value.decision().jobPostingId(),value.jobContext().job().title(),value.jobContext().organization().name(),value.decision().eligibilityStatus(),value.decision().tier(),value.decision().fitScore(),value.decision().stabilityScore(),value.decision().coveragePercent())).toList(); }
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
     */
    public record AgentResponse(String question,String answer,List<DecisionBundle> decisions,boolean modelPhrased,boolean fallbackUsed,String disclaimer,List<String> violations) {
        public AgentResponse { decisions=List.copyOf(decisions); violations=violations==null?List.of():List.copyOf(violations); }
    }
}
