package com.careeros.infrastructure.extraction;

import com.careeros.domain.DomainEnums.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

final class ExtractionJpaModels {
    private ExtractionJpaModels() {}

    @MappedSuperclass
    abstract static class UuidEntity {
        @Id @Column(nullable = false, updatable = false) UUID id;
    }

    @Entity @Table(name = "source_artifact")
    static class SourceArtifactEntity extends UuidEntity {
        @Column(nullable = false, unique = true, length = 64) String sha256;
        @Column(name = "media_type", nullable = false) String mediaType;
        @Column(name = "size_bytes", nullable = false) long sizeBytes;
        @Column(name = "storage_uri", nullable = false) String storageUri;
        @Column(name = "captured_at", nullable = false) Instant capturedAt;
        protected SourceArtifactEntity() {}
    }

    @Entity @Table(name = "evidence_fragment")
    static class EvidenceFragmentEntity extends UuidEntity {
        @Column(name = "evidence_id", nullable = false) UUID evidenceId;
        @Enumerated(EnumType.STRING) @Column(name = "locator_type", nullable = false) LocatorType locatorType;
        @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
        Map<String, Object> locator = new LinkedHashMap<>();
        @Column(name = "verbatim_text", nullable = false) String verbatimText;
        @Column(name = "content_hash", nullable = false, length = 64) String contentHash;
        @Column(name = "created_at", nullable = false) Instant createdAt;
        protected EvidenceFragmentEntity() {}
    }

    @Entity @Table(name = "extraction_run")
    static class ExtractionRunEntity extends UuidEntity {
        @Column(name = "evidence_id", nullable = false) UUID evidenceId;
        @Column(name = "organization_id") UUID organizationId;
        @Column(name = "recruitment_event_id") UUID recruitmentEventId;
        @Column(name = "input_fingerprint", nullable = false, unique = true, length = 64) String inputFingerprint;
        @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false) ExtractionSourceType sourceType;
        @Column(name = "parser_name", nullable = false) String parserName;
        @Column(name = "parser_version", nullable = false) String parserVersion;
        @Column(name = "extractor_name", nullable = false) String extractorName;
        @Column(name = "extractor_version", nullable = false) String extractorVersion;
        @Column(name = "model_name") String modelName;
        @Column(name = "prompt_version") String promptVersion;
        @Column(name = "schema_version", nullable = false) String schemaVersion;
        @Enumerated(EnumType.STRING) @Column(nullable = false) DataQualityStatus status;
        @Column(nullable = false) double confidence;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "proposed_payload", nullable = false, columnDefinition = "jsonb")
        JsonNode proposedPayload;
        @Column(name = "model_response") String modelResponse;
        @Column(name = "error_code") String errorCode;
        @Column(name = "error_message") String errorMessage;
        @Column(name = "started_at", nullable = false) Instant startedAt;
        @Column(name = "completed_at") Instant completedAt;
        protected ExtractionRunEntity() {}
    }

    @Entity @Table(name = "review_item")
    static class ReviewItemEntity extends UuidEntity {
        @Column(name = "extraction_run_id", nullable = false, unique = true) UUID extractionRunId;
        @Enumerated(EnumType.STRING) @Column(nullable = false) ReviewStatus status;
        @Version @Column(nullable = false) long version;
        @Column(name = "created_at", nullable = false) Instant createdAt;
        @Column(name = "resolved_at") Instant resolvedAt;
        protected ReviewItemEntity() {}
    }

    @Entity @Table(name = "review_issue")
    static class ReviewIssueEntity extends UuidEntity {
        @Column(name = "review_item_id", nullable = false) UUID reviewItemId;
        @Enumerated(EnumType.STRING) @Column(name = "reason_code", nullable = false) ReviewReasonCode reasonCode;
        @Column(name = "field_path") String fieldPath;
        @Column(nullable = false) String message;
        @Column(name = "evidence_fragment_id") UUID evidenceFragmentId;
        protected ReviewIssueEntity() {}
    }

    @Entity @Table(name = "review_action")
    static class ReviewActionEntity extends UuidEntity {
        @Column(name = "review_item_id", nullable = false) UUID reviewItemId;
        @Enumerated(EnumType.STRING) @Column(nullable = false) ReviewDecision decision;
        @Column(name = "expected_version", nullable = false) long expectedVersion;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "original_payload", nullable = false, columnDefinition = "jsonb")
        JsonNode originalPayload;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "corrected_payload", columnDefinition = "jsonb")
        JsonNode correctedPayload;
        String note;
        @Column(name = "acted_at", nullable = false) Instant actedAt;
        protected ReviewActionEntity() {}
    }
}
