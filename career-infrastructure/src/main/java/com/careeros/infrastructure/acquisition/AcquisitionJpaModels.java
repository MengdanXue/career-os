package com.careeros.infrastructure.acquisition;

import com.careeros.domain.acquisition.AcquiredDocument.DocumentKind;
import com.careeros.domain.acquisition.AcquiredDocument.DocumentState;
import com.careeros.domain.acquisition.AcquisitionChange.ChangeType;
import com.careeros.domain.acquisition.ArtifactImportFailure.FailureStage;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import com.careeros.domain.acquisition.SourceYearCoverage.CoverageStatus;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint.CheckpointStatus;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

final class AcquisitionJpaModels {
    private AcquisitionJpaModels() {}

    @Entity(name = "RecruitmentSourceEntity") @Table(name = "recruitment_source")
    static class RecruitmentSourceEntity {
        @Id UUID id;
        @Column(nullable = false, unique = true) String code;
        @Column(nullable = false) String name;
        @Column(name = "base_uri", nullable = false) String baseUri;
        @Column(name = "entry_uri", nullable = false) String entryUri;
        @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false) SourceType sourceType;
        @Column(nullable = false) String region;
        @Enumerated(EnumType.STRING) @Column(name = "crawl_mode", nullable = false) CrawlMode crawlMode;
        @Column(nullable = false) boolean enabled;
        @Column(name = "cron_expression", nullable = false) String cronExpression;
        @Column(name = "time_zone", nullable = false) String timeZone;
        @Column(name = "minimum_request_interval_ms", nullable = false) long minimumRequestIntervalMs;
        @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
        Map<String, Object> configuration = new LinkedHashMap<>();
        @Column(name = "last_success_at") Instant lastSuccessAt;
        @Column(name = "last_failure_at") Instant lastFailureAt;
        @Column(name = "next_due_at") Instant nextDueAt;
        @Column(name = "consecutive_failure_count", nullable = false) int consecutiveFailureCount;
        @Column(name = "created_at", nullable = false) Instant createdAt;
        @Column(name = "updated_at", nullable = false) Instant updatedAt;
        protected RecruitmentSourceEntity() {}
    }

    @Entity(name = "SourceCrawlRunEntity") @Table(name = "source_crawl_run")
    static class SourceCrawlRunEntity {
        @Id UUID id;
        @Column(name = "source_id", nullable = false) UUID sourceId;
        @Enumerated(EnumType.STRING) @Column(name = "trigger_type", nullable = false) RunTrigger trigger;
        @Enumerated(EnumType.STRING) @Column(nullable = false) RunStatus status;
        @Column(name = "started_at", nullable = false) Instant startedAt;
        @Column(name = "completed_at") Instant completedAt;
        @Column(name = "discovered_count", nullable = false) int discoveredCount;
        @Column(name = "fetched_count", nullable = false) int fetchedCount;
        @Column(name = "unchanged_count", nullable = false) int unchangedCount;
        @Column(name = "added_count", nullable = false) int addedCount;
        @Column(name = "updated_count", nullable = false) int updatedCount;
        @Column(name = "deactivated_count", nullable = false) int deactivatedCount;
        @Column(name = "failed_count", nullable = false) int failedCount;
        @Column(name = "error_code") String errorCode;
        @Column(name = "error_message") String errorMessage;
        protected SourceCrawlRunEntity() {}
    }

    @Entity(name = "AcquiredDocumentEntity") @Table(name = "acquired_document")
    static class AcquiredDocumentEntity {
        @Id UUID id;
        @Column(name = "source_id", nullable = false) UUID sourceId;
        @Column(name = "canonical_uri", nullable = false) String canonicalUri;
        @Column(name = "parent_document_id") UUID parentDocumentId;
        @Enumerated(EnumType.STRING) @Column(name = "document_kind", nullable = false) DocumentKind kind;
        @Column(name = "media_type", nullable = false) String mediaType;
        @Column(name = "content_fingerprint", nullable = false, length = 64) String contentFingerprint;
        @Column String etag;
        @Column(name = "last_modified") String lastModified;
        @Column(name = "storage_uri", nullable = false) String storageUri;
        @Enumerated(EnumType.STRING) @Column(name = "document_state", nullable = false) DocumentState state;
        @Column(name = "first_seen_at", nullable = false) Instant firstSeenAt;
        @Column(name = "last_seen_at", nullable = false) Instant lastSeenAt;
        @Column(name = "last_changed_at", nullable = false) Instant lastChangedAt;
        @Column(name = "last_gone_at") Instant lastGoneAt;
        @Column(name = "consecutive_gone_count", nullable = false) int consecutiveGoneCount;
        @Column(name = "last_http_status", nullable = false) int lastHttpStatus;
        @Column(name = "last_processed_fingerprint", length = 64) String lastProcessedFingerprint;
        @Column(name = "last_processor_version") String lastProcessorVersion;
        @Version @Column(nullable = false) long version;
        protected AcquiredDocumentEntity() {}
    }

    @Entity(name = "AcquisitionChangeEntity") @Table(name = "acquisition_change")
    static class AcquisitionChangeEntity {
        @Id UUID id;
        @Column(name = "run_id", nullable = false) UUID runId;
        @Column(name = "source_id", nullable = false) UUID sourceId;
        @Column(name = "document_id") UUID documentId;
        @Enumerated(EnumType.STRING) @Column(name = "change_type", nullable = false) ChangeType changeType;
        @Column(name = "previous_fingerprint", length = 64) String previousFingerprint;
        @Column(name = "current_fingerprint", nullable = false, length = 64) String currentFingerprint;
        @Column(name = "canonical_uri", nullable = false) String canonicalUri;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "job_delta_summary", nullable = false, columnDefinition = "jsonb")
        Map<String, Object> jobDeltaSummary = new LinkedHashMap<>();
        @Column(name = "occurred_at", nullable = false) Instant occurredAt;
        protected AcquisitionChangeEntity() {}
    }

    @Embeddable
    static class SourceYearCoverageId implements Serializable {
        @Column(name = "source_id", nullable = false) UUID sourceId;
        @Column(name = "recruitment_year", nullable = false) int recruitmentYear;
        protected SourceYearCoverageId() {}
        SourceYearCoverageId(UUID sourceId, int recruitmentYear) {
            this.sourceId = sourceId;
            this.recruitmentYear = recruitmentYear;
        }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof SourceYearCoverageId value
                && recruitmentYear == value.recruitmentYear && java.util.Objects.equals(sourceId, value.sourceId);
        }
        @Override public int hashCode() { return java.util.Objects.hash(sourceId, recruitmentYear); }
    }

    @Entity(name = "SourceYearCoverageEntity") @Table(name = "source_year_coverage")
    static class SourceYearCoverageEntity {
        @EmbeddedId SourceYearCoverageId id;
        @Enumerated(EnumType.STRING) @Column(nullable = false) CoverageStatus status;
        @Column(name = "discovered_count", nullable = false) int discoveredCount;
        @Column(name = "fetched_count", nullable = false) int fetchedCount;
        @Column(name = "parsed_count", nullable = false) int parsedCount;
        @Column(name = "target_job_count", nullable = false) int targetJobCount;
        @Column(name = "completion_basis") String completionBasis;
        @Column(name = "completed_at") Instant completedAt;
        @Column(name = "updated_at", nullable = false) Instant updatedAt;
        @Column(name = "listing_page_count", nullable = false) int listingPageCount;
        @Column(name = "filtered_count", nullable = false) int filteredCount;
        @Column(name = "failed_count", nullable = false) int failedCount;
        @Column(name = "earliest_published_on") LocalDate earliestPublishedOn;
        @Column(name = "latest_published_on") LocalDate latestPublishedOn;
        @Column(name = "stop_reason") String stopReason;
        protected SourceYearCoverageEntity() {}
    }

    @Embeddable
    static class SourceOnboardingCheckpointId implements Serializable {
        @Column(name = "source_id", nullable = false) UUID sourceId;
        @Enumerated(EnumType.STRING) @Column(nullable = false) Checkpoint checkpoint;
        protected SourceOnboardingCheckpointId() {}
        SourceOnboardingCheckpointId(UUID sourceId, Checkpoint checkpoint) {
            this.sourceId = sourceId;
            this.checkpoint = checkpoint;
        }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof SourceOnboardingCheckpointId value
                && java.util.Objects.equals(sourceId, value.sourceId) && checkpoint == value.checkpoint;
        }
        @Override public int hashCode() { return java.util.Objects.hash(sourceId, checkpoint); }
    }

    @Entity(name = "SourceOnboardingCheckpointEntity") @Table(name = "source_onboarding_checkpoint")
    static class SourceOnboardingCheckpointEntity {
        @EmbeddedId SourceOnboardingCheckpointId id;
        @Enumerated(EnumType.STRING) @Column(nullable = false) CheckpointStatus status;
        @Column String evidence;
        @Column(name = "verified_at", nullable = false) Instant verifiedAt;
        protected SourceOnboardingCheckpointEntity() {}
    }

    @Entity(name = "ArtifactImportFailureEntity") @Table(name = "artifact_import_failure")
    static class ArtifactImportFailureEntity {
        @Id UUID id;
        @Column(name = "run_id", nullable = false) UUID runId;
        @Column(name = "source_id", nullable = false) UUID sourceId;
        @Column(name = "document_id", nullable = false) UUID documentId;
        @Enumerated(EnumType.STRING) @Column(nullable = false) FailureStage stage;
        @Column(name = "sheet_name") String sheetName;
        @Column(name = "row_number") Integer rowNumber;
        @Column(name = "error_code", nullable = false) String errorCode;
        @Column(name = "safe_message", nullable = false, length = 500) String safeMessage;
        @Column(name = "occurred_at", nullable = false) Instant occurredAt;
        protected ArtifactImportFailureEntity() {}
    }
}
