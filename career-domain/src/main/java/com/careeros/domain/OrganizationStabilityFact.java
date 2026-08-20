package com.careeros.domain;

import static com.careeros.domain.DomainEnums.AssessmentDimensionType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OrganizationStabilityFact(
    UUID id, UUID organizationId, AssessmentDimensionType dimensionType,
    int achievedPoints, int maximumPoints, String reasonCode, String explanation,
    List<UUID> evidenceIds, Instant observedAt
) {
    private static final Set<AssessmentDimensionType> ALLOWED = Set.of(
        AssessmentDimensionType.FUNDING_STABILITY, AssessmentDimensionType.ORGANIZATION_STABILITY,
        AssessmentDimensionType.POLICY_STABILITY, AssessmentDimensionType.BUSINESS_VOLATILITY,
        AssessmentDimensionType.LAYOFF_RISK
    );
    public OrganizationStabilityFact {
        Objects.requireNonNull(id); Objects.requireNonNull(organizationId); Objects.requireNonNull(dimensionType); Objects.requireNonNull(observedAt);
        if (!ALLOWED.contains(dimensionType)) throw new IllegalArgumentException("dimensionType is not an organization stability fact");
        if (maximumPoints <= 0 || achievedPoints < 0 || achievedPoints > maximumPoints) throw new IllegalArgumentException("fact points are invalid");
        if (reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("reasonCode is required");
        if (explanation == null || explanation.isBlank()) throw new IllegalArgumentException("explanation is required");
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (evidenceIds.isEmpty()) throw new IllegalArgumentException("stability fact requires evidence");
    }
}
