package com.careeros;

import com.careeros.application.AcquisitionPorts.ChangeCursor;
import com.careeros.application.AcquisitionPorts.ChangePage;
import com.careeros.application.AcquisitionPorts.RunPage;
import com.careeros.domain.acquisition.AcquisitionChange;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.SourceCrawlRun;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class AcquisitionApiModels {
    private AcquisitionApiModels() {}

    record SourceResponse(
        UUID id, String code, String name, String entryUri, String sourceType, String region,
        String crawlMode, boolean enabled, String cronExpression, String timeZone,
        Instant lastSuccessAt, Instant lastFailureAt, Instant nextDueAt, int consecutiveFailureCount
    ) {
        static SourceResponse from(RecruitmentSource value) {
            return new SourceResponse(value.id(),value.code(),value.name(),value.entryUri().toString(),
                value.sourceType().name(),value.region(),value.crawlMode().name(),value.enabled(),
                value.cronExpression(),value.timeZone(),value.lastSuccessAt(),value.lastFailureAt(),
                value.nextDueAt(),value.consecutiveFailureCount());
        }
    }

    record RunResponse(
        UUID id, UUID sourceId, String trigger, String status, Instant startedAt, Instant completedAt,
        int discoveredCount, int fetchedCount, int unchangedCount, int addedCount, int updatedCount,
        int deactivatedCount, int failedCount, String errorCode, String errorMessage
    ) {
        static RunResponse from(SourceCrawlRun value) {
            return new RunResponse(value.id(),value.sourceId(),value.trigger().name(),value.status().name(),
                value.startedAt(),value.completedAt(),value.discoveredCount(),value.fetchedCount(),
                value.unchangedCount(),value.addedCount(),value.updatedCount(),value.deactivatedCount(),
                value.failedCount(),value.errorCode(),value.errorMessage());
        }
    }

    record RunPageResponse(List<RunResponse> items, int page, int size, long total) {
        static RunPageResponse from(RunPage value) {
            return new RunPageResponse(value.items().stream().map(RunResponse::from).toList(),
                value.page(),value.size(),value.total());
        }
    }

    record ChangeResponse(
        UUID id, UUID runId, UUID sourceId, UUID documentId, String changeType,
        String previousFingerprint, String currentFingerprint, String canonicalUri,
        Map<String,Object> jobDeltaSummary, Instant occurredAt
    ) {
        static ChangeResponse from(AcquisitionChange value) {
            return new ChangeResponse(value.id(),value.runId(),value.sourceId(),value.documentId(),
                value.changeType().name(),value.previousFingerprint(),value.currentFingerprint(),
                value.canonicalUri().toString(),value.jobDeltaSummary(),value.occurredAt());
        }
    }

    record ChangePageResponse(List<ChangeResponse> items, String nextCursor) {
        static ChangePageResponse from(ChangePage value) {
            return new ChangePageResponse(value.items().stream().map(ChangeResponse::from).toList(),
                CursorCodec.encode(value.nextCursor()));
        }
    }

    static final class CursorCodec {
        private CursorCodec() {}
        static String encode(ChangeCursor cursor) {
            if (cursor == null) return null;
            String plain = cursor.occurredAt() + "|" + cursor.id();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        }
        static ChangeCursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) return null;
            try {
                String plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                String[] parts = plain.split("\\|", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Invalid change cursor");
                return new ChangeCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid change cursor", exception);
            }
        }
    }
}
