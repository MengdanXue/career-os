package com.careeros.domain;

import com.careeros.domain.DomainEnums.OpportunityStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Opportunity(UUID id, UUID candidateProfileId, UUID jobPostingId, UUID eligibilityAssessmentId, OpportunityStatus status, int matchScore, String decisionNote, Instant createdAt, Instant updatedAt) {
    public Opportunity {
        Objects.requireNonNull(id); Objects.requireNonNull(candidateProfileId); Objects.requireNonNull(jobPostingId); Objects.requireNonNull(status);
        if (matchScore < 0 || matchScore > 100) throw new IllegalArgumentException("matchScore must be between 0 and 100"); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
    }
}
