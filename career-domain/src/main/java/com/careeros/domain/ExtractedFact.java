package com.careeros.domain;

import com.careeros.domain.DomainEnums.FactStatus;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ExtractedFact<T>(
    T value,
    FactStatus factStatus,
    double confidence,
    List<UUID> evidenceFragmentIds,
    String interpretation
) {
    public ExtractedFact {
        Objects.requireNonNull(factStatus, "factStatus");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        evidenceFragmentIds = evidenceFragmentIds == null ? List.of() : List.copyOf(evidenceFragmentIds);
        if (factStatus == FactStatus.UNKNOWN && value != null) {
            throw new IllegalArgumentException("UNKNOWN fact must not contain a value");
        }
        if (factStatus != FactStatus.UNKNOWN && value == null) {
            throw new IllegalArgumentException("known fact requires a value");
        }
        if (factStatus == FactStatus.INTERPRETED && (interpretation == null || interpretation.isBlank())) {
            throw new IllegalArgumentException("INTERPRETED fact requires an interpretation");
        }
    }
}
