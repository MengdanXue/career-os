package com.careeros.domain;

import com.careeros.domain.DomainEnums.*;
import com.careeros.domain.JobLineageBuilder.LineageInput;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobLineageAndForecastTest {
    private final JobLineageBuilder builder = new JobLineageBuilder();
    private final OpportunityForecaster forecaster = new OpportunityForecaster();

    // --- 岗位族归并 ---

    @Test void sameOrganizationAndTitleAcrossYearsFormOneLineage() {
        var lineages = builder.build(List.of(
            input(2025, "杭州市儿童医院", "信息中心工作人员", JobFamily.INFORMATION_SYSTEMS),
            input(2026, "杭州市儿童医院", "信息中心工作人员", JobFamily.INFORMATION_SYSTEMS)));

        assertThat(lineages).hasSize(1);
        assertThat(lineages.getFirst().observedYears()).containsExactly(2025, 2026);
        assertThat(lineages.getFirst().isContiguous()).isTrue();
    }

    /** 归一化只吃空白与常见标点，不做同义词扩展。 */
    @Test void normalizationIgnoresPunctuationAndSpacingOnly() {
        var lineages = builder.build(List.of(
            input(2025, "杭州市儿童医院", "信息中心 工作人员", JobFamily.INFORMATION_SYSTEMS),
            input(2026, " 杭州市儿童医院 ", "信息中心（工作人员）", JobFamily.INFORMATION_SYSTEMS)));
        assertThat(lineages).hasSize(1);
    }

    /**
     * 归并键刻意保守：岗位名称实质改变就分成两族。宁可少归并（信号偏弱），
     * 也不把不同岗位拼成一条假的"连续招聘"。
     */
    @Test void differentTitlesAreNotMergedIntoAFakeRecurrence() {
        var lineages = builder.build(List.of(
            input(2025, "杭州市儿童医院", "信息中心工作人员", JobFamily.INFORMATION_SYSTEMS),
            input(2026, "杭州市儿童医院", "数据中心运维岗", JobFamily.INFORMATION_SYSTEMS)));

        assertThat(lineages).hasSize(2);
        assertThat(lineages).allSatisfy(lineage -> assertThat(lineage.observedYears()).hasSize(1));
    }

    @Test void differentOrganizationsNeverShareALineage() {
        var lineages = builder.build(List.of(
            input(2025, "杭州市儿童医院", "信息中心工作人员", JobFamily.INFORMATION_SYSTEMS),
            input(2026, "杭州市第一人民医院", "信息中心工作人员", JobFamily.INFORMATION_SYSTEMS)));
        assertThat(lineages).hasSize(2);
    }

    @Test void lineageUsesTheMostRecentPostingAsRepresentative() {
        var lineages = builder.build(List.of(
            input(2024, "杭州市儿童医院", "信息中心工作人员", JobFamily.INFORMATION_SYSTEMS),
            input(2026, "杭州市儿童医院", "信息中心工作人员", JobFamily.INFORMATION_SYSTEMS)));
        assertThat(lineages.getFirst().lastObservedYear()).isEqualTo(2026);
        assertThat(lineages.getFirst().firstObservedYear()).isEqualTo(2024);
        assertThat(lineages.getFirst().jobPostingIds()).hasSize(2);
    }

    @Test void aLineageNeedsAtLeastOneObservedYear() {
        assertThatThrownBy(() -> new JobFamilyLineage("key", UUID.randomUUID(), "单位", "岗位",
            JobFamily.SOFTWARE, new java.util.TreeSet<>(), List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 再现信号 ---

    /** 只有一年数据时，任何"连续招聘"的说法都不成立。 */
    @Test void aSingleYearWindowCannotSupportAnyRecurrenceClaim() {
        var lineage = lineage(2026);
        var forecast = forecaster.forecast(lineage, 2027, 2026, 2026);
        assertThat(forecast.signal()).isEqualTo(RecurrenceSignal.INSUFFICIENT_HISTORY);
        assertThat(forecast.observationWindowSpan()).isEqualTo(1);
    }

    @Test void consecutiveYearsAreRecurringAnnual() {
        var forecast = forecaster.forecast(lineage(2025, 2026), 2027, 2025, 2026);
        assertThat(forecast.signal()).isEqualTo(RecurrenceSignal.RECURRING_ANNUAL);
        assertThat(forecast.rationale()).contains("2025、2026").contains("观测窗口 2025–2026");
    }

    @Test void gapsMakeTheSignalIntermittent() {
        var forecast = forecaster.forecast(lineage(2024, 2026), 2027, 2024, 2026);
        assertThat(forecast.signal()).isEqualTo(RecurrenceSignal.INTERMITTENT);
    }

    @Test void oneAppearanceInALongWindowIsASingleOccurrence() {
        var forecast = forecaster.forecast(lineage(2025), 2027, 2024, 2026);
        assertThat(forecast.signal()).isEqualTo(RecurrenceSignal.SINGLE_OCCURRENCE);
    }

    /** 最近一次出现离窗口末年越远，越要提醒确认单位是否仍有该需求。 */
    @Test void aStaleLineageWarnsAboutTheGap() {
        var forecast = forecaster.forecast(lineage(2024, 2025), 2027, 2024, 2026);
        assertThat(forecast.signal()).isEqualTo(RecurrenceSignal.RECURRING_ANNUAL);
        assertThat(forecast.rationale()).contains("距窗口末年已 1 年");
    }

    /** §11：不给概率。预测结构上就没有概率字段。 */
    @Test void forecastCarriesNoProbabilityField() {
        assertThat(OpportunityForecast.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("probability", "likelihood", "successRate", "confidence");
    }

    @Test void everySignalCarriesItsObservationWindow() {
        for (var forecast : List.of(
            forecaster.forecast(lineage(2026), 2027, 2026, 2026),
            forecaster.forecast(lineage(2025, 2026), 2027, 2025, 2026),
            forecaster.forecast(lineage(2024, 2026), 2027, 2024, 2026),
            forecaster.forecast(lineage(2025), 2027, 2024, 2026))) {
            assertThat(forecast.rationale()).contains("观测窗口");
            assertThat(forecast.targetYear()).isEqualTo(2027);
        }
    }

    // --- 接入评分 ---

    @Test void futureDimensionReflectsTheSignalAndStaysUnscoredWithoutHistory() {
        assertThat(OpportunityScorer.future(null).basis()).isEqualTo(ScoreBasis.INSUFFICIENT_DATA);
        assertThat(OpportunityScorer.future(forecaster.forecast(lineage(2026), 2027, 2026, 2026)).basis())
            .isEqualTo(ScoreBasis.INSUFFICIENT_DATA);

        var recurring = OpportunityScorer.future(forecaster.forecast(lineage(2025, 2026), 2027, 2025, 2026));
        var single = OpportunityScorer.future(forecaster.forecast(lineage(2025), 2027, 2024, 2026));
        assertThat(recurring.basis()).isEqualTo(ScoreBasis.ESTIMATED);
        assertThat(recurring.value()).isGreaterThan(single.value());
        // 依据必须一路带到评分里，读者要能看出结论来自多长的历史
        assertThat(recurring.rationale()).contains("观测窗口");
    }

    private JobFamilyLineage lineage(int... years) {
        var observed = new java.util.TreeSet<Integer>();
        for (int year : years) observed.add(year);
        return new JobFamilyLineage("lineage-key", UUID.randomUUID(), "杭州市儿童医院",
            "信息中心工作人员", JobFamily.INFORMATION_SYSTEMS, observed,
            List.of(UUID.randomUUID()), List.of(UUID.randomUUID()));
    }

    private LineageInput input(int year, String organizationName, String title, JobFamily family) {
        var job = new JobPosting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, title,
            family, EmploymentType.ESTABLISHMENT, "杭州", 1, EducationLevel.MASTER, Set.of(), Set.of(),
            null, LocalDate.of(year, 1, 1), null, Set.of(), "", "https://example.test/" + year,
            List.of(UUID.randomUUID()), Map.of());
        return new LineageInput(job, year, organizationName);
    }
}
