package com.careeros.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 校验模型叙述能否与确定性事实块一起展示。
 *
 * <p>设计取向有两处是刻意的：
 *
 * <p><b>一、叙述里不允许出现任何事实。</b> 校验不是去比对"这个数字在工具结果里出现过没有"
 * ——同一个数字可能属于另一个岗位、另一个字段或另一次已过期的评估，出现过并不代表用对了。
 * 与其事后判断归属，不如从结构上杜绝：所有数值与判定词只出现在程序渲染的事实块里，
 * 叙述只负责连接。这样"把 A 岗位的适配分说成 B 岗位的"不是被检测出来，而是写不出来。
 *
 * <p><b>二、不通过就整块回落，不做逐句删减。</b> 逐句删减看起来更友好，但限制条件往往就是
 * 单独一句——删掉一句"限本市户籍"，回答会读起来更肯定，而不是更谨慎。宁可整段不展示，
 * 只给确定性事实块。
 */
public final class AnswerNarrativeValidator {

    /** 半角与全角数字。所有数值都归事实块，叙述里出现即为越界。 */
    private static final Pattern DIGITS = Pattern.compile("[0-9０-９]");

    /** 中文数字直接接计量单位，例如"七十二分"、"四成"。 */
    private static final Pattern CHINESE_NUMERIC_CLAIM =
        Pattern.compile("[一二两三四五六七八九十百千万零]+\\s*[分成%％件个人年岁天]");

    /** 判定词与分层标签只能由事实块给出。 */
    private static final List<String> VERDICT_TOKENS = List.of(
        "可报", "不可报", "条件可报", "待确认", "证据冲突",
        "建议关注", "暂不建议", "需人工复核", "已排除",
        "T1", "T2", "T3"
    );

    /** 把主观指数说成统计概率的表述。基线 §6.2 明确禁止。 */
    private static final List<String> PROBABILITY_CLAIMS = List.of(
        "概率", "几率", "录取率", "上岸率", "命中率", "把握很大", "稳了", "十拿九稳"
    );

    /**
     * 否定与免责语境。合法的免责声明本身就要提到"概率"才能否定它——
     * 「以上不是录取概率」必须放行，否则系统会因为说了实话而被自己拦下。
     */
    private static final List<String> DISCLAIMER_MARKERS = List.of(
        "不是", "不代表", "不构成", "并非", "无法", "不能", "不预测", "不提供", "不等于", "非"
    );

    /** 免责标记需要出现在被禁词之前多少个字符内才算作用于它。 */
    private static final int DISCLAIMER_WINDOW = 12;

    public Result validate(String narrative, AnswerBlock block) {
        Objects.requireNonNull(block, "block");
        if (narrative == null || narrative.isBlank()) {
            return Result.violating(List.of("模型未返回叙述"));
        }
        var violations = new ArrayList<String>();
        if (DIGITS.matcher(narrative).find()) {
            violations.add("叙述包含数值；所有数值必须来自确定性事实块");
        }
        if (CHINESE_NUMERIC_CLAIM.matcher(narrative).find()) {
            violations.add("叙述包含中文数字表述的数值；所有数值必须来自确定性事实块");
        }
        for (String token : VERDICT_TOKENS) {
            if (narrative.contains(token)) {
                violations.add("叙述包含判定词「" + token + "」；判定只能来自确定性事实块");
            }
        }
        for (String claim : PROBABILITY_CLAIMS) {
            int at = narrative.indexOf(claim);
            while (at >= 0) {
                if (!disclaimed(narrative, at)) {
                    violations.add("叙述把决策指数表述为概率：「" + claim + "」");
                    break;
                }
                at = narrative.indexOf(claim, at + claim.length());
            }
        }
        return violations.isEmpty() ? Result.ok() : Result.violating(List.copyOf(violations));
    }

    /**
     * 组装最终回答。
     *
     * <p>事实块永远逐字出现，无论叙述通过与否——模型既改不了它，也删不掉它。
     */
    public Composed compose(String narrative, AnswerBlock block) {
        Result result = validate(narrative, block);
        String rendered = block.render();
        if (!result.accepted()) {
            return new Composed(rendered, false, result.violations());
        }
        return new Composed(narrative.strip() + "\n\n" + rendered, true, List.of());
    }

    private static boolean disclaimed(String narrative, int at) {
        int from = Math.max(0, at - DISCLAIMER_WINDOW);
        String window = narrative.substring(from, at);
        return DISCLAIMER_MARKERS.stream().anyMatch(window::contains);
    }

    public record Result(boolean accepted, List<String> violations) {
        public Result { violations = violations == null ? List.of() : List.copyOf(violations); }
        static Result ok() { return new Result(true, List.of()); }
        static Result violating(List<String> violations) { return new Result(false, violations); }
    }

    /**
     * @param answer          最终展示的文本
     * @param narrativeUsed   模型叙述是否被采用；false 表示整块回落到确定性事实块
     * @param violations      回落原因，用于留痕——没人看得见的拦截等于没拦截
     */
    public record Composed(String answer, boolean narrativeUsed, List<String> violations) {
        public Composed { violations = violations == null ? List.of() : List.copyOf(violations); }
    }
}
