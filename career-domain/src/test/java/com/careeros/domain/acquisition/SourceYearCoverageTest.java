package com.careeros.domain.acquisition;

import static com.careeros.domain.acquisition.SourceYearCoverage.CoverageStatus.ACCESS_FAILED;
import static com.careeros.domain.acquisition.SourceYearCoverage.CoverageStatus.COMPLETE;
import static com.careeros.domain.acquisition.SourceYearCoverage.CoverageStatus.NO_TARGET_RECORDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceYearCoverageTest {
    @Test
    void partialCoverageRetainsTraversalAndFailureEvidence() {
        var coverage = new SourceYearCoverage(
            UUID.randomUUID(), 2024, SourceYearCoverage.CoverageStatus.PARTIAL,
            12, 11, 10, 3, null, null, Instant.parse("2026-08-24T12:00:00Z"),
            4, 6, 1, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 12, 1),
            "DOCUMENT_FAILURE");

        assertThat(coverage.listingPageCount()).isEqualTo(4);
        assertThat(coverage.failedCount()).isEqualTo(1);
        assertThat(coverage.supportsAbsenceConclusion()).isFalse();
    }

    private static final UUID SOURCE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000301");
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    void onlyCompletedDiscoveryCanSupportAnAbsenceConclusion() {
        assertThat(coverage(ACCESS_FAILED, 0, null).supportsAbsenceConclusion()).isFalse();
        assertThat(coverage(COMPLETE, 3, "年度索引3份公告均已解析").supportsAbsenceConclusion()).isFalse();
        assertThat(coverage(COMPLETE, 3, "年度索引3份公告均已解析").hasLegacyCompletionRecord()).isTrue();
        assertThat(coverage(NO_TARGET_RECORDS, 0, "年度索引已核对，无目标技术岗位").supportsAbsenceConclusion()).isFalse();
    }

    @Test
    void rejectsNoTargetStatusWhenTargetJobsWereActuallyParsed() {
        assertThatThrownBy(() -> coverage(NO_TARGET_RECORDS, 1, "错误的完成依据"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("targetJobCount");
    }

    private static SourceYearCoverage coverage(
        SourceYearCoverage.CoverageStatus status,
        int targetJobCount,
        String completionBasis
    ) {
        return new SourceYearCoverage(
            SOURCE_ID, 2025, status, 3, 3, 3, targetJobCount,
            completionBasis, status == COMPLETE || status == NO_TARGET_RECORDS ? NOW : null, NOW
        );
    }
}
