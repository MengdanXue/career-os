package com.careeros.domain;

import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.OpportunityTier;
import com.careeros.domain.DomainEnums.OrganizationType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个岗位落在哪个关注池，以及为什么。{@code evidenceSufficient} 为 false 表示分层依据不足，
 * 结论只能当作待核验的初判——产品需求 §3 要求 T1/T2/T3 分池呈现，不得混排成"半体制总榜"。
 */
public record OpportunityTierAssessment(
    UUID jobPostingId,
    OpportunityTier tier,
    EmploymentType employmentType,
    OrganizationType organizationType,
    boolean evidenceSufficient,
    String reason,
    List<UUID> evidenceIds
) {
    public OpportunityTierAssessment {
        Objects.requireNonNull(jobPostingId, "jobPostingId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(employmentType, "employmentType");
        Objects.requireNonNull(organizationType, "organizationType");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (tier == OpportunityTier.T1_ESTABLISHMENT_TARGET && !evidenceSufficient) {
            throw new IllegalArgumentException("T1 requires sufficient employment-identity evidence");
        }
    }
}
