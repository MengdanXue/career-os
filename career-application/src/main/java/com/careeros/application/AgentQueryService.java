package com.careeros.application;

import static com.careeros.application.DecisionPorts.DecisionBundle;
import static com.careeros.domain.DomainEnums.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AgentQueryService {
    private final RankingPort rankings;
    private final DecisionExplanationService explanations;
    private final Optional<AgentPhraser> phraser;

    public AgentQueryService(RankingPort rankings,DecisionExplanationService explanations,Optional<AgentPhraser> phraser) {
        this.rankings=rankings; this.explanations=explanations; this.phraser=phraser;
    }

    public AgentResponse query(UUID candidateId,String question,int limit,Instant now) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question is required");
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between 1 and 20");
        var query=new DecisionRankingService.RankingQuery(tier(question),location(question),jobFamily(question),0,limit,false);
        var page=rankings.rank(candidateId,query,now);
        String deterministic=deterministicAnswer(page.items());
        if (phraser.isEmpty()) return new AgentResponse(question,deterministic,page.items(),false,false,"机会决策指数，不是录取概率");
        try {
            String phrased=phraser.get().phrase(new PhrasingContext(question,deterministic,summaries(page.items())));
            if (phrased == null || phrased.isBlank()) throw new IllegalStateException("model returned an empty answer");
            return new AgentResponse(question,phrased,page.items(),true,false,"机会决策指数，不是录取概率");
        } catch (RuntimeException failure) {
            return new AgentResponse(question,deterministic,page.items(),false,true,"机会决策指数，不是录取概率");
        }
    }

    private String deterministicAnswer(List<DecisionBundle> decisions) {
        if (decisions.isEmpty()) return "当前没有满足条件且通过硬性资格门槛的岗位。";
        var text=new StringBuilder("根据硬性资格、岗位匹配和稳定性证据，当前建议关注：");
        for (int index=0;index<decisions.size();index++) {
            var value=decisions.get(index); var decision=value.decision();
            text.append("\n").append(index+1).append(". ").append(value.jobContext().job().title())
                .append("（").append(value.jobContext().organization().name()).append("）：")
                .append(decision.tier()).append("，匹配度 ").append(decision.fitScore())
                .append("，稳定性 ").append(decision.stabilityScore()).append("，")
                .append(explanations.explain(value).text());
        }
        return text.toString();
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
    public record AgentResponse(String question,String answer,List<DecisionBundle> decisions,boolean modelPhrased,boolean fallbackUsed,String disclaimer) { public AgentResponse { decisions=List.copyOf(decisions); } }
}
