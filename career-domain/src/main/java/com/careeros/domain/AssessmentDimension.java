package com.careeros.domain;

import com.careeros.domain.DomainEnums.AssessmentDimensionType;
import com.careeros.domain.DomainEnums.AssessmentFactStatus;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AssessmentDimension(
    AssessmentDimensionType type,
    int achievedPoints,
    int maximumPoints,
    AssessmentFactStatus factStatus,
    String reasonCode,
    String explanation,
    List<UUID> evidenceIds
) {
    public AssessmentDimension {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factStatus, "factStatus");
        if (maximumPoints <= 0) throw new IllegalArgumentException("maximumPoints must be positive");
        if (achievedPoints < 0 || achievedPoints > maximumPoints) throw new IllegalArgumentException("achievedPoints is outside dimension range");
        if (factStatus == AssessmentFactStatus.UNKNOWN && achievedPoints != 0) throw new IllegalArgumentException("unknown dimension cannot award points");
        if (reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("reasonCode is required");
        if (explanation == null || explanation.isBlank()) throw new IllegalArgumentException("explanation is required");
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
