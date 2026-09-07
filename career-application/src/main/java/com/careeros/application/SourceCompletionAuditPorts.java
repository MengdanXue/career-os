package com.careeros.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class SourceCompletionAuditPorts {
    private SourceCompletionAuditPorts() {}

    public interface AuditSnapshots {
        void append(UUID id, UUID parentId, UUID registrySnapshotId, int fromYear, int toYear,
            String coverageThrough, Instant cutoffAt, Instant assessedAt, String registryHash,
            String assessorVersion, String payload);
        Optional<String> find(UUID id);
        Optional<String> findLatest(int fromYear, int toYear);
        Optional<SnapshotEnvelope> findEnvelope(UUID id);

        record SnapshotEnvelope(UUID id, UUID parentId, UUID registrySnapshotId, int fromYear, int toYear,
            String coverageThrough, Instant cutoffAt, Instant assessedAt, String registryHash,
            String assessorVersion, String payload) {}
    }
}
