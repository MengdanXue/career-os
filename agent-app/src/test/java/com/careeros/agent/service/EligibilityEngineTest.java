package com.careeros.agent.service;

import com.careeros.agent.domain.CandidateProfile;
import com.careeros.agent.domain.CandidateProfile.Fact;
import com.careeros.agent.domain.CandidateProfile.FactStatus;
import com.careeros.agent.domain.CandidateProfile.NumericFact;
import com.careeros.agent.domain.CandidateProfile.PartialDate;
import com.careeros.agent.domain.EligibilityAssessment;
import com.careeros.crawler.domain.NormalizedJob;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EligibilityEngineTest {
    private final EligibilityEngine engine = new EligibilityEngine();

    @Test
    void returnsEligibleWhenConfirmedHardConditionsPass() {
        EligibilityAssessment result = engine.assess(profile(FactStatus.CONFIRMED, "群众"),
                job(null, null, "1987-03-19", List.of()));

        assertEquals(EligibilityAssessment.OverallStatus.ELIGIBLE, result.status());
    }

    @Test
    void returnsNeedsConfirmationForUnknownPoliticalStatus() {
        EligibilityAssessment result = engine.assess(profile(FactStatus.CONFIRMED, null),
                job("中共党员", null, "1987-03-19", List.of()));

        assertEquals(EligibilityAssessment.OverallStatus.NEEDS_CONFIRMATION, result.status());
    }

    @Test
    void rejectsMismatchedGraduateYear() {
        EligibilityAssessment result = engine.assess(profile(FactStatus.CONFIRMED, "群众"),
                job(null, "仅限2026届应届毕业生", "1987-03-19", List.of()));

        assertEquals(EligibilityAssessment.OverallStatus.INELIGIBLE, result.status());
    }

    @Test
    void returnsConditionalForExpectedDegreeAndMajor() {
        EligibilityAssessment result = engine.assess(profile(FactStatus.EXPECTED, "群众"),
                job(null, null, "1987-03-19", List.of()));

        assertEquals(EligibilityAssessment.OverallStatus.CONDITIONAL, result.status());
    }

    @Test
    void realStyleHospitalRowCanPassAgeWithoutExactDayAndExamNoteIsNotAHardCondition() {
        EligibilityAssessment result = engine.assess(profile(FactStatus.CONFIRMED, "群众"),
                job(null, null, null, List.of("面试时需测试专业知识。")));

        assertEquals(EligibilityAssessment.OverallStatus.ELIGIBLE, result.status());
        assertEquals(EligibilityAssessment.CriterionStatus.PASS,
                criterion(result, "age").status());
        assertEquals(EligibilityAssessment.CriterionStatus.NOT_APPLICABLE,
                criterion(result, "other_conditions").status());
    }

    private EligibilityAssessment.Criterion criterion(EligibilityAssessment result, String code) {
        return result.criteria().stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private CandidateProfile profile(FactStatus studyStatus, String politicalStatus) {
        return new CandidateProfile(
                "test", new PartialDate(1992, 12, null, FactStatus.CONFIRMED, null),
                new Fact("硕士研究生", studyStatus, LocalDate.of(2027, 6, 30), null),
                new Fact("硕士", studyStatus, LocalDate.of(2027, 6, 30), null),
                new Fact("计算机科学与技术", studyStatus, LocalDate.of(2027, 6, 30), null),
                true, new Fact(null, FactStatus.UNKNOWN, null, null),
                new NumericFact(12.0, FactStatus.CONFIRMED, null),
                List.of(new Fact("计算机应用中级职称", FactStatus.CONFIRMED, null, null)),
                new Fact(politicalStatus, politicalStatus == null ? FactStatus.UNKNOWN : FactStatus.CONFIRMED, null, null),
                new Fact("2027届海外毕业生", studyStatus, LocalDate.of(2027, 6, 30), null),
                new Fact(null, FactStatus.UNKNOWN, null, null)
        );
    }

    private NormalizedJob job(
            String politicalStatus, String applicantType, String birthBoundary, List<String> otherConditions
    ) {
        return new NormalizedJob(
                "1.1.0", "job_0123456789abcdef",
                new NormalizedJob.Source(
                        "S01", "测试公告", "https://example.com/announcement", null, "2026-01-01",
                        "2026-01-02T00:00:00Z", "jobs.xlsx", "a".repeat(64),
                        new NormalizedJob.Locator("spreadsheet", "岗位表", null, null, 2, "1-based row"),
                        "测试岗位原文证据"
                ),
                new NormalizedJob.Employer("测试事业单位", "测试主管部门", "public_institution"),
                new NormalizedJob.Position(
                        "信息化专业技术岗", "001", "十级以下", "专业技术", 1,
                        "事业编制", List.of("杭州"), List.of("信息系统建设")
                ),
                new NormalizedJob.Requirements(
                        applicantType, "硕士研究生以上", "硕士以上", "计算机科学与技术类（计算机科学与技术）",
                        List.of("计算机科学与技术"),
                        new NormalizedJob.Age("<=", 38, birthBoundary, "38周岁以下"),
                        null, politicalStatus, List.of(), null, null, List.of("Java"), otherConditions
                ),
                new NormalizedJob.Application(
                        "2026-03-01T00:00:00Z", "2026-03-10T23:59:59Z", "网上报名",
                        "https://example.com/apply", List.of()
                ),
                new NormalizedJob.Classification(true, 0.95, List.of("计算机"), List.of("信息化"), "测试"),
                new NormalizedJob.Extraction("test", "1", 1.0, "human_verified", List.of())
        );
    }
}
