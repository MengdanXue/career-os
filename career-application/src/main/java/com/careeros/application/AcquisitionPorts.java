package com.careeros.application;

import com.careeros.domain.acquisition.AcquiredDocument;
import com.careeros.domain.acquisition.AcquisitionChange;
import com.careeros.domain.acquisition.ArtifactImportFailure;
import com.careeros.domain.acquisition.AcquisitionChange.ChangeType;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.SourceCrawlRun;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint;
import com.careeros.domain.acquisition.SourceYearCoverage;
import com.careeros.domain.acquisition.TargetSource.ConnectionStatus;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.function.Supplier;

public final class AcquisitionPorts {
    private AcquisitionPorts() {}

    public interface AcquisitionStore {
        List<RecruitmentSource> findDueSources(Instant now, int limit);
        List<RecruitmentSource> findSources();
        RecruitmentSource findSource(UUID id);
        RecruitmentSource saveSource(RecruitmentSource source);
        SourceCrawlRun saveRun(SourceCrawlRun run);
        SourceCrawlRun findRun(UUID id);
        Optional<SourceCrawlRun> findLatestRun(UUID sourceId);
        RunPage findRuns(RunQuery query, int page, int size);
        Optional<AcquiredDocument> findDocument(UUID sourceId, URI canonicalUri);
        List<AcquiredDocument> findDocuments(UUID sourceId);
        AcquiredDocument saveDocument(AcquiredDocument document);
        AcquisitionChange appendChange(AcquisitionChange change);
        PersistedDocumentChange saveDocumentAndChange(AcquiredDocument document, AcquisitionChange change);
        ChangePage findChanges(ChangeCursor cursor, UUID sourceId, Set<ChangeType> types, int size);
        List<SourceYearCoverage> findSourceYearCoverage(UUID sourceId, Integer recruitmentYear);
        SourceYearCoverage saveSourceYearCoverage(SourceYearCoverage coverage);
        SourceOnboardingCheckpoint saveCheckpoint(SourceOnboardingCheckpoint checkpoint);
        List<SourceOnboardingCheckpoint> findCheckpoints(UUID sourceId);
        List<ArtifactImportFailure> saveImportFailures(List<ArtifactImportFailure> failures);
        List<ArtifactImportFailure> findImportFailures(UUID sourceId, UUID runId);
        long countImportFailures(UUID sourceId);
        ConnectionStatus findTargetSourceStatus(String sourceCode);
        void updateTargetSourceStatus(
            String sourceCode, ConnectionStatus status, UUID recruitmentSourceId, Instant updatedAt);
        long countActiveTargetJobs(UUID sourceId, int recruitmentYear);
    }

    public interface SourceRunLock {
        Optional<SourceCrawlRun> tryExecute(
            String sourceCode, Duration wait, Supplier<SourceCrawlRun> work);
    }

    public interface NextRunCalculator {
        Instant next(RecruitmentSource source, Instant after);
    }

    public interface AcquisitionObserver {
        AcquisitionObserver NOOP = new AcquisitionObserver() {};
        default void runCompleted(String sourceCode, SourceCrawlRun run) {}
        default void document(String sourceCode, String result) {}
        default void fetch(String sourceCode, Duration duration) {}
        default void processingFailure(String sourceCode, String mediaType) {}
        default void lockSkipped(String sourceCode) {}
    }

    public record ChangeCursor(Instant occurredAt, UUID id) {
        public ChangeCursor {
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(id, "id");
        }
    }

    public record ChangePage(List<AcquisitionChange> items, ChangeCursor nextCursor) {
        public ChangePage { items = List.copyOf(items); }
    }

    public record RunQuery(UUID sourceId, RunStatus status, Instant from, Instant to) {}

    public record RunPage(List<SourceCrawlRun> items, int page, int size, long total) {
        public RunPage { items = List.copyOf(items); }
    }

    public record PersistedDocumentChange(AcquiredDocument document, AcquisitionChange change) {}
}
