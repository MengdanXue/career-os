package com.careeros.domain;

import com.careeros.domain.DomainEnums.AssessmentFactStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record FitAssessment(
    UUID id, UUID candidateProfileId, UUID jobPostingId, List<AssessmentDimension> dimensions,
    String evaluatorVersion, String profileVersion, String jobContentFingerprint, Instant assessedAt
) {
    public FitAssessment {
        Objects.requireNonNull(id); Objects.requireNonNull(candidateProfileId); Objects.requireNonNull(jobPostingId);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        require(evaluatorVersion, "evaluatorVersion"); require(profileVersion, "profileVersion"); require(jobContentFingerprint, "jobContentFingerprint");
        Objects.requireNonNull(assessedAt);
    }
    public int score() { return dimensions.stream().mapToInt(AssessmentDimension::achievedPoints).sum(); }
    public int maximumScore() { return dimensions.stream().mapToInt(AssessmentDimension::maximumPoints).sum(); }
    public int coveragePercent() {
        int maximum = maximumScore();
        if (maximum == 0) return 0;
        int known = dimensions.stream().filter(d -> d.factStatus() != AssessmentFactStatus.UNKNOWN).mapToInt(AssessmentDimension::maximumPoints).sum();
        return Math.round(known * 100f / maximum);
    }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); }
}
