package com.careeros;

import com.careeros.application.AcquisitionPorts.ChangeCursor;
import com.careeros.application.AcquisitionPorts.ChangePage;
import com.careeros.application.AcquisitionPorts.RunPage;
import com.careeros.domain.acquisition.AcquisitionChange;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.SourceCrawlRun;
import com.careeros.domain.acquisition.SourceYearCoverage;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint;
import com.careeros.domain.acquisition.ArtifactImportFailure;
import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.application.AcquisitionPorts.TargetSourceRegistration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class AcquisitionApiModels {
    private AcquisitionApiModels() {}

    record SourceResponse(
        UUID id, String code, String name, String entryUri, String sourceType, String region,
        String crawlMode, boolean enabled, String cronExpression, String timeZone,
        Instant lastSuccessAt, Instant lastFailureAt, Instant nextDueAt, int consecutiveFailureCount,
        String connectionStatus, String scopeLevel, String scopeCode, String priorityTier,
        String coverageRole, String accessStatus, long documentIssueCount,
        long lifecycleDocumentCount, long matchedLifecycleCount,
        long unmatchedLifecycleCount, long ambiguousLifecycleCount,
        List<CoverageResponse> coverage,
        List<CheckpointResponse> checkpoints, long historicalFailureCount,
        java.util.Map<String,Object> completion
    ) {
        SourceResponse withCompletion(java.util.Map<String,Object> value) {
            return new SourceResponse(id, code, name, entryUri, sourceType, region, crawlMode, enabled,
                cronExpression, timeZone, lastSuccessAt, lastFailureAt, nextDueAt, consecutiveFailureCount,
                connectionStatus, scopeLevel, scopeCode, priorityTier, coverageRole, accessStatus,
                documentIssueCount, lifecycleDocumentCount, matchedLifecycleCount, unmatchedLifecycleCount,
                ambiguousLifecycleCount, coverage, checkpoints, historicalFailureCount, value);
        }

        static SourceResponse from(
            RecruitmentSource value, TargetSourceRegistration target, AcquisitionStore store
        ) {
            if (value == null) {
                return new SourceResponse(null, target.code(), target.name(), target.officialRootUrl(),
                    "UNCONFIGURED", target.region(), "UNCONFIGURED", false, null, null,
                    null, null, null, 0, target.connectionStatus().name(), target.scopeLevel(),
                    target.scopeCode(), target.priorityTier(), target.coverageRole(), "NOT_CONFIGURED", 0,
                    0, 0, 0, 0,
                    List.of(), List.of(), 0, null);
            }
            long documentIssues = store.countDocumentImportFailures(value.id());
            var lifecycle = store.lifecycleCounts(value.id());
            return new SourceResponse(value.id(),value.code(),value.name(),value.entryUri().toString(),
                value.sourceType().name(),value.region(),value.crawlMode().name(),value.enabled(),
                value.cronExpression(),value.timeZone(),value.lastSuccessAt(),value.lastFailureAt(),
                value.nextDueAt(),value.consecutiveFailureCount(),
                target.connectionStatus().name(), target.scopeLevel(), target.scopeCode(),
                target.priorityTier(), target.coverageRole(), accessStatus(value, store), documentIssues,
                lifecycle.documents(), lifecycle.matched(), lifecycle.unmatched(), lifecycle.ambiguous(),
                store.findSourceYearCoverage(value.id(), null).stream()
                    .sorted(java.util.Comparator.comparingInt(SourceYearCoverage::recruitmentYear))
                    .map(CoverageResponse::from).toList(),
                store.findCheckpoints(value.id()).stream().map(CheckpointResponse::from).toList(),
                store.countImportFailures(value.id()), null);
        }

        private static String accessStatus(RecruitmentSource value, AcquisitionStore store) {
            if (value.lastSuccessAt() != null) return "ACCESSIBLE";
            return store.findLatestRun(value.id())
                .map(run -> run.status() == com.careeros.domain.acquisition.SourceCrawlRun.RunStatus.FAILED
                    ? "ACCESS_FAILED" : "ACCESSIBLE")
                .orElse("UNKNOWN");
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

    record CoverageResponse(
        UUID sourceId, int year, String status, int discoveredCount, int fetchedCount,
        int parsedCount, int targetJobCount, String completionBasis, Instant completedAt,
        Instant updatedAt, boolean supportsAbsenceConclusion, int listingPageCount,
        int filteredCount, int failedCount, LocalDate earliestPublishedOn,
        LocalDate latestPublishedOn, String stopReason, java.util.Map<String,Object> assessment
    ) {
        static CoverageResponse from(SourceYearCoverage value) {
            return new CoverageResponse(value.sourceId(), value.recruitmentYear(), value.status().name(),
                value.discoveredCount(), value.fetchedCount(), value.parsedCount(), value.targetJobCount(),
                value.completionBasis(), value.completedAt(), value.updatedAt(), value.supportsAbsenceConclusion(),
                value.listingPageCount(), value.filteredCount(), value.failedCount(),
                value.earliestPublishedOn(), value.latestPublishedOn(), value.stopReason(), null);
        }

        CoverageResponse withAssessment(java.util.Map<String,Object> value) {
            return new CoverageResponse(sourceId, year, status, discoveredCount, fetchedCount, parsedCount,
                targetJobCount, completionBasis, completedAt, updatedAt, supportsAbsenceConclusion,
                listingPageCount, filteredCount, failedCount, earliestPublishedOn, latestPublishedOn, stopReason,
                value);
        }
    }

    record CheckpointResponse(
        UUID sourceId, String checkpoint, String status, String evidence, Instant verifiedAt
    ) {
        static CheckpointResponse from(SourceOnboardingCheckpoint value) {
            return new CheckpointResponse(value.sourceId(), value.checkpoint().name(), value.status().name(),
                value.evidence(), value.verifiedAt());
        }
    }

    record FailureResponse(
        UUID id, UUID runId, UUID sourceId, UUID documentId, String stage,
        String sheetName, Integer rowNumber, String errorCode, String safeMessage, Instant occurredAt
    ) {
        static FailureResponse from(ArtifactImportFailure value) {
            return new FailureResponse(value.id(), value.runId(), value.sourceId(), value.documentId(),
                value.stage().name(), value.sheetName(), value.rowNumber(), value.errorCode(),
                value.safeMessage(), value.occurredAt());
        }
    }

    record HistoricalRunResponse(RunResponse run, List<CoverageResponse> coverage) {}

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
