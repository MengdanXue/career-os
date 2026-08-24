package com.careeros.domain.acquisition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ArtifactImportFailure(
    UUID id,
    UUID runId,
    UUID sourceId,
    UUID documentId,
    FailureStage stage,
    String sheetName,
    Integer rowNumber,
    String errorCode,
    String safeMessage,
    Instant occurredAt
) {
    public enum FailureStage {
        DISCOVERY_CONTRACT_CHANGED,
        REMOTE_ACCESS_FAILED,
        ARTIFACT_DOWNLOAD_FAILED,
        UNSUPPORTED_DOCUMENT,
        DOCUMENT_PARSE_FAILED,
        ROW_PARSE_FAILED,
        NORMALIZATION_FAILED,
        EVIDENCE_LINK_FAILED
    }

    public ArtifactImportFailure {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(stage, "stage");
        sheetName = optional(sheetName);
        errorCode = required(errorCode, "errorCode");
        safeMessage = sanitize(required(safeMessage, "safeMessage"));
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (stage == FailureStage.ROW_PARSE_FAILED
            && (documentId == null || sheetName == null || rowNumber == null || rowNumber <= 0)) {
            throw new IllegalArgumentException("row failure requires sheetName and a positive rowNumber");
        }
        if (rowNumber != null && rowNumber <= 0) {
            throw new IllegalArgumentException("rowNumber must be positive");
        }
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) return "未提供错误详情";
        String safe = value
            .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[手机号已脱敏]")
            .replaceAll("(?<!\\d)\\d{17}[0-9Xx](?!\\d)", "[身份证号已脱敏]")
            .trim();
        return safe.length() <= 500 ? safe : safe.substring(0, 500);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
