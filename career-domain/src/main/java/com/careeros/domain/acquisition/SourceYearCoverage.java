package com.careeros.domain.acquisition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record SourceYearCoverage(
    UUID sourceId,
    int recruitmentYear,
    CoverageStatus status,
    int discoveredCount,
    int fetchedCount,
    int parsedCount,
    int targetJobCount,
    String completionBasis,
    Instant completedAt,
    Instant updatedAt,
    int listingPageCount,
    int filteredCount,
    int failedCount,
    LocalDate earliestPublishedOn,
    LocalDate latestPublishedOn,
    String stopReason
) {
    public enum CoverageStatus {
        NOT_DISCOVERED,
        DISCOVERED_NOT_FETCHED,
        ACCESS_FAILED,
        FETCHED_NOT_PARSED,
        PARTIAL,
        COMPLETE,
        NO_TARGET_RECORDS
    }

    public SourceYearCoverage {
        Objects.requireNonNull(sourceId, "sourceId");
        if (recruitmentYear < 2000 || recruitmentYear > 2100) {
            throw new IllegalArgumentException("recruitmentYear is invalid");
        }
        Objects.requireNonNull(status, "status");
        requireNonNegative(discoveredCount, "discoveredCount");
        requireNonNegative(fetchedCount, "fetchedCount");
        requireNonNegative(parsedCount, "parsedCount");
        requireNonNegative(targetJobCount, "targetJobCount");
        requireNonNegative(listingPageCount, "listingPageCount");
        requireNonNegative(filteredCount, "filteredCount");
        requireNonNegative(failedCount, "failedCount");
        completionBasis = optional(completionBasis);
        stopReason = optional(stopReason);
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (earliestPublishedOn != null && latestPublishedOn != null
            && earliestPublishedOn.isAfter(latestPublishedOn)) {
            throw new IllegalArgumentException("earliestPublishedOn cannot be after latestPublishedOn");
        }
        if (hasLegacyCompletionRecord(status)) {
            if (completionBasis == null) throw new IllegalArgumentException("completionBasis is required");
            if (completedAt == null) throw new IllegalArgumentException("completedAt is required");
        } else if (completedAt != null) {
            throw new IllegalArgumentException("completedAt requires completed coverage");
        }
        if (status == CoverageStatus.NO_TARGET_RECORDS && targetJobCount != 0) {
            throw new IllegalArgumentException("targetJobCount must be zero for NO_TARGET_RECORDS");
        }
    }

    public SourceYearCoverage(
        UUID sourceId,
        int recruitmentYear,
        CoverageStatus status,
        int discoveredCount,
        int fetchedCount,
        int parsedCount,
        int targetJobCount,
        String completionBasis,
        Instant completedAt,
        Instant updatedAt
    ) {
        this(sourceId, recruitmentYear, status, discoveredCount, fetchedCount, parsedCount,
            targetJobCount, completionBasis, completedAt, updatedAt, 0, 0, 0,
            null, null, null);
    }

    /**
     * Whether this legacy operational row carries the fields required by its
     * historical completion contract.  This is deliberately separate from the
     * six-level audit conclusion: an old COMPLETE row is an observation, not
     * independently assessed evidence that a source/year had no opportunities.
     */
    public boolean hasLegacyCompletionRecord() { return hasLegacyCompletionRecord(status); }

    /** Legacy rows are never sufficient for the independent audit absence gate. */
    public boolean supportsAbsenceConclusion() { return false; }

    private static boolean hasLegacyCompletionRecord(CoverageStatus value) {
        return value == CoverageStatus.COMPLETE || value == CoverageStatus.NO_TARGET_RECORDS;
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " cannot be negative");
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
