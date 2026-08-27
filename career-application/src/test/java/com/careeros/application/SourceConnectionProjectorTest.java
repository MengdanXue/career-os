package com.careeros.application;

import static com.careeros.domain.acquisition.TargetSource.ConnectionStatus.CONNECTED;
import static com.careeros.domain.acquisition.TargetSource.ConnectionStatus.FAILED;
import static com.careeros.domain.acquisition.TargetSource.ConnectionStatus.NOT_CONNECTED;
import static com.careeros.domain.acquisition.TargetSource.ConnectionStatus.PARTIAL;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import com.careeros.domain.acquisition.SourceCrawlRun;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint.CheckpointStatus;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import com.careeros.domain.acquisition.SourceYearCoverage;
import com.careeros.domain.acquisition.SourceYearCoverage.CoverageStatus;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class SourceConnectionProjectorTest {
    private static final UUID SOURCE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000303");
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    @Test
    void threeCompleteYearsAndFreshIncrementalRunProduceConnected() {
        var recording = new RecordingStore(source("HDU_RECRUITMENT"), List.of(
            coverage(2024, CoverageStatus.COMPLETE), coverage(2025, CoverageStatus.COMPLETE),
            coverage(2026, CoverageStatus.NO_TARGET_RECORDS)), Optional.of(run(RunStatus.SUCCEEDED)),
            verifiedLifecycle());
        AcquisitionStore store = recording.proxy();

        new SourceConnectionProjector(store, Duration.ofDays(2)).refresh(SOURCE_ID, NOW);

        assertThat(recording.updatedCode).isEqualTo("HDU_RECRUITMENT");
        assertThat(recording.updatedStatus).isEqualTo(CONNECTED);
    }

    @Test
    void fixedEvidenceHospitalRemainsPartial() {
        var recording = new RecordingStore(source("HZ_FIRST_HOSPITAL"), List.of(
            coverage(2024, CoverageStatus.PARTIAL), coverage(2025, CoverageStatus.PARTIAL),
            coverage(2026, CoverageStatus.PARTIAL)), Optional.empty(), List.of());
        AcquisitionStore store = recording.proxy();

        new SourceConnectionProjector(store, Duration.ofDays(2)).refresh(SOURCE_ID, NOW);

        assertThat(recording.updatedCode).isEqualTo("HZ_FIRST_HOSPITAL");
        assertThat(recording.updatedStatus).isEqualTo(PARTIAL);
    }

    @Test
    void incompleteRequiredHistoricalYearKeepsFreshVerifiedSourcePartial() {
        var recording = new RecordingStore(source("HDU_RECRUITMENT"), List.of(
            coverage(2024, CoverageStatus.COMPLETE), coverage(2025, CoverageStatus.PARTIAL),
            coverage(2026, CoverageStatus.NO_TARGET_RECORDS)), Optional.of(run(RunStatus.SUCCEEDED)),
            verifiedLifecycle());

        new SourceConnectionProjector(recording.proxy(), Duration.ofDays(2)).refresh(SOURCE_ID, NOW);

        assertThat(recording.updatedStatus).isEqualTo(PARTIAL);
    }

    @Test
    void disabledSourceIsNotConnectedEvenWithConclusiveEvidence() {
        var recording = new RecordingStore(source("HDU_RECRUITMENT", false, 0), List.of(
            coverage(2024, CoverageStatus.COMPLETE), coverage(2025, CoverageStatus.COMPLETE),
            coverage(2026, CoverageStatus.NO_TARGET_RECORDS)), Optional.of(run(RunStatus.SUCCEEDED)),
            verifiedLifecycle());

        new SourceConnectionProjector(recording.proxy(), Duration.ofDays(2)).refresh(SOURCE_ID, NOW);

        assertThat(recording.updatedStatus).isEqualTo(NOT_CONNECTED);
    }

    @Test
    void backfillWithoutIncrementalVerificationRemainsPartial() {
        var recording = new RecordingStore(source("HDU_RECRUITMENT"), List.of(
            coverage(2024, CoverageStatus.COMPLETE), coverage(2025, CoverageStatus.COMPLETE),
            coverage(2026, CoverageStatus.NO_TARGET_RECORDS)), Optional.of(run(RunStatus.SUCCEEDED)),
            List.of(checkpoint(Checkpoint.BACKFILL_COMPLETE)));

        new SourceConnectionProjector(recording.proxy(), Duration.ofDays(2)).refresh(SOURCE_ID, NOW);

        assertThat(recording.updatedStatus).isEqualTo(PARTIAL);
    }

    @Test
    void threeConsecutiveSourceFailuresProduceFailed() {
        var recording = new RecordingStore(source("HDU_RECRUITMENT", 3), List.of(),
            Optional.of(run(RunStatus.FAILED)), List.of());

        new SourceConnectionProjector(recording.proxy(), Duration.ofDays(2)).refresh(SOURCE_ID, NOW);

        assertThat(recording.updatedStatus).isEqualTo(FAILED);
    }

    private static SourceYearCoverage coverage(int year, CoverageStatus status) {
        boolean complete = status == CoverageStatus.COMPLETE || status == CoverageStatus.NO_TARGET_RECORDS;
        return new SourceYearCoverage(SOURCE_ID, year, status, 1, 1, 1,
            status == CoverageStatus.COMPLETE ? 1 : 0, complete ? "verified listing" : null,
            complete ? NOW : null, NOW);
    }

    private static RecruitmentSource source(String code) {
        return source(code, 0);
    }

    private static RecruitmentSource source(String code, int consecutiveFailures) {
        return source(code, true, consecutiveFailures);
    }

    private static RecruitmentSource source(String code, boolean enabled, int consecutiveFailures) {
        return new RecruitmentSource(SOURCE_ID, code, code, URI.create("https://official.example/"),
            URI.create("https://official.example/list"), SourceType.OFFICIAL_ORGANIZATION, "杭州",
            CrawlMode.STATIC_HTML, enabled, "0 0 8 * * *", "Asia/Shanghai", Duration.ZERO,
            Map.of(), null, null, NOW, consecutiveFailures, NOW, NOW);
    }

    private static List<SourceOnboardingCheckpoint> verifiedLifecycle() {
        return List.of(checkpoint(Checkpoint.CONTRACT_VERIFIED, NOW.minus(Duration.ofHours(3))),
            checkpoint(Checkpoint.BACKFILL_COMPLETE, NOW.minus(Duration.ofHours(2))),
            checkpoint(Checkpoint.INCREMENTAL_VERIFIED, NOW.minus(Duration.ofHours(1))));
    }

    private static SourceOnboardingCheckpoint checkpoint(Checkpoint value) {
        return checkpoint(value, NOW);
    }

    private static SourceOnboardingCheckpoint checkpoint(Checkpoint value, Instant verifiedAt) {
        return new SourceOnboardingCheckpoint(SOURCE_ID, value, CheckpointStatus.VERIFIED,
            "verified test evidence", verifiedAt);
    }

    private static SourceCrawlRun run(RunStatus status) {
        return new SourceCrawlRun(UUID.randomUUID(), SOURCE_ID, RunTrigger.SCHEDULED, status,
            NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(50)), 1, 1, 0, 1, 0, 0,
            status == RunStatus.SUCCEEDED ? 0 : 1, null, null);
    }

    private static final class RecordingStore implements java.lang.reflect.InvocationHandler {
        private final RecruitmentSource source;
        private final List<SourceYearCoverage> coverage;
        private final Optional<SourceCrawlRun> latest;
        private final List<SourceOnboardingCheckpoint> checkpoints;
        private String updatedCode;
        private com.careeros.domain.acquisition.TargetSource.ConnectionStatus updatedStatus;

        private RecordingStore(
            RecruitmentSource source, List<SourceYearCoverage> coverage, Optional<SourceCrawlRun> latest,
            List<SourceOnboardingCheckpoint> checkpoints
        ) {
            this.source = source; this.coverage = coverage; this.latest = latest;
            this.checkpoints = checkpoints;
        }

        private AcquisitionStore proxy() {
            return (AcquisitionStore) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { AcquisitionStore.class }, this);
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "findSource" -> source;
                case "findSourceYearCoverage" -> coverage;
                case "findLatestRun" -> latest;
                case "findCheckpoints" -> checkpoints;
                case "updateTargetSourceStatus" -> {
                    updatedCode = (String) args[0];
                    updatedStatus = (com.careeros.domain.acquisition.TargetSource.ConnectionStatus) args[1];
                    yield null;
                }
                case "toString" -> "RecordingStore";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
