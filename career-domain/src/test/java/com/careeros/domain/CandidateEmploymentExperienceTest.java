package com.careeros.domain;

import static com.careeros.domain.CandidateEmploymentRecord.EmploymentMode.FULL_TIME;
import static com.careeros.domain.CandidateEmploymentRecord.VerificationStatus.VERIFIED;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.DomainEnums.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateEmploymentExperienceTest {
    @Test
    void countsOnlyCompleteMergedMonthsAcrossExactAndOneDayShortBoundaries() {
        assertThat(years(record(LocalDate.of(2021, 1, 1), LocalDate.of(2021, 12, 31)))).hasValue(1);
        assertThat(years(record(LocalDate.of(2021, 1, 2), LocalDate.of(2021, 12, 31)))).hasValue(0);
        assertThat(years(record(LocalDate.of(2020, 2, 29), LocalDate.of(2021, 2, 28)))).hasValue(1);
    }

    @Test
    void mergesOverlapsAndCombinesCompleteMonthsFromSeparateIntervals() {
        assertThat(years(
            record(LocalDate.of(2021, 1, 1), LocalDate.of(2021, 8, 31)),
            record(LocalDate.of(2021, 7, 1), LocalDate.of(2021, 12, 31)))).hasValue(1);
        assertThat(years(
            record(LocalDate.of(2021, 1, 1), LocalDate.of(2021, 6, 30)),
            record(LocalDate.of(2022, 1, 1), LocalDate.of(2022, 6, 30)))).hasValue(1);
    }

    @Test
    void distinguishesConfirmedEmptyHistoryFromUnknownHistory() {
        var candidate = candidate(List.of());
        assertThat(CandidateEmploymentExperience.completedYears(candidate, CandidateFacts.confirmed(candidate), LocalDate.of(2026, 8, 23))).hasValue(0);
        assertThat(CandidateEmploymentExperience.completedYears(candidate, CandidateFacts.resolve(candidate, List.of()), LocalDate.of(2026, 8, 23))).isEmpty();
    }

    private static java.util.OptionalInt years(CandidateEmploymentRecord... records) {
        var candidate = candidate(List.of(records));
        return CandidateEmploymentExperience.completedYears(candidate, CandidateFacts.confirmed(candidate), LocalDate.of(2026, 8, 23));
    }

    private static CandidateEmploymentRecord record(LocalDate start, LocalDate end) {
        return new CandidateEmploymentRecord("测试单位", "工程师", start, end, FULL_TIME, VERIFIED, Set.of("劳动合同"));
    }

    private static CandidateProfile candidate(List<CandidateEmploymentRecord> records) {
        return new CandidateProfile(UUID.randomUUID(), "候选人", new PartialDate(1992, 12, 31), EducationLevel.BACHELOR,
            Set.of("计算机科学与技术"), 2014, 7, Set.of(), List.of("杭州"), Set.of(EmploymentType.ESTABLISHMENT),
            "test", Set.of(), Set.of(), Set.of(), Set.of(), List.of(), Gender.FEMALE,
            PoliticalAffiliation.NON_MEMBER, records);
    }
}
