package com.careeros.application;

import com.careeros.domain.acquisition.AcquiredDocument;
import com.careeros.domain.acquisition.AcquisitionChange;
import com.careeros.domain.acquisition.AcquisitionChange.ChangeType;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.SourceCrawlRun;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class AcquisitionPorts {
    private AcquisitionPorts() {}

    public interface AcquisitionStore {
        List<RecruitmentSource> findDueSources(Instant now, int limit);
        List<RecruitmentSource> findSources();
        RecruitmentSource findSource(UUID id);
        RecruitmentSource saveSource(RecruitmentSource source);
        SourceCrawlRun saveRun(SourceCrawlRun run);
        SourceCrawlRun findRun(UUID id);
        RunPage findRuns(RunQuery query, int page, int size);
        Optional<AcquiredDocument> findDocument(UUID sourceId, URI canonicalUri);
        List<AcquiredDocument> findDocuments(UUID sourceId);
        AcquiredDocument saveDocument(AcquiredDocument document);
        AcquisitionChange appendChange(AcquisitionChange change);
        PersistedDocumentChange saveDocumentAndChange(AcquiredDocument document, AcquisitionChange change);
        ChangePage findChanges(ChangeCursor cursor, UUID sourceId, Set<ChangeType> types, int size);
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
