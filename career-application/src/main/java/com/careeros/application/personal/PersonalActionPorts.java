package com.careeros.application.personal;

import com.careeros.application.personal.CandidateEvidenceTaskService.CandidateEvidenceTasks;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.OpportunityTier;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PersonalActionPorts {
    private PersonalActionPorts() {}

    @FunctionalInterface
    public interface CurrentJobs {
        List<CurrentJobSignal> load(UUID candidateId, LocalDate asOf);
    }

    @FunctionalInterface
    public interface EvidenceTasks {
        CandidateEvidenceTasks load(UUID candidateId, LocalDate asOf);
    }

    @FunctionalInterface
    public interface TargetJobChanges {
        TargetJobChangeSnapshot load(UUID candidateId, LocalDate asOf);
    }

    public record CurrentJobSignal(
        UUID jobId,
        String jobTitle,
        String organizationName,
        EligibilityStatus eligibilityStatus,
        OpportunityTier tier,
        LocalDate deadline
    ) {
        public CurrentJobSignal {
            Objects.requireNonNull(jobId);
            require(jobTitle, "jobTitle");
            require(organizationName, "organizationName");
            Objects.requireNonNull(eligibilityStatus);
            Objects.requireNonNull(tier);
        }
    }

    public record TargetJobChange(
        UUID changeId,
        String changeType,
        String title,
        int affectedJobCount,
        String deepLink
    ) {
        public TargetJobChange {
            Objects.requireNonNull(changeId);
            require(changeType, "changeType");
            require(title, "title");
            if (affectedJobCount < 1) throw new IllegalArgumentException("affectedJobCount must be positive");
            if (deepLink == null || !deepLink.startsWith("/")) {
                throw new IllegalArgumentException("deepLink must be an internal path");
            }
        }
    }

    public record TargetJobChangeSnapshot(
        boolean available,
        String message,
        List<TargetJobChange> items
    ) {
        public TargetJobChangeSnapshot {
            items = List.copyOf(items);
            if (!available && (message == null || message.isBlank())) {
                throw new IllegalArgumentException("message is required when target changes are unavailable");
            }
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
