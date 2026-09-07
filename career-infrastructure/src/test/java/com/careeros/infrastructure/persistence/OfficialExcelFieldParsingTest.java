package com.careeros.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 年龄上限与最低工作年限是硬性资格条件，直接决定 ELIGIBLE / INELIGIBLE。
 * 这里用真实公告里的常见写法固定住解析行为：宁可留空进人工确认，也不能猜出一个错的数字。
 * 纯单元测试，不依赖 Docker，任何环境都会执行。
 */
class OfficialExcelFieldParsingTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "35周岁以下|35",
        "35岁以下|35",
        "年龄35周岁及以下|35",
        "年龄一般不超过40周岁|40",
        "年龄不得超过45周岁|45",
        "未满35周岁|35",
        // 出生日期与年龄上限同时出现：必须取 35，而不是 1990
        "1990年1月1日以后出生，年龄不超过35周岁|35",
        // 区间：必须取上界 35，而不是下界 18
        "年龄在18周岁以上、35周岁以下|35",
        "18周岁至35周岁|35",
        "18-35周岁|35",
        "年龄在18周岁以上、35周岁以下（1990年3月至2008年3月期间出生）|35",
        "应届毕业生年龄可放宽至40周岁|40",
    })
    void ageLimitTakesTheUpperBoundAndIgnoresBirthDates(String text, int expected) {
        assertThat(OfficialExcelImportService.ageLimit(text).value()).isEqualTo(expected);
        assertThat(OfficialExcelImportService.ageLimit(text).warning()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "不限", "无", "年龄不限"})
    void ageLimitIsAbsentWithoutWarningWhenThePostingStatesNoLimit(String text) {
        assertThat(OfficialExcelImportService.ageLimit(text).value()).isNull();
        assertThat(OfficialExcelImportService.ageLimit(text).warning()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1990年1月1日以后出生",
        "1995年1月1日至2008年12月31日期间出生",
    })
    void ageExpressedOnlyAsABirthDateIsLeftBlankAndWarned(String text) {
        var parsed = OfficialExcelImportService.ageLimit(text);
        assertThat(parsed.value()).isNull();
        assertThat(parsed.warning()).contains("出生日期");
    }

    @Test
    void ambiguousAgeTextIsLeftBlankAndWarnedRatherThanGuessed() {
        var parsed = OfficialExcelImportService.ageLimit("28周岁以上，具有35岁以上管理经验者优先");
        assertThat(parsed.value()).isNull();
        assertThat(parsed.warning()).contains("无法确定年龄上限");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "3年以上相关工作经验|3",
        "具有2年及以上工作经历|2",
        "5年|5",
        "工作满5年|5",
        "2年以上|2",
    })
    void experienceYearsReadsTheStatedMinimum(String text, int expected) {
        assertThat(OfficialExcelImportService.experienceYears(text).value()).isEqualTo(expected);
        assertThat(OfficialExcelImportService.experienceYears(text).warning()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // 这些以前会返回 2025 / 2024 并把所有候选人判成不合格
        "2025年应届毕业生",
        "2024年1月以后取得学历，工作经历不限",
        "2026届毕业生",
        "不限",
        "无",
        "",
    })
    void calendarYearsAndOpenRequirementsNeverBecomeAnExperienceFloor(String text) {
        var parsed = OfficialExcelImportService.experienceYears(text);
        assertThat(parsed.value()).isNull();
        assertThat(parsed.warning()).isNull();
    }

    @Test
    void multipleExperienceCandidatesAreLeftBlankAndWarned() {
        var parsed = OfficialExcelImportService.experienceYears("2年以上工作经历，其中3年以上管理经历");
        assertThat(parsed.value()).isNull();
        assertThat(parsed.warning()).contains("多个工作年限候选值");
    }
}
