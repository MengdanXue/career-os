package com.careeros.domain;

import com.careeros.domain.DomainEnums.DigestReason;
import com.careeros.domain.DomainEnums.JobChangeKind;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 由一次增量入库的结果生成每日摘要（产品需求 §8、§10.7）。
 *
 * <p>规则只有一条核心：**未变化且截止日期还远的岗位不推送**。每个岗位最多出现一次，
 * 变化本身优先于截止提醒——一条今天刚新增、后天截止的岗位报为"新增"，但仍然带着
 * 剩余天数，读者不会因为分类而漏掉紧迫性。
 */
public final class DailyDigestBuilder {
    public static final String VERSION = "phase2-digest-v1";

    /** 默认提前多少天开始提醒报名截止。 */
    public static final int DEFAULT_DEADLINE_WINDOW_DAYS = 7;

    /** 建摘要所需的一条输入：岗位、单位名、报名截止日、本次变化类型。 */
    public record DigestInput(
        UUID jobPostingId,
        String title,
        String organizationName,
        LocalDate applicationEndsOn,
        JobChangeKind changeKind
    ) {
        public DigestInput {
            Objects.requireNonNull(jobPostingId, "jobPostingId");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
            Objects.requireNonNull(changeKind, "changeKind");
        }
    }

    public DailyDigest build(List<DigestInput> inputs, LocalDate reportDate) {
        return build(inputs, reportDate, DEFAULT_DEADLINE_WINDOW_DAYS);
    }

    public DailyDigest build(List<DigestInput> inputs, LocalDate reportDate, int deadlineWindowDays) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(reportDate, "reportDate");
        if (deadlineWindowDays < 1) throw new IllegalArgumentException("deadlineWindowDays must be positive");

        var entries = new ArrayList<DailyDigest.Entry>();
        int suppressed = 0;
        for (DigestInput input : inputs) {
            Integer daysLeft = daysUntilDeadline(input.applicationEndsOn(), reportDate);
            DigestReason reason = reasonFor(input, daysLeft, deadlineWindowDays);
            if (reason == null) {
                suppressed++;
                continue;
            }
            entries.add(new DailyDigest.Entry(
                input.jobPostingId(), reason, input.title(), input.organizationName(),
                input.applicationEndsOn(), daysLeft, note(reason, daysLeft)));
        }
        entries.sort(Comparator
            .comparingInt((DailyDigest.Entry entry) -> entry.reason().ordinal())
            .thenComparing(entry -> entry.daysUntilDeadline() == null ? Integer.MAX_VALUE : entry.daysUntilDeadline())
            .thenComparing(DailyDigest.Entry::title));
        return new DailyDigest(reportDate, entries, suppressed, deadlineWindowDays);
    }

    /** 返回 null 表示这条不值得推送。 */
    private static DigestReason reasonFor(DigestInput input, Integer daysLeft, int windowDays) {
        return switch (input.changeKind()) {
            case NEW -> DigestReason.NEW;
            case UPDATED -> DigestReason.UPDATED;
            case DEACTIVATED -> DigestReason.DEACTIVATED;
            // 未变化的岗位只有一个理由值得再提：报名快截止了。
            case UNCHANGED -> daysLeft != null && daysLeft <= windowDays
                ? DigestReason.DEADLINE_APPROACHING
                : null;
        };
    }

    /** 已过截止日或日期未知时返回 null——过期的岗位不需要"还剩 -3 天"这种提醒。 */
    private static Integer daysUntilDeadline(LocalDate applicationEndsOn, LocalDate reportDate) {
        if (applicationEndsOn == null) return null;
        long days = ChronoUnit.DAYS.between(reportDate, applicationEndsOn);
        return days < 0 ? null : (int) days;
    }

    private static String note(DigestReason reason, Integer daysLeft) {
        String deadline = daysLeft == null ? "" : "，报名还剩 " + daysLeft + " 天";
        return switch (reason) {
            case NEW -> "本次采集新增岗位" + deadline;
            case UPDATED -> "岗位内容较上次采集有变化，需重新确认硬条件" + deadline;
            case DEACTIVATED -> "岗位在最新的完整公告快照中已消失，可能已下线";
            case DEADLINE_APPROACHING -> "岗位内容未变，但报名还剩 " + daysLeft + " 天";
        };
    }
}
