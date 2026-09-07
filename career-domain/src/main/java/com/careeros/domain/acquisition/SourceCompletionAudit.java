package com.careeros.domain.acquisition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, append-only assessment of the frozen source/year denominator. */
public record SourceCompletionAudit(
    UUID auditSnapshotId, UUID parentSnapshotId, UUID registrySnapshotId,
    int fromYear, int toYear, LocalDate coverageThrough, Instant cutoffAt,
    Instant assessedAt, String registryHash, String assessorVersion,
    List<SourceAssessment> sources
) {
    public SourceCompletionAudit {
        Objects.requireNonNull(auditSnapshotId); Objects.requireNonNull(registrySnapshotId);
        Objects.requireNonNull(cutoffAt); Objects.requireNonNull(assessedAt);
        Objects.requireNonNull(registryHash); Objects.requireNonNull(assessorVersion);
        if (fromYear < 2000 || toYear < fromYear || toYear > 2100) throw new IllegalArgumentException("invalid year range");
        sources = List.copyOf(Objects.requireNonNull(sources));
    }

    public enum GateStatus { PASS, FAIL, UNKNOWN }
    public enum HistoricalConclusion { UNKNOWN, PARTIAL, ARCHIVE_UNAVAILABLE, MOVED, VERIFIED_NO_DATA, COMPLETE }

    public record Gate(GateStatus status, List<String> reasonCodes, List<String> evidenceRefs) {
        public Gate { Objects.requireNonNull(status); reasonCodes = List.copyOf(reasonCodes == null ? List.of() : reasonCodes); evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs); }
    }
    public record YearAssessment(int year, HistoricalConclusion conclusion, List<String> reasonCodes, List<String> evidenceRefs) {
        public YearAssessment { if (year < 2000 || year > 2100) throw new IllegalArgumentException("invalid year"); Objects.requireNonNull(conclusion); reasonCodes = List.copyOf(reasonCodes == null ? List.of() : reasonCodes); evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs); }
    }
    public record SourceAssessment(String sourceCode, UUID sourceId, List<YearAssessment> years, List<Gate> gates) {
        public SourceAssessment { Objects.requireNonNull(sourceCode); years = List.copyOf(years == null ? List.of() : years); gates = List.copyOf(gates == null ? List.of() : gates); if (gates.size() > 6) throw new IllegalArgumentException("at most six gates"); }
    }
}
