package com.careeros.domain;

import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.RuleType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EligibilityAssessment(UUID id, UUID candidateProfileId, UUID jobPostingId, EligibilityStatus status, Map<RuleType, RuleResult> ruleResults, List<UUID> evidenceIds, String evaluatorVersion, Instant assessedAt) {
    public EligibilityAssessment {
        Objects.requireNonNull(id); Objects.requireNonNull(candidateProfileId); Objects.requireNonNull(jobPostingId); Objects.requireNonNull(status);
        ruleResults = ruleResults == null ? Map.of() : Map.copyOf(ruleResults); evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (evaluatorVersion == null || evaluatorVersion.isBlank()) throw new IllegalArgumentException("evaluatorVersion is required"); Objects.requireNonNull(assessedAt);
    }
    public record RuleResult(EligibilityStatus status, String explanation) { public RuleResult { Objects.requireNonNull(status); if (explanation == null || explanation.isBlank()) throw new IllegalArgumentException("explanation is required"); } }
}
