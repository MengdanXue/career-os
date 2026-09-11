package com.careeros.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 回答里一条可追溯的事实。
 *
 * <p>产品需求 §6.2 与 §10.6 要求结论可解释、可定位。仅仅"这个数字在工具结果里出现过"
 * 不足以保证正确——同一个数字可能属于另一个岗位、另一个字段、或另一个已经过期的评估版本。
 * 因此事实必须是类型化的，把"哪个岗位、哪个评估版本、哪个字段、什么值、什么单位、
 * 适用到什么时间、依据哪些证据"一起绑定。
 *
 * @param jobPostingId     事实属于哪个岗位。跨岗位错配靠它识别，而不是靠数值比对。
 * @param evaluatorVersion 产出这条事实的评估器版本。与当前版本不一致即为过期引用。
 * @param field            字段。同一个数值在不同字段下含义完全不同（72 分适配 vs 72% 覆盖率）。
 * @param value            规范化后的值。枚举取其 name()，数值取十进制字符串。
 * @param unit             单位。覆盖率是百分比，适配是分数——两者不可互换表述。
 * @param applicableAsOf   适用时间。资格以官方报名截止日为准，过了这个日期结论不再适用。
 * @param evidenceIds      支撑这条事实的证据片段；为空表示只能回落到公告级证据。
 */
public record AnswerFact(
    UUID jobPostingId,
    String evaluatorVersion,
    FactField field,
    String value,
    FactUnit unit,
    LocalDate applicableAsOf,
    List<UUID> evidenceIds
) {
    public AnswerFact {
        Objects.requireNonNull(jobPostingId, "jobPostingId");
        if (evaluatorVersion == null || evaluatorVersion.isBlank()) {
            throw new IllegalArgumentException("evaluatorVersion is required");
        }
        Objects.requireNonNull(field, "field");
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value is required");
        Objects.requireNonNull(unit, "unit");
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (unit == FactUnit.PERCENT || unit == FactUnit.POINTS) {
            int numeric = parseNumeric(value, field);
            if (numeric < 0) throw new IllegalArgumentException(field + " must not be negative");
            if (unit == FactUnit.PERCENT && numeric > 100) {
                throw new IllegalArgumentException("percent must not exceed 100");
            }
        }
    }

    private static int parseNumeric(String value, FactField field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must carry a numeric value, got: " + value);
        }
    }

    /**
     * 这条事实是否来自一次已经过期的评估。
     *
     * @param currentEvaluatorGeneration 当前评估器代号，例如 {@code eligibility-hard-verdict-v7}。
     *        决策快照的版本串是复合的（决策版本 | 资格版本 @ 资格参考日），资格参考日逐岗不同，
     *        因此不能整串相等比较——那会把所有事实都误判成过期。带有当前代号即为当前。
     */
    public boolean staleAgainst(String currentEvaluatorGeneration) {
        return !evaluatorVersion.contains(currentEvaluatorGeneration);
    }

    public enum FactField {
        ELIGIBILITY("硬资格"),
        TIER("机会分层"),
        RECOMMENDATION("行动建议"),
        FIT_SCORE("岗位适配"),
        STABILITY_SCORE("稳定性"),
        EVIDENCE_COVERAGE("证据覆盖率"),
        APPLICATION_DEADLINE("报名截止"),
        RESTRICTION("限制条件");

        private final String label;

        FactField(String label) { this.label = label; }

        public String label() { return label; }
    }

    public enum FactUnit {
        /** 枚举值，按域内词表渲染。 */
        ENUM,
        /** 分，0–100。不是百分比，不可写成百分号。 */
        POINTS,
        /** 百分比，0–100。证据覆盖率用它——覆盖率不是录取概率。 */
        PERCENT,
        DATE,
        TEXT
    }
}
