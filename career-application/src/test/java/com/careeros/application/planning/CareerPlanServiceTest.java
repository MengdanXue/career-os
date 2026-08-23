package com.careeros.application.planning;

import static com.careeros.application.planning.CareerPlanPorts.CoverageStatus.COMPLETE;
import static com.careeros.application.planning.CareerPlanPorts.CoverageStatus.PARTIAL;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.planning.CareerPlanPorts.CareerPlanData;
import com.careeros.application.planning.CareerPlanPorts.CoverageSignal;
import com.careeros.application.planning.CareerPlanPorts.HistoricalJob;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.EducationRecord;
import com.careeros.domain.EducationRecord.CompletionStatus;
import com.careeros.domain.EducationRecord.CredentialVerificationStatus;
import com.careeros.domain.PartialDate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CareerPlanServiceTest {
    private static final UUID CANDIDATE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000001");
    private static final Instant LOADED_AT = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    void generatesThreeScenariosRoutesAgeWindowsAndPartialCoverageWarnings() {
        var data = new CareerPlanData(candidate(), jobs(), coverage(), LOADED_AT);
        var service = new CareerPlanService((candidateId, fromYear, toYear, asOf) -> data);

        var plan = service.generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22));

        assertThat(plan.currentScenario().code()).isEqualTo("PRE_GRADUATION");
        assertThat(plan.futureScenarios()).extracting(CareerPlan.Scenario::code)
            .containsExactly("DEGREE_PENDING_VERIFICATION", "MASTER_VERIFIED");
        assertThat(plan.recommendedRoutes()).extracting(CareerPlan.Route::code)
            .contains("PUBLIC_TECH", "UNIVERSITY_HOSPITAL_IT", "RESEARCH_SUPPORT", "GOVERNMENT_SOE_DIGITAL");
        assertThat(plan.recommendedRoutes().getFirst().code()).isEqualTo("PUBLIC_TECH");
        assertThat(plan.recommendedRoutes().getFirst().label()).doesNotContain("概率");
        assertThat(plan.ageWindows()).filteredOn(window -> window.year() == 2028 && window.maximumAge() == 35)
            .singleElement().extracting(CareerPlan.AgeWindow::eligible).isEqualTo(true);
        assertThat(plan.ageWindows()).filteredOn(window -> window.year() == 2029 && window.maximumAge() == 35)
            .singleElement().extracting(CareerPlan.AgeWindow::eligible).isEqualTo(false);
        assertThat(plan.ageWindows()).filteredOn(window -> window.year() == 2031 && window.maximumAge() == 38)
            .singleElement().extracting(CareerPlan.AgeWindow::eligible).isEqualTo(true);
        assertThat(plan.dataCoverage().complete()).isFalse();
        assertThat(plan.dataCoverage().warnings()).anyMatch(value -> value.contains("数据尚未补齐"));
        assertThat(plan.qualificationRisks()).extracting(CareerPlan.Risk::code)
            .contains("CREDENTIAL_VERIFICATION", "POLITICAL_AFFILIATION", "EMPLOYMENT_EVIDENCE", "SOURCE_COVERAGE");
        assertThat(plan.algorithmVersion()).isEqualTo("career-plan-v1");
    }

    @Test
    void countsJobsAndEventsOnceAndKeepsOrderingDeterministic() {
        var duplicated = new ArrayList<>(jobs());
        duplicated.add(jobs().getFirst());
        var reversed = new ArrayList<>(duplicated);
        java.util.Collections.reverse(reversed);
        var first = new CareerPlanService((id, from, to, asOf) ->
            new CareerPlanData(candidate(), duplicated, coverage(), LOADED_AT))
            .generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22));
        var second = new CareerPlanService((id, from, to, asOf) ->
            new CareerPlanData(candidate(), reversed, coverage(), LOADED_AT))
            .generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22));

        assertThat(first.historicalSummary()).isEqualTo(second.historicalSummary());
        assertThat(first.recommendedRoutes()).isEqualTo(second.recommendedRoutes());
        assertThat(first.historicalSummary()).filteredOn(summary -> summary.year() == 2026)
            .singleElement().satisfies(summary -> {
                assertThat(summary.jobCount()).isEqualTo(3);
                assertThat(summary.eventCount()).isEqualTo(2);
            });
    }

    private static CandidateProfile candidate() {
        return new CandidateProfile(
            CANDIDATE_ID, "测试候选人", new PartialDate(1992, 12, 31), EducationLevel.BACHELOR,
            Set.of("计算机科学与技术"), 2014, null, Set.of("中级：计算机应用（评审）"),
            List.of("杭州", "浙江"), Set.of(EmploymentType.ESTABLISHMENT, EmploymentType.PUBLIC_INSTITUTION_FORMAL),
            "profile-test", Set.of("Java", "PostgreSQL"), Set.of("AI"), Set.of(JobFamily.SOFTWARE, JobFamily.INFORMATION_SYSTEMS),
            Set.of(OrganizationType.PUBLIC_INSTITUTION, OrganizationType.UNIVERSITY, OrganizationType.HOSPITAL),
            List.of(
                new EducationRecord(null, "中国", EducationLevel.BACHELOR, "计算机科学与技术", 2014, 6, CompletionStatus.COMPLETED, CredentialVerificationStatus.NOT_REQUIRED),
                new EducationRecord("示例海外大学", "示例国", EducationLevel.MASTER, "计算机科学", 2027, 6, CompletionStatus.EXPECTED, CredentialVerificationStatus.PLANNED)
            ), Gender.FEMALE, PoliticalAffiliation.UNKNOWN, List.of());
    }

    private static List<HistoricalJob> jobs() {
        var eventA = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var eventB = UUID.fromString("10000000-0000-0000-0000-000000000002");
        return List.of(
            job("20000000-0000-0000-0000-000000000001", eventA, "杭州市河湖管理中心", OrganizationType.PUBLIC_INSTITUTION,
                "信息技术", JobFamily.INFORMATION_SYSTEMS, EmploymentType.PUBLIC_INSTITUTION_FORMAL, EducationLevel.BACHELOR, 38, 2, Set.of("中级"), "不限", true),
            job("20000000-0000-0000-0000-000000000002", eventA, "杭州市第二社会福利院", OrganizationType.PUBLIC_INSTITUTION,
                "信息化管理", JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, EducationLevel.MASTER, 38, null, Set.of(), "不限", true),
            job("20000000-0000-0000-0000-000000000003", eventB, "浙江工业大学", OrganizationType.UNIVERSITY,
                "网络与系统管理", JobFamily.IT_OPERATIONS, EmploymentType.PUBLIC_INSTITUTION_FORMAL, EducationLevel.MASTER, 35, null, Set.of(), "应届毕业生、中共党员", false)
        );
    }

    private static HistoricalJob job(String jobId, UUID eventId, String organization, OrganizationType organizationType,
        String title, JobFamily family, EmploymentType employmentType, EducationLevel education, Integer age,
        Integer experience, Set<String> titles, String scope, boolean evidenceComplete) {
        return new HistoricalJob(UUID.fromString(jobId), eventId, 2026, LocalDate.of(2026, 3, 20),
            LocalDate.of(2026, 3, 25), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 25),
            LocalDate.of(2026, 3, 31), List.of("职业能力倾向测验", "综合应用能力"), organization,
            organizationType, title, family, employmentType, education, age, experience, titles, scope,
            "计算机相关专业", "https://example.gov.cn/jobs/" + jobId, evidenceComplete);
    }

    private static List<CoverageSignal> coverage() {
        return List.of(
            new CoverageSignal("HZ_HRSS_INSTITUTION", 2026, COMPLETE, LOADED_AT),
            new CoverageSignal("ZJ_HRSS_INSTITUTION", 2026, PARTIAL, LOADED_AT));
    }
}
