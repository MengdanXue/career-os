package com.careeros.application;

import com.careeros.domain.DomainEnums.EventType;
import com.careeros.domain.acquisition.ArtifactImportFailure.FailureStage;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public interface AcquiredDocumentProcessor {
    default String version() { return "processor-v1"; }

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
        String errorCode,
        java.util.List<ProcessingIssue> issues
    ) {
        public ProcessingResult(
            ProcessingStatus status, UUID extractionRunId, UUID recruitmentEventId,
            int inserted, int updated, int unchanged, int deactivated, String errorCode
        ) {
            this(status, extractionRunId, recruitmentEventId, inserted, updated,
                unchanged, deactivated, errorCode, java.util.List.of());
        }

        public ProcessingResult {
            Objects.requireNonNull(status, "status");
            if (inserted < 0 || updated < 0 || unchanged < 0 || deactivated < 0) {
                throw new IllegalArgumentException("processing counters cannot be negative");
            }
            issues = issues == null ? java.util.List.of() : java.util.List.copyOf(issues);
        }

        public static ProcessingResult extracted(UUID runId) {
            return new ProcessingResult(ProcessingStatus.PROCESSED, runId, null, 0, 0, 0, 0, null, java.util.List.of());
        }
        public static ProcessingResult imported(UUID eventId, int inserted, int updated, int unchanged, int deactivated) {
            return new ProcessingResult(ProcessingStatus.PROCESSED, null, eventId,
                inserted, updated, unchanged, deactivated, null, java.util.List.of());
        }
        public static ProcessingResult importedWithErrors(
            UUID eventId, int inserted, int updated, int unchanged, int deactivated, int rowErrorCount
        ) {
            if (rowErrorCount < 1) throw new IllegalArgumentException("rowErrorCount must be positive");
            return new ProcessingResult(ProcessingStatus.PROCESSED_WITH_ERRORS, null, eventId,
                inserted, updated, unchanged, deactivated, "ROW_ERRORS:" + rowErrorCount,
                java.util.List.of(new ProcessingIssue(FailureStage.DOCUMENT_PARSE_FAILED, null, null,
                    "ROW_ERRORS", rowErrorCount + " row(s) could not be imported")));
        }
        public static ProcessingResult importedWithErrors(
            UUID eventId, int inserted, int updated, int unchanged, int deactivated,
            java.util.List<ProcessingIssue> issues
        ) {
            if (issues == null || issues.isEmpty()) throw new IllegalArgumentException("issues are required");
            return new ProcessingResult(ProcessingStatus.PROCESSED_WITH_ERRORS, null, eventId,
                inserted, updated, unchanged, deactivated, "ROW_ERRORS:" + issues.size(), issues);
        }
        public static ProcessingResult unsupported() {
            return new ProcessingResult(ProcessingStatus.UNSUPPORTED, null, null, 0, 0, 0, 0,
                "UNSUPPORTED_MEDIA_TYPE", java.util.List.of());
        }
        public static ProcessingResult ignored(String reasonCode) {
            requireText(reasonCode, "reasonCode");
            return new ProcessingResult(ProcessingStatus.IGNORED, null, null, 0, 0, 0, 0, reasonCode, java.util.List.of());
        }
        public static ProcessingResult failed(String code) {
            return new ProcessingResult(ProcessingStatus.FAILED, null, null, 0, 0, 0, 0, code, java.util.List.of());
        }
        public boolean successful() {
            return status == ProcessingStatus.PROCESSED
                || status == ProcessingStatus.PROCESSED_WITH_ERRORS
                || status == ProcessingStatus.IGNORED;
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
            if (!issues.isEmpty()) summary.put("issueCount", issues.size());
            return Map.copyOf(summary);
        }
    }

    record ProcessingIssue(
        FailureStage stage,
        String sheetName,
        Integer rowNumber,
        String errorCode,
        String safeMessage
    ) {
        public ProcessingIssue {
            Objects.requireNonNull(stage, "stage");
            requireText(errorCode, "errorCode");
            safeMessage = com.careeros.domain.acquisition.ArtifactImportFailure.sanitize(safeMessage);
            sheetName = sheetName == null || sheetName.isBlank() ? null : sheetName.trim();
            if (rowNumber != null && rowNumber <= 0) throw new IllegalArgumentException("rowNumber must be positive");
            if (stage == FailureStage.ROW_PARSE_FAILED && (sheetName == null || rowNumber == null)) {
                throw new IllegalArgumentException("row issue requires sheetName and rowNumber");
            }
        }
    }

    enum ProcessingStatus { PROCESSED, PROCESSED_WITH_ERRORS, IGNORED, UNSUPPORTED, FAILED }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
