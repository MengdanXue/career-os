package com.careeros.domain;

import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.ExtractionSourceType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ExtractionRun(
    UUID id,
    UUID evidenceId,
    UUID organizationId,
    UUID recruitmentEventId,
    String inputFingerprint,
    ExtractionSourceType sourceType,
    String parserName,
    String parserVersion,
    String extractorName,
    String extractorVersion,
    String modelName,
    String promptVersion,
    String schemaVersion,
    DataQualityStatus status,
    double confidence,
    RecruitmentExtractionProposal proposedPayload,
    String modelResponse,
    String errorCode,
    String errorMessage,
    Instant startedAt,
    Instant completedAt
) {
    private static final Map<DataQualityStatus, Set<DataQualityStatus>> ALLOWED = Map.of(
        DataQualityStatus.RAW, Set.of(DataQualityStatus.PARSED, DataQualityStatus.FAILED),
        DataQualityStatus.PARSED, Set.of(DataQualityStatus.NORMALIZED, DataQualityStatus.REVIEW_REQUIRED, DataQualityStatus.FAILED),
        DataQualityStatus.NORMALIZED, Set.of(DataQualityStatus.VERIFIED, DataQualityStatus.REVIEW_REQUIRED, DataQualityStatus.FAILED),
        DataQualityStatus.REVIEW_REQUIRED, Set.of(DataQualityStatus.VERIFIED, DataQualityStatus.REJECTED, DataQualityStatus.FAILED),
        DataQualityStatus.VERIFIED, Set.of(),
        DataQualityStatus.REJECTED, Set.of(),
        DataQualityStatus.FAILED, Set.of()
    );

    public ExtractionRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(evidenceId, "evidenceId");
        requireText(inputFingerprint, "inputFingerprint");
        Objects.requireNonNull(sourceType, "sourceType");
        requireText(parserName, "parserName");
        requireText(parserVersion, "parserVersion");
        requireText(extractorName, "extractorName");
        requireText(extractorVersion, "extractorVersion");
        requireText(promptVersion, "promptVersion");
        requireText(schemaVersion, "schemaVersion");
        Objects.requireNonNull(status, "status");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        if ((status == DataQualityStatus.VERIFIED || status == DataQualityStatus.REVIEW_REQUIRED)
            && proposedPayload == null) {
            throw new IllegalArgumentException(status + " run requires proposedPayload");
        }
        if (status == DataQualityStatus.FAILED && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("FAILED run requires errorCode");
        }
    }

    public ExtractionRun transitionTo(DataQualityStatus target, Instant at) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(at, "at");
        if (!ALLOWED.get(status).contains(target)) {
            throw new IllegalStateException("Cannot transition extraction run from " + status + " to " + target);
        }
        Instant completion = switch (target) {
            case REVIEW_REQUIRED, VERIFIED, REJECTED, FAILED -> at;
            default -> completedAt;
        };
        return new ExtractionRun(
            id, evidenceId, organizationId, recruitmentEventId, inputFingerprint, sourceType,
            parserName, parserVersion, extractorName, extractorVersion, modelName, promptVersion,
            schemaVersion, target, confidence, proposedPayload, modelResponse, errorCode, errorMessage,
            startedAt, completion);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
