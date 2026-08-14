package com.careeros.agent.domain;

import java.util.List;

public record EligibilityAssessment(
        String jobId,
        OverallStatus status,
        List<Criterion> criteria,
        List<String> requiredConfirmations
) {
    public EligibilityAssessment {
        criteria = List.copyOf(criteria);
        requiredConfirmations = List.copyOf(requiredConfirmations);
    }

    public enum OverallStatus {
        ELIGIBLE,
        INELIGIBLE,
        CONDITIONAL,
        NEEDS_CONFIRMATION,
        CONFLICTING_EVIDENCE
    }

    public enum CriterionStatus {
        PASS,
        FAIL,
        CONDITIONAL,
        UNKNOWN,
        NOT_APPLICABLE
    }

    public record Criterion(
            String code,
            CriterionStatus status,
            String requirement,
            String candidateFact,
            String reason,
            Evidence evidence
    ) {}

    public record Evidence(String sourceUrl, String sourceText) {}
}
