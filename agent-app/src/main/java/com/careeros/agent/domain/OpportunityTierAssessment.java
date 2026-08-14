package com.careeros.agent.domain;

public record OpportunityTierAssessment(
        String jobId,
        OpportunityTier tier,
        String employmentIdentity,
        boolean evidenceSufficient,
        String reason,
        Evidence evidence
) {
    public enum OpportunityTier {
        T1_ESTABLISHMENT_TARGET,
        T2_IDENTITY_REVIEW,
        T3_STABLE_SOE_BACKUP,
        EXCLUDED,
        UNKNOWN
    }

    public record Evidence(String sourceUrl, String sourceText) {}
}
