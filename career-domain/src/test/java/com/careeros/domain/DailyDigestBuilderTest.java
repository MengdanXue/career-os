package com.careeros.domain;

import com.careeros.domain.DomainEnums.*;
import com.careeros.domain.DailyDigestBuilder.DigestInput;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailyDigestBuilderTest {
    private final DailyDigestBuilder builder = new DailyDigestBuilder();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 6);

    /** §10.7 的核心约束：未变化且截止日期还远的岗位绝不出现在推送里。 */
    @Test void unchangedPostingsWithDistantDeadlinesAreNeverPushed() {
        var digest = builder.build(List.of(
            input("未变化岗位", LocalDate.of(2026, 12, 1), JobChangeKind.UNCHANGED),
            input("另一个未变化岗位", null, JobChangeKind.UNCHANGED)), TODAY);

        assertThat(digest.entries()).isEmpty();
        assertThat(digest.isEmpty()).isTrue();
        // 但要说明今天确实扫过它们
        assertThat(digest.suppressedUnchangedCount()).isEqualTo(2);
    }

    @Test void newUpdatedAndDeactivatedAreAllReported() {
        var digest = builder.build(List.of(
            input("新增岗位", null, JobChangeKind.NEW),
            input("变更岗位", null, JobChangeKind.UPDATED),
            input("下线岗位", null, JobChangeKind.DEACTIVATED)), TODAY);

        assertThat(digest.entries()).hasSize(3);
        assertThat(digest.byReason()).containsOnlyKeys(
            DigestReason.NEW, DigestReason.UPDATED, DigestReason.DEACTIVATED);
        assertThat(digest.suppressedUnchangedCount()).isZero();
    }

    /** 未变化的岗位只有一个理由值得再提：报名快截止了。 */
    @Test void unchangedPostingsSurfaceOnlyWhenTheDeadlineIsNear() {
        var digest = builder.build(List.of(
            input("快截止", TODAY.plusDays(3), JobChangeKind.UNCHANGED),
            input("还很远", TODAY.plusDays(30), JobChangeKind.UNCHANGED)), TODAY);

        assertThat(digest.entries()).hasSize(1);
        assertThat(digest.entries().getFirst().reason()).isEqualTo(DigestReason.DEADLINE_APPROACHING);
        assertThat(digest.entries().getFirst().title()).isEqualTo("快截止");
        assertThat(digest.entries().getFirst().daysUntilDeadline()).isEqualTo(3);
    }

    @Test void theDeadlineWindowBoundaryIsInclusive() {
        int window = DailyDigestBuilder.DEFAULT_DEADLINE_WINDOW_DAYS;
        var onBoundary = builder.build(List.of(
            input("边界", TODAY.plusDays(window), JobChangeKind.UNCHANGED)), TODAY);
        var pastBoundary = builder.build(List.of(
            input("界外", TODAY.plusDays(window + 1), JobChangeKind.UNCHANGED)), TODAY);

        assertThat(onBoundary.entries()).hasSize(1);
        assertThat(pastBoundary.entries()).isEmpty();
    }

    /** 已经截止的岗位不该再推"还剩 -3 天"。 */
    @Test void expiredDeadlinesAreNotReminders() {
        var digest = builder.build(List.of(
            input("已截止", TODAY.minusDays(3), JobChangeKind.UNCHANGED)), TODAY);
        assertThat(digest.entries()).isEmpty();
        assertThat(digest.suppressedUnchangedCount()).isEqualTo(1);
    }

    /** 变化本身优先于截止提醒，但剩余天数仍要带上，读者不因分类漏掉紧迫性。 */
    @Test void aChangedPostingKeepsItsDeadlineUrgency() {
        var digest = builder.build(List.of(
            input("新增且快截止", TODAY.plusDays(2), JobChangeKind.NEW)), TODAY);

        var entry = digest.entries().getFirst();
        assertThat(entry.reason()).isEqualTo(DigestReason.NEW);
        assertThat(entry.daysUntilDeadline()).isEqualTo(2);
        assertThat(entry.note()).contains("还剩 2 天");
    }

    @Test void everyEntryAppearsAtMostOncePerJob() {
        UUID jobId = UUID.randomUUID();
        assertThatThrownBy(() -> new DailyDigest(TODAY, List.of(
            entry(jobId, DigestReason.NEW), entry(jobId, DigestReason.DEADLINE_APPROACHING)), 0, 7))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at most once");
    }

    @Test void entriesAreOrderedByReasonThenUrgency() {
        var digest = builder.build(List.of(
            input("下线的", null, JobChangeKind.DEACTIVATED),
            input("新增晚截止", TODAY.plusDays(20), JobChangeKind.NEW),
            input("新增早截止", TODAY.plusDays(2), JobChangeKind.NEW),
            input("变更的", null, JobChangeKind.UPDATED)), TODAY);

        assertThat(digest.entries()).extracting(DailyDigest.Entry::title)
            .containsExactly("新增早截止", "新增晚截止", "变更的", "下线的");
    }

    @Test void aDeadlineReminderRequiresItsRemainingDays() {
        assertThatThrownBy(() -> new DailyDigest.Entry(UUID.randomUUID(), DigestReason.DEADLINE_APPROACHING,
            "岗位", "单位", null, null, null, "说明"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void anEmptyScanProducesAnEmptyDigestRatherThanFailing() {
        var digest = builder.build(List.of(), TODAY);
        assertThat(digest.isEmpty()).isTrue();
        assertThat(digest.reportDate()).isEqualTo(TODAY);
    }

    private static DailyDigest.Entry entry(UUID jobId, DigestReason reason) {
        return new DailyDigest.Entry(jobId, reason, "岗位", "单位", null, 1, null, "说明");
    }

    private DigestInput input(String title, LocalDate endsOn, JobChangeKind kind) {
        return new DigestInput(UUID.randomUUID(), title, "杭州市测试单位", endsOn, kind, null);
    }
}
