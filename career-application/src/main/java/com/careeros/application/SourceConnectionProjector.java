package com.careeros.application;

import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import com.careeros.domain.acquisition.SourceYearCoverage;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint.CheckpointStatus;
import com.careeros.domain.acquisition.TargetSource.ConnectionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SourceConnectionProjector {
    private final AcquisitionStore store;
    private final Duration freshness;

    public SourceConnectionProjector(AcquisitionStore store, Duration freshness) {
        this.store = Objects.requireNonNull(store, "store");
        this.freshness = Objects.requireNonNull(freshness, "freshness");
        if (freshness.isNegative() || freshness.isZero()) throw new IllegalArgumentException("freshness must be positive");
    }

    public ConnectionStatus refresh(UUID sourceId, Instant now) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(now, "now");
        var source = store.findSource(sourceId);
        List<SourceYearCoverage> annual = store.findSourceYearCoverage(sourceId, null).stream()
            .filter(value -> value.recruitmentYear() >= 2024 && value.recruitmentYear() <= 2026)
            .toList();
        boolean completeYears = annual.size() == 3
            && annual.stream().allMatch(SourceYearCoverage::hasLegacyCompletionRecord);
        var latestRun = store.findLatestRun(sourceId);
        boolean freshSuccess = latestRun
            .filter(run -> run.status() == RunStatus.SUCCEEDED)
            .map(run -> run.completedAt() != null && !run.completedAt().isBefore(now.minus(freshness)))
            .orElse(false);
        var verified = store.findCheckpoints(sourceId).stream()
            .filter(value -> value.status() == CheckpointStatus.VERIFIED)
            .collect(java.util.stream.Collectors.toMap(value -> value.checkpoint(), value -> value.verifiedAt(),
                (left, right) -> left.isAfter(right) ? left : right));
        boolean lifecycleVerified = verified.containsKey(Checkpoint.CONTRACT_VERIFIED)
            && verified.containsKey(Checkpoint.BACKFILL_COMPLETE)
            && verified.containsKey(Checkpoint.INCREMENTAL_VERIFIED)
            && !verified.get(Checkpoint.INCREMENTAL_VERIFIED)
                .isBefore(verified.get(Checkpoint.BACKFILL_COMPLETE));
        ConnectionStatus status;
        if (!source.enabled()) status = ConnectionStatus.NOT_CONNECTED;
        else if (source.consecutiveFailureCount() >= 3
            && latestRun.map(run -> run.status() == RunStatus.FAILED).orElse(true)) {
            status = ConnectionStatus.FAILED;
        }
        else if (completeYears && freshSuccess && lifecycleVerified) status = ConnectionStatus.CONNECTED;
        else status = ConnectionStatus.PARTIAL;
        store.updateTargetSourceStatus(source.code(), status, source.id(), now);
        return status;
    }
}
