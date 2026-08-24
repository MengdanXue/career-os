package com.careeros.application.personal;

import com.careeros.domain.DomainEnums.EligibilityStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DecisionChangeSummary(
    UUID candidateId,
    String previousProfileVersion,
    String currentProfileVersion,
    LocalDate asOf,
    boolean available,
    String message,
    Integer newlyEligibleCount,
    Integer resolvedUncertaintyCount,
    Integer newlyIneligibleCount,
    List<AffectedJob> affectedJobs
) {
    public DecisionChangeSummary {
        Objects.requireNonNull(candidateId);
        require(previousProfileVersion, "previousProfileVersion");
        require(currentProfileVersion, "currentProfileVersion");
        Objects.requireNonNull(asOf);
        affectedJobs = List.copyOf(affectedJobs == null ? List.of() : affectedJobs);
        if (!available && (newlyEligibleCount != null || resolvedUncertaintyCount != null
            || newlyIneligibleCount != null)) {
            throw new IllegalArgumentException("unavailable differences must not contain invented counts");
        }
    }

    public record AffectedJob(
        UUID jobId,
        String title,
        String organizationName,
        EligibilityStatus previousStatus,
        EligibilityStatus currentStatus,
        List<String> reasons,
        String deepLink
    ) {
        public AffectedJob {
            Objects.requireNonNull(jobId);
            Objects.requireNonNull(previousStatus);
            Objects.requireNonNull(currentStatus);
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            require(deepLink, "deepLink");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
