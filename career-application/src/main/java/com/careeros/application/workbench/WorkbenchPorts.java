package com.careeros.application.workbench;

import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.OpportunityTier;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkbenchPorts {
    private WorkbenchPorts() {}

    @FunctionalInterface
    public interface DecisionOverview {
        List<DecisionSignal> load(UUID candidateId, Instant now);
    }

    @FunctionalInterface
    public interface AcquisitionOverview {
        AcquisitionSnapshot load(Instant now);
    }

    @FunctionalInterface
    public interface ReviewOverview {
        long pendingCount();
    }

    public record DecisionSignal(
        UUID jobId, String jobTitle, String organizationName, String location,
        EligibilityStatus eligibilityStatus, OpportunityTier tier,
        int fitScore, int stabilityScore, int coveragePercent, LocalDate deadline
    ) {}

    public record ChangeSignal(
        UUID id, String changeType, URI sourceUri, Map<String,Object> jobDeltaSummary, Instant occurredAt
    ) {
        public ChangeSignal { jobDeltaSummary = jobDeltaSummary == null ? Map.of() : Map.copyOf(jobDeltaSummary); }
    }

    public record SourceSignal(
        UUID id, String name, boolean enabled, Instant lastSuccessAt, Instant lastFailureAt,
        int consecutiveFailureCount
    ) {}

    public record AcquisitionSnapshot(List<ChangeSignal> changes, List<SourceSignal> sources) {
        public AcquisitionSnapshot {
            changes = changes == null ? List.of() : List.copyOf(changes);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }
}
