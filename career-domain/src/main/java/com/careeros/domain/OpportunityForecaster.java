package com.careeros.domain;

import com.careeros.domain.DomainEnums.RecurrenceSignal;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 由岗位族的跨年度出现记录推出目标年度的再现信号（产品需求 §5）。
 *
 * <p>产出的是模式而非概率——§11 明确把"没有历史样本支撑的精确上岸概率"列为非目标。
 * 每个结论都带上观测窗口，读者能立刻看出它是在多长的历史里得出的。
 */
public final class OpportunityForecaster {
    public static final String VERSION = "phase2-forecast-v1";

    /** 判断再现规律所需的最小观测窗口。只有一年数据时，任何"连续"的说法都不成立。 */
    static final int MINIMUM_WINDOW_SPAN = 2;

    /**
     * @param windowStartYear 已回填数据覆盖的最早招聘年度
     * @param windowEndYear   已回填数据覆盖的最晚招聘年度
     */
    public OpportunityForecast forecast(
        JobFamilyLineage lineage,
        int targetYear,
        int windowStartYear,
        int windowEndYear
    ) {
        Objects.requireNonNull(lineage, "lineage");
        if (windowEndYear < windowStartYear) {
            throw new IllegalArgumentException("observation window ends before it starts");
        }
        int span = windowEndYear - windowStartYear + 1;
        String window = "观测窗口 " + windowStartYear + "–" + windowEndYear + "（" + span + " 年）";
        String seen = lineage.observedYears().stream().map(String::valueOf).collect(Collectors.joining("、"));

        if (span < MINIMUM_WINDOW_SPAN) {
            return signal(lineage, targetYear, RecurrenceSignal.INSUFFICIENT_HISTORY,
                window + "不足以判断再现规律，已出现年份：" + seen,
                windowStartYear, windowEndYear);
        }
        if (lineage.observedYears().size() == 1) {
            return signal(lineage, targetYear, RecurrenceSignal.SINGLE_OCCURRENCE,
                window + "内仅在 " + seen + " 出现过一次，不足以支持再现判断",
                windowStartYear, windowEndYear);
        }
        if (lineage.isContiguous()) {
            return signal(lineage, targetYear, RecurrenceSignal.RECURRING_ANNUAL,
                window + "内于 " + seen + " 连续出现，" + targetYear + " 年再现的可能性值得关注"
                    + lastSeenCaveat(lineage, windowEndYear),
                windowStartYear, windowEndYear);
        }
        return signal(lineage, targetYear, RecurrenceSignal.INTERMITTENT,
            window + "内于 " + seen + " 间断出现，未构成逐年招聘规律"
                + lastSeenCaveat(lineage, windowEndYear),
            windowStartYear, windowEndYear);
    }

    /** 最近一次出现距窗口末年越远，越不该把历史规律直接套到目标年度。 */
    private static String lastSeenCaveat(JobFamilyLineage lineage, int windowEndYear) {
        int gap = windowEndYear - lineage.lastObservedYear();
        return gap <= 0 ? "" : "；最近一次出现在 " + lineage.lastObservedYear()
            + "，距窗口末年已 " + gap + " 年，需确认单位是否仍有该岗位需求";
    }

    private static OpportunityForecast signal(
        JobFamilyLineage lineage, int targetYear, RecurrenceSignal signal,
        String rationale, int windowStartYear, int windowEndYear
    ) {
        return new OpportunityForecast(lineage.lineageKey(), targetYear, signal, rationale,
            lineage.observedYears(), windowStartYear, windowEndYear, lineage.evidenceIds());
    }
}
