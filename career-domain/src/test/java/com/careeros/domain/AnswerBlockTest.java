package com.careeros.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.domain.AnswerFact.FactField;
import com.careeros.domain.AnswerFact.FactUnit;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerBlockTest {
    private static final String CURRENT = "eligibility-hard-verdict-v7";
    private static final UUID JOB_A = UUID.randomUUID();
    private static final UUID JOB_B = UUID.randomUUID();

    // --- 跨岗位分数错配 ---

    /**
     * 一条属于 B 岗位的事实不能被归到 A 岗位下。这不是靠比对数值发现的——
     * 两个岗位的适配分完全可能都是 72，数值比对根本分不出来。事实自带岗位 ID。
     */
    @Test void aFactFromAnotherJobCannotBeGroupedUnderThisJob() {
        var factOfB = new AnswerFact(JOB_B, CURRENT, FactField.FIT_SCORE, "72", FactUnit.POINTS, null, List.of());

        assertThatThrownBy(() -> new AnswerBlock.JobFacts(JOB_A, "信息中心工作人员", "杭州市儿童医院", List.of(factOfB)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(JOB_B.toString());
    }

    /** 两个岗位分数相同也不会串：渲染按岗位分组，各自只带自己的事实。 */
    @Test void identicalScoresOnDifferentJobsStayWithTheirOwnJob() {
        var block = AnswerBlock.of(List.of(
            new AnswerBlock.JobFacts(JOB_A, "甲岗位", "甲单位",
                List.of(new AnswerFact(JOB_A, CURRENT, FactField.FIT_SCORE, "72", FactUnit.POINTS, null, List.of()))),
            new AnswerBlock.JobFacts(JOB_B, "乙岗位", "乙单位",
                List.of(new AnswerFact(JOB_B, CURRENT, FactField.FIT_SCORE, "72", FactUnit.POINTS, null, List.of())))),
            CURRENT);

        assertThat(block.render()).contains("1. 甲岗位（甲单位）").contains("2. 乙岗位（乙单位）");
        assertThat(block.facts()).hasSize(2);
        assertThat(block.facts()).extracting(AnswerFact::jobPostingId).containsExactly(JOB_A, JOB_B);
    }

    // --- 覆盖率冒充录取概率 ---

    /** 覆盖率按百分比渲染、适配分按分渲染，两者单位不同，不能互相冒充。 */
    @Test void coverageRendersAsPercentAndFitRendersAsPointsSoNeitherReadsAsTheOther() {
        var block = AnswerBlock.of(List.of(new AnswerBlock.JobFacts(JOB_A, "信息中心工作人员", "杭州市儿童医院", List.of(
            new AnswerFact(JOB_A, CURRENT, FactField.FIT_SCORE, "72", FactUnit.POINTS, null, List.of()),
            new AnswerFact(JOB_A, CURRENT, FactField.EVIDENCE_COVERAGE, "40", FactUnit.PERCENT, null, List.of())))),
            CURRENT);

        String rendered = block.render();
        assertThat(rendered).contains("岗位适配：72 分").contains("证据覆盖率：40%");
        // 免责声明随块一起渲染，模型删不掉。
        assertThat(rendered).contains("不是录取概率");
    }

    /** 百分比不能超过 100——把分数当覆盖率塞进来会当场失败，而不是渲染出一个荒谬的值。 */
    @Test void aPercentAboveOneHundredIsRejected() {
        assertThatThrownBy(() -> new AnswerFact(JOB_A, CURRENT, FactField.EVIDENCE_COVERAGE, "140", FactUnit.PERCENT, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 过期评估引用 ---

    /** 过期评估不能当作当前结论呈现，渲染时必须明确标出。 */
    @Test void aStaleAssessmentIsRenderedAsStaleRatherThanCurrent() {
        var stale = new AnswerFact(JOB_A, "eligibility-hard-verdict-v5", FactField.ELIGIBILITY,
            "ELIGIBLE", FactUnit.ENUM, null, List.of());
        var block = AnswerBlock.of(
            List.of(new AnswerBlock.JobFacts(JOB_A, "信息中心工作人员", "杭州市儿童医院", List.of(stale))), CURRENT);

        assertThat(stale.staleAgainst(CURRENT)).isTrue();
        assertThat(block.render()).contains("基于已过期的评估版本");
    }

    @Test void aCurrentAssessmentIsNotMarkedStale() {
        var fresh = new AnswerFact(JOB_A, CURRENT, FactField.ELIGIBILITY, "ELIGIBLE", FactUnit.ENUM, null, List.of());
        var block = AnswerBlock.of(
            List.of(new AnswerBlock.JobFacts(JOB_A, "信息中心工作人员", "杭州市儿童医院", List.of(fresh))), CURRENT);

        assertThat(block.render()).doesNotContain("已过期");
    }

    // --- 限制条件与适用时间 ---

    /** 限制条件全部列出，一条不省——它们是最容易被"读起来更肯定"的改写吃掉的部分。 */
    @Test void everyRestrictionIsRendered() {
        var block = AnswerBlock.of(List.of(new AnswerBlock.JobFacts(JOB_A, "信息中心工作人员", "杭州市儿童医院", List.of(
            new AnswerFact(JOB_A, CURRENT, FactField.RESTRICTION, "限中共党员", FactUnit.TEXT, null, List.of()),
            new AnswerFact(JOB_A, CURRENT, FactField.RESTRICTION, "须报名时未落实工作单位", FactUnit.TEXT, null, List.of())))),
            CURRENT);

        String rendered = block.render();
        assertThat(rendered).contains("限中共党员").contains("须报名时未落实工作单位");
    }

    /** 资格以官方报名截止日为准，适用时间必须随事实一起呈现。 */
    @Test void anApplicableDateIsRenderedWithTheFact() {
        var block = AnswerBlock.of(List.of(new AnswerBlock.JobFacts(JOB_A, "信息中心工作人员", "杭州市儿童医院", List.of(
            new AnswerFact(JOB_A, CURRENT, FactField.ELIGIBILITY, "CONDITIONAL", FactUnit.ENUM,
                LocalDate.of(2027, 3, 25), List.of(UUID.randomUUID()))))),
            CURRENT);

        assertThat(block.render()).contains("硬资格：条件可报（适用至 2027-03-25）［证据 1 处］");
    }

    /** 同样的事实必须渲染出同样的文本，否则逐字包含校验无从谈起。 */
    @Test void renderingIsDeterministic() {
        var facts = List.of(new AnswerBlock.JobFacts(JOB_A, "信息中心工作人员", "杭州市儿童医院", List.of(
            new AnswerFact(JOB_A, CURRENT, FactField.ELIGIBILITY, "ELIGIBLE", FactUnit.ENUM, null, List.of()),
            new AnswerFact(JOB_A, CURRENT, FactField.FIT_SCORE, "72", FactUnit.POINTS, null, List.of()))));

        assertThat(AnswerBlock.of(facts, CURRENT).render())
            .isEqualTo(AnswerBlock.of(facts, CURRENT).render());
    }

    @Test void anEmptyBlockStillCarriesTheDisclaimer() {
        assertThat(AnswerBlock.of(List.of(), CURRENT).render())
            .contains("当前没有符合条件的岗位")
            .contains("不是录取概率");
    }
}
