package com.careeros.domain;

import com.careeros.domain.DomainEnums.RecurrenceSignal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 一条岗位族对目标年度（如 2027）的再现信号（产品需求 §5 OpportunityHistory / Forecast）。
 *
 * <p>§11 把"没有历史样本支撑的精确上岸概率"列为非目标，因此这里**没有概率字段**，只有一个
 * 可观测的模式加上它的依据。{@code observationWindow} 公开这个结论是在多长的历史窗口里得出的——
 * 只有一年数据时任何"连续招聘"的说法都不成立，信号必须是 INSUFFICIENT_HISTORY。
 */
public record OpportunityForecast(
    String lineageKey,
    int targetYear,
    RecurrenceSignal signal,
    String rationale,
    SortedSet<Integer> observedYears,
    int observationWindowStartYear,
    int observationWindowEndYear,
    List<UUID> evidenceIds
) {
    public OpportunityForecast {
        if (lineageKey == null || lineageKey.isBlank()) throw new IllegalArgumentException("lineageKey is required");
        Objects.requireNonNull(signal, "signal");
        if (rationale == null || rationale.isBlank()) throw new IllegalArgumentException("rationale is required");
        if (observationWindowEndYear < observationWindowStartYear) {
            throw new IllegalArgumentException("observation window ends before it starts");
        }
        observedYears = observedYears == null
            ? Collections.unmodifiableSortedSet(new TreeSet<>())
            : Collections.unmodifiableSortedSet(new TreeSet<>(observedYears));
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (signal != RecurrenceSignal.INSUFFICIENT_HISTORY && observedYears.isEmpty()) {
            throw new IllegalArgumentException("a non-trivial signal requires at least one observed year");
        }
    }

    /** 观测窗口跨了几年。少于 2 年就谈不上"再现"。 */
    public int observationWindowSpan() {
        return observationWindowEndYear - observationWindowStartYear + 1;
    }
}
