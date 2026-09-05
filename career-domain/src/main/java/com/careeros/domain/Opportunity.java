package com.careeros.domain;

import com.careeros.domain.DomainEnums.OpportunityStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个岗位对候选人的机会记录。原先的 {@code matchScore} 已按产品需求 §6.2 移除——
 * 单一匹配分正是该节明令禁止的东西；取而代之的是六维 {@link OpportunityScorecard}。
 */
public record Opportunity(UUID id, UUID candidateProfileId, UUID jobPostingId, UUID eligibilityAssessmentId, OpportunityStatus status, OpportunityScorecard scorecard, String decisionNote, Instant createdAt, Instant updatedAt) {
    public Opportunity {
        Objects.requireNonNull(id); Objects.requireNonNull(candidateProfileId); Objects.requireNonNull(jobPostingId); Objects.requireNonNull(status);
        Objects.requireNonNull(scorecard, "scorecard");
        if (!scorecard.jobPostingId().equals(jobPostingId)) {
            throw new IllegalArgumentException("scorecard belongs to another job posting");
        }
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
    }
}
