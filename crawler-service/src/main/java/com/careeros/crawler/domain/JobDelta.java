package com.careeros.crawler.domain;

import java.time.OffsetDateTime;
import java.util.List;

public record JobDelta(
        String schemaVersion,
        String sourceId,
        OffsetDateTime generatedAt,
        int previousCount,
        int currentCount,
        int unchangedCount,
        List<Change> added,
        List<Change> changed,
        List<Change> removed
) {
    public JobDelta {
        added = List.copyOf(added);
        changed = List.copyOf(changed);
        removed = List.copyOf(removed);
    }

    public record Change(
            String stableKey,
            String previousJobId,
            String currentJobId,
            String employer,
            String positionTitle,
            String positionCode,
            String previousContentHash,
            String currentContentHash
    ) {}
}
