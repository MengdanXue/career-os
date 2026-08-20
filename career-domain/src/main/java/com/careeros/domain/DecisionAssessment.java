package com.careeros.domain;

import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.OpportunityTier;
import com.careeros.domain.DomainEnums.RecommendationStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DecisionAssessment(
    UUID id, UUID candidateProfileId, UUID jobPostingId, UUID eligibilityAssessmentId,
    UUID fitAssessmentId, UUID stabilityAssessmentId, EligibilityStatus eligibilityStatus,
    OpportunityTier tier, RecommendationStatus recommendationStatus,
    int fitScore, int stabilityScore, int coveragePercent,
    String evaluatorVersion, String profileVersion, String jobContentFingerprint, Instant assessedAt
) {
    public DecisionAssessment {
        Objects.requireNonNull(id); Objects.requireNonNull(candidateProfileId); Objects.requireNonNull(jobPostingId);
        Objects.requireNonNull(eligibilityAssessmentId); Objects.requireNonNull(fitAssessmentId); Objects.requireNonNull(stabilityAssessmentId);
        Objects.requireNonNull(eligibilityStatus); Objects.requireNonNull(tier); Objects.requireNonNull(recommendationStatus); Objects.requireNonNull(assessedAt);
        range(fitScore, "fitScore"); range(stabilityScore, "stabilityScore"); range(coveragePercent, "coveragePercent");
        require(evaluatorVersion, "evaluatorVersion"); require(profileVersion, "profileVersion"); require(jobContentFingerprint, "jobContentFingerprint");
    }
    private static void range(int value, String field) { if (value < 0 || value > 100) throw new IllegalArgumentException(field + " must be between 0 and 100"); }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); }
}
