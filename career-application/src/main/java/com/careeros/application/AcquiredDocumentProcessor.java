package com.careeros.application;

import com.careeros.domain.DomainEnums.EventType;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public interface AcquiredDocumentProcessor {
    ProcessingResult process(ProcessDocumentCommand command);

    record ProcessDocumentCommand(
        byte[] content,
        String mediaType,
        URI documentUri,
        URI parentAnnouncementUri,
        String announcementTitle,
        Instant capturedAt,
        int recruitmentYear,
        LocalDate publishedOn,
        String defaultLocation,
        EventType eventType
    ) {
        public ProcessDocumentCommand {
            content = content == null ? new byte[0] : content.clone();
            requireText(mediaType, "mediaType");
            Objects.requireNonNull(documentUri, "documentUri");
            Objects.requireNonNull(parentAnnouncementUri, "parentAnnouncementUri");
            requireText(announcementTitle, "announcementTitle");
            Objects.requireNonNull(capturedAt, "capturedAt");
            if (recruitmentYear < 2000 || recruitmentYear > 2100) {
                throw new IllegalArgumentException("recruitmentYear is out of range");
            }
            Objects.requireNonNull(eventType, "eventType");
        }
        @Override public byte[] content() { return content.clone(); }
    }

    record ProcessingResult(
        ProcessingStatus status,
        UUID extractionRunId,
        UUID recruitmentEventId,
        int inserted,
        int updated,
        int unchanged,
        int deactivated,
        String errorCode
    ) {
        public ProcessingResult {
            Objects.requireNonNull(status, "status");
            if (inserted < 0 || updated < 0 || unchanged < 0 || deactivated < 0) {
                throw new IllegalArgumentException("processing counters cannot be negative");
            }
        }

        public static ProcessingResult extracted(UUID runId) {
            return new ProcessingResult(ProcessingStatus.PROCESSED, runId, null, 0, 0, 0, 0, null);
        }
        public static ProcessingResult imported(UUID eventId, int inserted, int updated, int unchanged, int deactivated) {
            return new ProcessingResult(ProcessingStatus.PROCESSED, null, eventId,
                inserted, updated, unchanged, deactivated, null);
        }
        public static ProcessingResult unsupported() {
            return new ProcessingResult(ProcessingStatus.UNSUPPORTED, null, null, 0, 0, 0, 0,
                "UNSUPPORTED_MEDIA_TYPE");
        }
        public static ProcessingResult ignored(String reasonCode) {
            requireText(reasonCode, "reasonCode");
            return new ProcessingResult(ProcessingStatus.IGNORED, null, null, 0, 0, 0, 0, reasonCode);
        }
        public static ProcessingResult failed(String code) {
            return new ProcessingResult(ProcessingStatus.FAILED, null, null, 0, 0, 0, 0, code);
        }
        public boolean successful() {
            return status == ProcessingStatus.PROCESSED || status == ProcessingStatus.IGNORED;
        }
        public Map<String, Object> summary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("processingStatus", status.name());
            summary.put("inserted", inserted);
            summary.put("updated", updated);
            summary.put("unchanged", unchanged);
            summary.put("deactivated", deactivated);
            if (extractionRunId != null) summary.put("extractionRunId", extractionRunId.toString());
            if (recruitmentEventId != null) summary.put("recruitmentEventId", recruitmentEventId.toString());
            if (errorCode != null) summary.put("errorCode", errorCode);
            return Map.copyOf(summary);
        }
    }

    enum ProcessingStatus { PROCESSED, IGNORED, UNSUPPORTED, FAILED }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
