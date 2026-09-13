package com.careeros.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.AnswerFact.FactField;
import com.careeros.domain.AnswerFact.FactUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerNarrativeValidatorTest {
    private static final String CURRENT = "eligibility-hard-verdict-v7";
    private static final UUID JOB_A = UUID.randomUUID();

    private final AnswerNarrativeValidator validator = new AnswerNarrativeValidator();

    private static AnswerBlock block() {
        return AnswerBlock.of(List.of(new AnswerBlock.JobFacts(JOB_A, "信息中心工作人员", "杭州市儿童医院", List.of(
            new AnswerFact(JOB_A, CURRENT, FactField.ELIGIBILITY, "CONDITIONAL", FactUnit.ENUM, null, List.of()),
            new AnswerFact(JOB_A, CURRENT, FactField.FIT_SCORE, "72", FactUnit.POINTS, null, List.of()),
            new AnswerFact(JOB_A, CURRENT, FactField.EVIDENCE_COVERAGE, "40", FactUnit.PERCENT, null, List.of()),
            new AnswerFact(JOB_A, CURRENT, FactField.RESTRICTION, "限中共党员", FactUnit.TEXT, null, List.of())))),
            CURRENT);
    }

    // --- 叙述不得携带事实 ---

    @Test void aPlainConnectiveNarrativeIsAccepted() {
        var result = validator.validate("下面按稳定性排序，其中一处仍需你补充材料后才能定。", block());
        assertThat(result.accepted()).isTrue();
    }

    /**
     * 跨岗位分数错配：模型说"杭州市儿童医院那个适配 72 分"，而 72 分其实属于另一个岗位。
     * 数值比对拦不住——72 确实在工具结果里出现过。这里靠"叙述不得出现任何数值"从结构上杜绝。
     */
    @Test void aNarrativeThatQuotesAScoreIsRejectedEvenWhenTheNumberExistsSomewhere() {
        var result = validator.validate("杭州市儿童医院那个岗位适配 72 分，值得优先看。", block());

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).anyMatch(value -> value.contains("数值"));
    }

    @Test void aNarrativeUsingChineseNumeralsForAScoreIsAlsoRejected() {
        var result = validator.validate("第一个岗位适配七十二分，可以优先看。", block());

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).anyMatch(value -> value.contains("中文数字"));
    }

    /** 判定词只能由事实块给出，否则模型可以把"条件可报"说成"可报"。 */
    @Test void aNarrativeThatRestatesAVerdictIsRejected() {
        var result = validator.validate("这个岗位你是可报的，放心投。", block());

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).anyMatch(value -> value.contains("判定词"));
    }

    // --- 覆盖率冒充录取概率 ---

    @Test void aNarrativeThatTurnsCoverageIntoAnAdmissionProbabilityIsRejected() {
        var result = validator.validate("证据覆盖率很高，上岸概率不小。", block());

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).anyMatch(value -> value.contains("概率"));
    }

    @Test void bareConfidenceClaimsAreRejected() {
        assertThat(validator.validate("这个稳了。", block()).accepted()).isFalse();
        assertThat(validator.validate("你报这个把握很大。", block()).accepted()).isFalse();
    }

    // --- 合法免责声明不得误伤 ---

    /**
     * 免责声明必须提到"概率"才能否定它。若因为出现了被禁词就拦下，系统会因为说了实话
     * 而被自己拦住，只剩下模糊的表述可用——那反而更危险。
     */
    @Test void aLegitimateDisclaimerIsNotFalselyRejected() {
        assertThat(validator.validate("以下是决策指数，不是录取概率。", block()).accepted()).isTrue();
        assertThat(validator.validate("这些分数并非上岸率，请以官方公告为准。", block()).accepted()).isTrue();
        assertThat(validator.validate("系统不预测录取概率，只汇总公开证据。", block()).accepted()).isTrue();
        assertThat(validator.validate("覆盖率不代表几率。", block()).accepted()).isTrue();
    }

    /**
     * 免责只在同一小句里才算数。
     *
     * <p>"不是我说，你上岸概率很高"里的"不是"属于上一小句，跟"概率"没有关系，
     * 但按固定字数回看它正好落在窗口里，于是一句真正的概率断言被放行了。
     * 固定窗口分不出"否定了这个断言"和"附近碰巧有个否定词"。
     */
    @Test void aNegationInAnEarlierClauseDoesNotLicenseAClaim() {
        assertThat(validator.validate("不是我说，你上岸概率很高。", block()).accepted()).isFalse();
        assertThat(validator.validate("这个我不清楚；你上岸概率很高。", block()).accepted()).isFalse();
        assertThat(validator.validate("不确定，命中率应该不低。", block()).accepted()).isFalse();
    }

    /** 先免责再断言不能蒙混过关：后一处断言仍要被拦。 */
    @Test void aDisclaimerDoesNotLicenseALaterClaim() {
        var result = validator.validate("这不是录取概率。不过说实话，你上岸概率挺高的。", block());
        assertThat(result.accepted()).isFalse();
    }

    // --- 整块回落，不做逐句删减 ---

    /**
     * 不合格时整段叙述都不展示。逐句删减会把"限中共党员"这类单独成句的限制条件删掉，
     * 让回答读起来更肯定——那是最危险的失败方式。
     */
    @Test void aRejectedNarrativeIsDroppedWholeAndRestrictionsSurvive() {
        var composed = validator.compose("这个岗位适配 72 分，限中共党员这点你满足。", block());

        assertThat(composed.narrativeUsed()).isFalse();
        assertThat(composed.answer()).doesNotContain("这个岗位适配");
        // 限制条件来自事实块，不受叙述被拒影响。
        assertThat(composed.answer()).contains("限中共党员");
        assertThat(composed.violations()).isNotEmpty();
    }

    /** 事实块永远逐字出现，无论叙述通过与否——模型改不了也删不掉。 */
    @Test void theFactBlockIsAlwaysPresentVerbatim() {
        var block = block();
        assertThat(validator.compose("下面按稳定性排序。", block).answer()).contains(block.render());
        assertThat(validator.compose("这个稳了。", block).answer()).contains(block.render());
    }

    @Test void anEmptyNarrativeFallsBackWithAReason() {
        var composed = validator.compose("   ", block());

        assertThat(composed.narrativeUsed()).isFalse();
        assertThat(composed.violations()).contains("模型未返回叙述");
    }

    /** 回落原因必须能被记录——没人看得见的拦截等于没拦截。 */
    @Test void everyRejectionCarriesAReason() {
        assertThat(validator.compose("适配 72 分。", block()).violations()).isNotEmpty();
        assertThat(validator.compose("可报。", block()).violations()).isNotEmpty();
        assertThat(validator.compose("上岸概率很高。", block()).violations()).isNotEmpty();
    }
}
