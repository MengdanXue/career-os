package com.careeros.domain;

import com.careeros.domain.DomainEnums.DigestReason;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 一天的变化摘要（产品需求 §8 每日增量监控、§10.7）。
 *
 * <p>只承载四类值得打扰人的事：新增、变更、下线、截止临近。未变化的岗位**不会**出现在
 * {@code entries} 里——它们只体现为 {@code suppressedUnchangedCount} 这个计数，用来说明
 * "今天扫了多少条但没什么可说的"，而不是把它们混进推送列表。
 */
public record DailyDigest(
    LocalDate reportDate,
    List<Entry> entries,
    int suppressedUnchangedCount,
    int deadlineWindowDays
) {
    public DailyDigest {
        Objects.requireNonNull(reportDate, "reportDate");
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (suppressedUnchangedCount < 0) throw new IllegalArgumentException("suppressed count must not be negative");
        if (deadlineWindowDays < 1) throw new IllegalArgumentException("deadlineWindowDays must be positive");
        long distinctJobs = entries.stream().map(Entry::jobPostingId).distinct().count();
        if (distinctJobs != entries.size()) {
            throw new IllegalArgumentException("a job must appear at most once in a digest");
        }
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    public Map<DigestReason, List<Entry>> byReason() {
        return entries.stream().collect(Collectors.groupingBy(Entry::reason));
    }

    /**
     * 一条值得推送的变化。{@code daysUntilDeadline} 对每条都填——即使推送原因是“新增”，
     * 读者也需要立刻看到还剩几天；报名已截止或日期未知时为 null。
     *
     * <p>这里不带推荐等级：主干把它放在候选人维度的 DecisionAssessment 上，
     * 而摘要报的是岗位变化，与具体候选人无关。要把两者合起来是一次独立的设计改动。
     */
    public record Entry(
        UUID jobPostingId,
        DigestReason reason,
        String title,
        String organizationName,
        LocalDate applicationEndsOn,
        Integer daysUntilDeadline,
        String note
    ) {
        public Entry {
            Objects.requireNonNull(jobPostingId, "jobPostingId");
            Objects.requireNonNull(reason, "reason");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
            if (note == null || note.isBlank()) throw new IllegalArgumentException("note is required");
            if (reason == DigestReason.DEADLINE_APPROACHING && daysUntilDeadline == null) {
                throw new IllegalArgumentException("a deadline reminder requires days until the deadline");
            }
        }
    }
}
