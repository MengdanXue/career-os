package com.careeros.domain;

import com.careeros.domain.DomainEnums.ScoreBasis;
import com.careeros.domain.DomainEnums.ScoreDimension;
import com.careeros.domain.DomainEnums.StrategyGrade;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 产品需求 §6.2 的多维评分结果。六个维度分别保留，**没有**总分字段——总分正是 §6.2
 * 明令禁止的"不可解释的匹配分"。对外只额外给一个由规则推导的 {@link StrategyGrade}。
 *
 * <p>{@code gradeRationale} 说明这个等级是怎么来的，{@code dimensionsWithoutData} 公开哪些维度
 * 缺数据——§6.2 要求"公开依据和不确定性"，而不是把缺口藏进一个看起来完整的分数里。
 */
public record OpportunityScorecard(
    UUID jobPostingId,
    Map<ScoreDimension, DimensionScore> dimensions,
    StrategyGrade strategyGrade,
    String gradeRationale,
    String scorerVersion
) {
    public OpportunityScorecard {
        Objects.requireNonNull(jobPostingId, "jobPostingId");
        Objects.requireNonNull(strategyGrade, "strategyGrade");
        if (gradeRationale == null || gradeRationale.isBlank()) {
            throw new IllegalArgumentException("gradeRationale is required");
        }
        if (scorerVersion == null || scorerVersion.isBlank()) {
            throw new IllegalArgumentException("scorerVersion is required");
        }
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
        for (ScoreDimension dimension : ScoreDimension.values()) {
            if (!dimensions.containsKey(dimension)) {
                throw new IllegalArgumentException("scorecard is missing dimension " + dimension);
            }
        }
    }

    /** 缺少数据、因而没有分值的维度。调用方应当把它们显示为"数据不足"而不是 0 分。 */
    public List<ScoreDimension> dimensionsWithoutData() {
        return dimensions.entrySet().stream()
            .filter(entry -> entry.getValue().basis() == ScoreBasis.INSUFFICIENT_DATA)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }

    public static Map<ScoreDimension, DimensionScore> emptyDimensions() {
        return new EnumMap<>(ScoreDimension.class);
    }

    /**
     * 单个维度的结论。{@code value} 只有在有依据时才存在——{@code INSUFFICIENT_DATA} 必须不带分值，
     * 与 {@link ExtractedFact} 里 UNKNOWN 不得带值是同一条规矩。
     *
     * <p>注意 {@code PREPARATION_COST} 是成本：分值越高代表准备成本越高，方向与其余五维相反。
     */
    public record DimensionScore(Integer value, ScoreBasis basis, String rationale) {
        public DimensionScore {
            Objects.requireNonNull(basis, "basis");
            if (rationale == null || rationale.isBlank()) throw new IllegalArgumentException("rationale is required");
            if (basis == ScoreBasis.INSUFFICIENT_DATA && value != null) {
                throw new IllegalArgumentException("INSUFFICIENT_DATA dimension must not carry a value");
            }
            if (basis != ScoreBasis.INSUFFICIENT_DATA) {
                if (value == null) throw new IllegalArgumentException("scored dimension requires a value");
                if (value < 0 || value > 100) throw new IllegalArgumentException("value must be between 0 and 100");
            }
        }

        public static DimensionScore measured(int value, String rationale) {
            return new DimensionScore(value, ScoreBasis.MEASURED, rationale);
        }
        public static DimensionScore estimated(int value, String rationale) {
            return new DimensionScore(value, ScoreBasis.ESTIMATED, rationale);
        }
        public static DimensionScore insufficient(String rationale) {
            return new DimensionScore(null, ScoreBasis.INSUFFICIENT_DATA, rationale);
        }
    }
}
