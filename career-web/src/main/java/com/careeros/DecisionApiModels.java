package com.careeros;

import static com.careeros.application.DecisionPorts.DecisionBundle;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.DecisionExplanationService.Explanation;
import com.careeros.domain.AssessmentDimension;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class DecisionApiModels {
    static final String DISCLAIMER = "机会决策指数，不是录取概率";
    private DecisionApiModels() {}

    record DimensionResponse(AssessmentDimensionType type,int achievedPoints,int maximumPoints,AssessmentFactStatus factStatus,String reasonCode,String explanation,List<UUID> evidenceIds) {
        static DimensionResponse from(AssessmentDimension value) { return new DimensionResponse(value.type(),value.achievedPoints(),value.maximumPoints(),value.factStatus(),value.reasonCode(),value.explanation(),value.evidenceIds()); }
    }
    record ScoreResponse(int score,int coveragePercent,List<DimensionResponse> dimensions) {}
    record DecisionResponse(
        UUID decisionId,UUID candidateId,UUID jobId,String jobTitle,String organizationName,String location,
        EligibilityStatus eligibilityStatus,OpportunityTier tier,RecommendationStatus recommendationStatus,
        ScoreResponse fit,ScoreResponse stability,String explanation,List<String> warnings,List<UUID> evidenceIds,
        String evaluatorVersion,String profileVersion,String jobContentFingerprint,Instant assessedAt,String disclaimer
    ) {
        static DecisionResponse from(DecisionBundle bundle, Explanation explanation) {
            var decision=bundle.decision();
            return new DecisionResponse(decision.id(),decision.candidateProfileId(),decision.jobPostingId(),bundle.jobContext().job().title(),bundle.jobContext().organization().name(),bundle.jobContext().job().location(),decision.eligibilityStatus(),decision.tier(),decision.recommendationStatus(),
                new ScoreResponse(decision.fitScore(),bundle.fit().coveragePercent(),bundle.fit().dimensions().stream().map(DimensionResponse::from).toList()),
                new ScoreResponse(decision.stabilityScore(),bundle.stability().coveragePercent(),bundle.stability().dimensions().stream().map(DimensionResponse::from).toList()),
                explanation.text(),explanation.warnings(),explanation.evidenceIds(),decision.evaluatorVersion(),decision.profileVersion(),decision.jobContentFingerprint(),decision.assessedAt(),DISCLAIMER);
        }
    }
    record DecisionPage(List<DecisionResponse> items,int page,int size,long total,String disclaimer) { DecisionPage { items=List.copyOf(items); } }
}
