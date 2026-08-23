package com.careeros.application.planning;

import static com.careeros.application.planning.CareerPlanPorts.CoverageStatus.COMPLETE;
import static com.careeros.application.planning.CareerPlanPorts.CoverageStatus.PARTIAL;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.planning.CareerPlanPorts.CareerPlanData;
import com.careeros.application.planning.CareerPlanPorts.CoverageSignal;
import com.careeros.application.planning.CareerPlanPorts.HistoricalJob;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.CandidateEmploymentRecord;
import com.careeros.domain.CandidateEmploymentRecord.EmploymentMode;
import com.careeros.domain.CandidateEmploymentRecord.VerificationStatus;
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
        assertThat(plan.algorithmVersion()).isEqualTo("career-plan-v2");
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

    @Test
    void evaluatesEachRouteForEveryScenarioAndUsesOnlyEvidenceBackedScoreComponents() {
        var service = new CareerPlanService((id, from, to, asOf) ->
            new CareerPlanData(candidate(), jobs(), coverage(), LOADED_AT));

        var plan = service.generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22));
        var route = plan.recommendedRoutes().stream()
            .filter(value -> value.code().equals("PUBLIC_TECH")).findFirst().orElseThrow();

        assertThat(route.scenarioBreakdowns()).extracting(CareerPlan.ScenarioBreakdown::scenarioCode)
            .containsExactly("PRE_GRADUATION", "DEGREE_PENDING_VERIFICATION", "MASTER_VERIFIED");
        assertThat(route.scenarioBreakdowns()).filteredOn(value -> value.scenarioCode().equals("PRE_GRADUATION"))
            .singleElement().satisfies(value -> {
                assertThat(value.eligible()).isZero();
                assertThat(value.conditionallyEligible()).isEqualTo(1);
                assertThat(value.uncertain()).isZero();
                assertThat(value.ineligible()).isEqualTo(1);
            });
        assertThat(route.scenarioBreakdowns()).filteredOn(value -> value.scenarioCode().equals("MASTER_VERIFIED"))
            .singleElement().satisfies(value -> {
                assertThat(value.eligible()).isEqualTo(1);
                assertThat(value.uncertain()).isZero();
                assertThat(value.ineligible()).isEqualTo(1);
            });
        assertThat(route.scoreComponents()).extracting(CareerPlan.ScoreComponent::code)
            .containsExactly("ELIGIBILITY_READINESS", "EXPERIENCE_ADVANTAGE", "EMPLOYMENT_STABILITY", "HISTORICAL_SUPPLY", "PREPARATION_REUSE");
        assertThat(route.scoreComponents()).extracting(CareerPlan.ScoreComponent::weight)
            .containsExactly(30, 25, 20, 15, 10);
        int expectedScore = route.scoreComponents().stream()
            .mapToInt(value -> value.score() * value.weight()).sum() / 100;
        assertThat(route.priorityScore()).isEqualTo(expectedScore);
        assertThat(route.scoreComponents()).filteredOn(value -> value.code().equals("EXPERIENCE_ADVANTAGE"))
            .singleElement().satisfies(value -> {
                assertThat(value.score()).isZero();
                assertThat(value.evidenceBacked()).isTrue();
                assertThat(value.basis()).contains("没有可计入");
            });
        assertThat(route.advantages()).noneMatch(value -> value.contains("经验"));
    }

    @Test
    void mergesOverlappingVerifiedFullTimeEmploymentInsteadOfDoubleCountingIt() {
        var employment = List.of(
            new CandidateEmploymentRecord("甲单位", "Java 开发", LocalDate.of(2018, 1, 1), LocalDate.of(2020, 12, 31),
                EmploymentMode.FULL_TIME, VerificationStatus.VERIFIED, Set.of("社保")),
            new CandidateEmploymentRecord("乙单位", "系统开发", LocalDate.of(2020, 1, 1), LocalDate.of(2022, 12, 31),
                EmploymentMode.FULL_TIME, VerificationStatus.VERIFIED, Set.of("合同"))
        );
        var candidate = candidate(employment, 6);
        var service = new CareerPlanService((id, from, to, asOf) ->
            new CareerPlanData(candidate, jobs(), coverage(), LOADED_AT));

        var route = service.generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22)).recommendedRoutes().stream()
            .filter(value -> value.code().equals("PUBLIC_TECH")).findFirst().orElseThrow();

        assertThat(route.scoreComponents()).filteredOn(value -> value.code().equals("EXPERIENCE_ADVANTAGE"))
            .singleElement().satisfies(value -> {
                assertThat(value.score()).isEqualTo(100);
                assertThat(value.basis()).contains("5 个完整年");
            });
        assertThat(route.scenarioBreakdowns()).filteredOn(value -> value.scenarioCode().equals("PRE_GRADUATION"))
            .singleElement().extracting(CareerPlan.ScenarioBreakdown::eligible).isEqualTo(1);
    }

    @Test
    void doesNotInventGraduationOrCredentialDatesAndOmitsElapsedActions() {
        var education = List.of(
            new EducationRecord(null, "中国", EducationLevel.BACHELOR, "计算机科学与技术", 2014, 6,
                CompletionStatus.COMPLETED, CredentialVerificationStatus.NOT_REQUIRED),
            new EducationRecord("示例海外大学", "示例国", EducationLevel.MASTER, "计算机科学", 2027, null,
                CompletionStatus.EXPECTED, CredentialVerificationStatus.PLANNED)
        );
        var base = candidate();
        var unknownMonth = new CandidateProfile(base.id(), base.displayName(), base.birthDate(), base.highestEducation(),
            base.majors(), base.graduationYear(), base.experienceYears(), base.professionalTitles(), base.preferredLocations(),
            base.acceptedEmploymentTypes(), base.profileVersion(), base.skills(), base.researchKeywords(), base.targetJobFamilies(),
            base.preferredOrganizationTypes(), education, base.gender(), base.politicalAffiliation(), base.employmentRecords());
        var service = new CareerPlanService((id, from, to, asOf) ->
            new CareerPlanData(unknownMonth, jobs(), coverage(), LOADED_AT));

        var plan = service.generate(CANDIDATE_ID, 2026, LocalDate.of(2026, 8, 22));

        assertThat(plan.futureScenarios()).allSatisfy(value -> assertThat(value.effectiveFrom()).isNull());
        assertThat(plan.actionTimeline()).allSatisfy(value -> {
            assertThat(value.startsOn()).isAfterOrEqualTo(plan.asOf());
            assertThat(value.endsOn()).isAfterOrEqualTo(plan.asOf());
        });
        assertThat(plan.actionTimeline()).isSortedAccordingTo(java.util.Comparator.comparing(CareerPlan.ActionItem::startsOn));
    }

    @Test
    void exposesSectionFailuresInsteadOfPresentingMissingHistoryAsZeroRecruitment() {
        var service = new CareerPlanService((id, from, to, asOf) ->
            new CareerPlanData(candidate(), List.of(), coverage(), LOADED_AT, List.of("HISTORY")));

        var plan = service.generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22));

        assertThat(plan.dataCoverage().failedSections()).containsExactly("HISTORY");
        assertThat(plan.dataCoverage().warnings()).anyMatch(value -> value.contains("空值不得解释为零招聘"));
        assertThat(plan.dataCoverage().complete()).isFalse();
    }

    @Test
    void enforcesConfirmedExactMajorGraduationYearAndGenderRules() {
        var candidate = candidate();
        var constrained = constrainedJob();
        var service = new CareerPlanService((id, from, to, asOf) -> new CareerPlanData(
            candidate, List.of(constrained), coverage(), LOADED_AT, List.of(), CandidateFacts.confirmed(candidate)));

        var outcome = service.generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22)).recommendedRoutes().stream()
            .filter(route -> route.code().equals("PUBLIC_TECH")).findFirst().orElseThrow()
            .representativeJobs().getFirst().scenarioOutcomes().stream()
            .filter(value -> value.scenarioCode().equals("PRE_GRADUATION")).findFirst().orElseThrow();

        assertThat(outcome.outcome()).isEqualTo(CareerPlan.QualificationOutcome.INELIGIBLE);
        assertThat(outcome.reasons()).anyMatch(value -> value.contains("专业不在"));
        assertThat(outcome.reasons()).anyMatch(value -> value.contains("毕业年份"));
        assertThat(outcome.reasons()).anyMatch(value -> value.contains("性别不符合"));
    }

    @Test
    void keepsUnconfirmedCandidateFactsUncertainInsteadOfHardFailing() {
        var candidate = candidate();
        var service = new CareerPlanService((id, from, to, asOf) -> new CareerPlanData(
            candidate, List.of(constrainedJob()), coverage(), LOADED_AT, List.of(), CandidateFacts.resolve(candidate, List.of())));

        var outcome = service.generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22)).recommendedRoutes().stream()
            .filter(route -> route.code().equals("PUBLIC_TECH")).findFirst().orElseThrow()
            .representativeJobs().getFirst().scenarioOutcomes().stream()
            .filter(value -> value.scenarioCode().equals("PRE_GRADUATION")).findFirst().orElseThrow();

        assertThat(outcome.outcome()).isEqualTo(CareerPlan.QualificationOutcome.UNCERTAIN);
        assertThat(outcome.reasons()).anyMatch(value -> value.contains("专业事实尚未"));
        assertThat(outcome.reasons()).anyMatch(value -> value.contains("毕业年份尚未"));
        assertThat(outcome.reasons()).anyMatch(value -> value.contains("性别事实尚未"));
    }

    @Test
    void prioritizesClearlyTechnicalTitlesInRepresentativeJobs() {
        var event = UUID.fromString("10000000-0000-0000-0000-000000000005");
        var history = List.of(
            job("20000000-0000-0000-0000-000000000010", event, "甲单位", OrganizationType.PUBLIC_INSTITUTION,
                "综合管理", JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, EducationLevel.BACHELOR, 38, null, Set.of(), "不限", true),
            job("20000000-0000-0000-0000-000000000011", event, "乙单位", OrganizationType.PUBLIC_INSTITUTION,
                "工程管理", JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, EducationLevel.BACHELOR, 38, null, Set.of(), "不限", true),
            job("20000000-0000-0000-0000-000000000012", event, "丙单位", OrganizationType.PUBLIC_INSTITUTION,
                "行政管理", JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, EducationLevel.BACHELOR, 38, null, Set.of(), "不限", true),
            job("20000000-0000-0000-0000-000000000013", event, "丁单位", OrganizationType.PUBLIC_INSTITUTION,
                "信息安全", JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, EducationLevel.BACHELOR, 38, null, Set.of(), "不限", true)
        );
        var service = new CareerPlanService((id, from, to, asOf) ->
            new CareerPlanData(candidate(), history, coverage(), LOADED_AT));

        var route = service.generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22)).recommendedRoutes().stream()
            .filter(value -> value.code().equals("PUBLIC_TECH")).findFirst().orElseThrow();

        assertThat(route.representativeJobs()).extracting(CareerPlan.RepresentativeJob::title)
            .startsWith("信息安全");
    }

    @Test
    void unconfirmedBirthEmploymentAndPoliticalFactsCannotCreateHardConclusions() {
        var employment = List.of(new CandidateEmploymentRecord("甲单位", "系统开发", LocalDate.of(2018, 1, 1),
            LocalDate.of(2024, 12, 31), EmploymentMode.FULL_TIME, VerificationStatus.VERIFIED, Set.of("合同")));
        var base = candidate(employment, 7);
        var unconfirmed = new CandidateProfile(base.id(), base.displayName(), base.birthDate(), base.highestEducation(),
            base.majors(), base.graduationYear(), base.experienceYears(), base.professionalTitles(), base.preferredLocations(),
            base.acceptedEmploymentTypes(), base.profileVersion(), base.skills(), base.researchKeywords(), base.targetJobFamilies(),
            base.preferredOrganizationTypes(), base.educationRecords(), base.gender(), PoliticalAffiliation.NON_MEMBER, employment);
        var 党员岗 = job("20000000-0000-0000-0000-000000000014",
            UUID.fromString("10000000-0000-0000-0000-000000000006"), "甲单位", OrganizationType.PUBLIC_INSTITUTION,
            "信息系统", JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, EducationLevel.BACHELOR,
            30, 2, Set.of(), "其他要求\n中共党员\n应届毕业生", true);
        var service = new CareerPlanService((id, from, to, asOf) -> new CareerPlanData(
            unconfirmed, List.of(党员岗), coverage(), LOADED_AT, List.of(), CandidateFacts.resolve(unconfirmed, List.of())));

        var plan = service.generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22));
        var outcome = plan.recommendedRoutes().stream().filter(route -> route.code().equals("PUBLIC_TECH"))
            .findFirst().orElseThrow().representativeJobs().getFirst().scenarioOutcomes().getFirst();

        assertThat(outcome.outcome()).isEqualTo(CareerPlan.QualificationOutcome.UNCERTAIN);
        assertThat(outcome.reasons()).anyMatch(value -> value.contains("完整生日事实尚未确认"));
        assertThat(outcome.reasons()).anyMatch(value -> value.contains("工作经历事实尚未确认"));
        assertThat(outcome.reasons()).anyMatch(value -> value.contains("政治面貌尚未确认"));
        assertThat(outcome.reasons()).anyMatch(value -> value.contains("应届身份"));
        assertThat(plan.ageWindows()).isEmpty();
        assertThat(plan.recommendedRoutes().stream().filter(route -> route.code().equals("PUBLIC_TECH"))
            .findFirst().orElseThrow().scoreComponents()).filteredOn(value -> value.code().equals("PREPARATION_REUSE"))
            .singleElement().satisfies(value -> {
                assertThat(value.score()).isZero();
                assertThat(value.evidenceBacked()).isFalse();
            });
    }

    @Test
    void enforcesExplicitOverseasCredentialDeadlineAndDoesNotGuessMajorCategories() {
        var base = job("20000000-0000-0000-0000-000000000015",
            UUID.fromString("10000000-0000-0000-0000-000000000007"), "甲单位", OrganizationType.PUBLIC_INSTITUTION,
            "信息安全", JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, EducationLevel.MASTER,
            38, null, Set.of(), "不限", true);
        var constrained = new HistoricalJob(base.jobId(), base.eventId(), base.year(), base.publishedOn(),
            base.applicationStartsOn(), base.applicationEndsOn(), base.writtenExamOn(), base.ageReferenceDate(),
            base.writtenExamSubjects(), base.organizationName(), base.organizationType(), base.title(), base.jobFamily(),
            base.employmentType(), base.minimumEducation(), base.maximumAge(), base.minimumExperienceYears(),
            base.requiredProfessionalTitles(), base.candidateScope(), base.requirements(), base.sourceUrl(), true,
            List.of("计算机类相关专业"), List.of(), null, "国外学历学位报名截止前需提供教育部留学服务中心认证");
        var service = new CareerPlanService((id, from, to, asOf) -> new CareerPlanData(
            candidate(), List.of(constrained), coverage(), LOADED_AT, List.of(), CandidateFacts.confirmed(candidate())));

        var outcomes = service.generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22)).recommendedRoutes().stream()
            .filter(route -> route.code().equals("PUBLIC_TECH")).findFirst().orElseThrow()
            .representativeJobs().getFirst().scenarioOutcomes();

        assertThat(outcomes).filteredOn(value -> value.scenarioCode().equals("DEGREE_PENDING_VERIFICATION"))
            .singleElement().satisfies(value -> {
                assertThat(value.outcome()).isEqualTo(CareerPlan.QualificationOutcome.INELIGIBLE);
                assertThat(value.reasons()).anyMatch(reason -> reason.contains("留服认证"));
            });
        assertThat(outcomes).filteredOn(value -> value.scenarioCode().equals("MASTER_VERIFIED"))
            .singleElement().satisfies(value -> {
                assertThat(value.outcome()).isEqualTo(CareerPlan.QualificationOutcome.UNCERTAIN);
                assertThat(value.reasons()).anyMatch(reason -> reason.contains("专业目录"));
            });
    }

    @Test
    void distinguishesCredentialDeadlineStagesAndPoliticalRuleStrength() {
        var base = job("20000000-0000-0000-0000-000000000016",
            UUID.fromString("10000000-0000-0000-0000-000000000008"), "甲单位", OrganizationType.PUBLIC_INSTITUTION,
            "信息系统", JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, EducationLevel.MASTER,
            38, null, Set.of(), "不限", true);

        assertThat(outcomeFor(withCredentialRule(base, "录用前须完成留服认证"), candidate(), "DEGREE_PENDING_VERIFICATION"))
            .satisfies(value -> {
                assertThat(value.outcome()).isEqualTo(CareerPlan.QualificationOutcome.CONDITIONALLY_ELIGIBLE);
                assertThat(value.reasons()).anyMatch(reason -> reason.contains("录用或报到前"));
            });
        assertThat(outcomeFor(withCredentialRule(base, "境外学历须完成留服认证"), candidate(), "DEGREE_PENDING_VERIFICATION"))
            .satisfies(value -> {
                assertThat(value.outcome()).isEqualTo(CareerPlan.QualificationOutcome.UNCERTAIN);
                assertThat(value.reasons()).anyMatch(reason -> reason.contains("截止阶段不明确"));
            });
        assertThat(outcomeFor(withCredentialRule(base, "部分项目无需认证；境外学历报名截止前须完成留服认证"),
            candidate(), "DEGREE_PENDING_VERIFICATION").outcome()).isEqualTo(CareerPlan.QualificationOutcome.INELIGIBLE);
        assertThat(outcomeFor(withCredentialRule(base, "国内学历无需认证，境外学历报名截止前须完成留服认证"),
            candidate(), "DEGREE_PENDING_VERIFICATION").outcome()).isEqualTo(CareerPlan.QualificationOutcome.INELIGIBLE);
        assertThat(outcomeFor(withCredentialRule(base, "报名结束后，录用前须完成留服认证"),
            candidate(), "DEGREE_PENDING_VERIFICATION")).satisfies(value -> {
                assertThat(value.outcome()).isEqualTo(CareerPlan.QualificationOutcome.CONDITIONALLY_ELIGIBLE);
                assertThat(value.reasons()).anyMatch(reason -> reason.contains("录用或报到前"));
            });
        assertThat(outcomeFor(withCredentialRule(base, "资格复审前须完成留服认证"),
            candidate(), "DEGREE_PENDING_VERIFICATION").outcome())
            .isEqualTo(CareerPlan.QualificationOutcome.CONDITIONALLY_ELIGIBLE);

        var nonMember = withPoliticalAffiliation(candidate(), PoliticalAffiliation.NON_MEMBER);
        var bachelorBase = job("20000000-0000-0000-0000-000000000017",
            UUID.fromString("10000000-0000-0000-0000-000000000009"), "乙单位", OrganizationType.PUBLIC_INSTITUTION,
            "信息安全", JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, EducationLevel.BACHELOR,
            38, null, Set.of(), "政治面貌不限，中共党员优先", true);
        assertThat(outcomeFor(bachelorBase, nonMember, "PRE_GRADUATION")).satisfies(value -> {
            assertThat(value.outcome()).isEqualTo(CareerPlan.QualificationOutcome.ELIGIBLE);
            assertThat(value.reasons()).anyMatch(reason -> reason.contains("优先条件"));
        });
        for (String preference : List.of("中共党员（含预备党员）优先", "具有中共党员身份者优先", "党员身份不限")) {
            var preferenceJob = new HistoricalJob(bachelorBase.jobId(), bachelorBase.eventId(), bachelorBase.year(),
                bachelorBase.publishedOn(), bachelorBase.applicationStartsOn(), bachelorBase.applicationEndsOn(),
                bachelorBase.writtenExamOn(), bachelorBase.ageReferenceDate(), bachelorBase.writtenExamSubjects(),
                bachelorBase.organizationName(), bachelorBase.organizationType(), bachelorBase.title(), bachelorBase.jobFamily(),
                bachelorBase.employmentType(), bachelorBase.minimumEducation(), bachelorBase.maximumAge(),
                bachelorBase.minimumExperienceYears(), bachelorBase.requiredProfessionalTitles(), preference,
                bachelorBase.requirements(), bachelorBase.sourceUrl(), bachelorBase.evidenceComplete(), bachelorBase.exactMajors(),
                bachelorBase.acceptedGraduationYears(), bachelorBase.genderRequirement(), bachelorBase.overseasDegreeRule());
            assertThat(outcomeFor(preferenceJob, nonMember, "PRE_GRADUATION").outcome())
                .as(preference).isEqualTo(CareerPlan.QualificationOutcome.ELIGIBLE);
        }
        for (String required : List.of("中共党员，具有相关经验者优先", "中共党员，年龄不限")) {
            var requiredJob = new HistoricalJob(bachelorBase.jobId(), bachelorBase.eventId(), bachelorBase.year(),
                bachelorBase.publishedOn(), bachelorBase.applicationStartsOn(), bachelorBase.applicationEndsOn(),
                bachelorBase.writtenExamOn(), bachelorBase.ageReferenceDate(), bachelorBase.writtenExamSubjects(),
                bachelorBase.organizationName(), bachelorBase.organizationType(), bachelorBase.title(), bachelorBase.jobFamily(),
                bachelorBase.employmentType(), bachelorBase.minimumEducation(), bachelorBase.maximumAge(),
                bachelorBase.minimumExperienceYears(), bachelorBase.requiredProfessionalTitles(), required,
                bachelorBase.requirements(), bachelorBase.sourceUrl(), bachelorBase.evidenceComplete(), bachelorBase.exactMajors(),
                bachelorBase.acceptedGraduationYears(), bachelorBase.genderRequirement(), bachelorBase.overseasDegreeRule());
            assertThat(outcomeFor(requiredJob, nonMember, "PRE_GRADUATION").outcome())
                .as(required).isEqualTo(CareerPlan.QualificationOutcome.INELIGIBLE);
        }
        var alternative = new HistoricalJob(bachelorBase.jobId(), bachelorBase.eventId(), bachelorBase.year(),
            bachelorBase.publishedOn(), bachelorBase.applicationStartsOn(), bachelorBase.applicationEndsOn(),
            bachelorBase.writtenExamOn(), bachelorBase.ageReferenceDate(), bachelorBase.writtenExamSubjects(),
            bachelorBase.organizationName(), bachelorBase.organizationType(), bachelorBase.title(), bachelorBase.jobFamily(),
            bachelorBase.employmentType(), bachelorBase.minimumEducation(), bachelorBase.maximumAge(),
            bachelorBase.minimumExperienceYears(), bachelorBase.requiredProfessionalTitles(), "中共党员或民主党派",
            bachelorBase.requirements(), bachelorBase.sourceUrl(), bachelorBase.evidenceComplete(), bachelorBase.exactMajors(),
            bachelorBase.acceptedGraduationYears(), bachelorBase.genderRequirement(), bachelorBase.overseasDegreeRule());
        assertThat(outcomeFor(alternative, nonMember, "PRE_GRADUATION")).satisfies(value -> {
            assertThat(value.outcome()).isEqualTo(CareerPlan.QualificationOutcome.UNCERTAIN);
            assertThat(value.reasons()).anyMatch(reason -> reason.contains("备选或歧义"));
        });
    }

    private static CandidateProfile candidate() {
        return candidate(List.of(), null);
    }

    private static CandidateProfile candidate(List<CandidateEmploymentRecord> employment, Integer experienceYears) {
        return new CandidateProfile(
            CANDIDATE_ID, "测试候选人", new PartialDate(1992, 12, 31), EducationLevel.BACHELOR,
            Set.of("计算机科学与技术"), 2014, experienceYears, Set.of("中级：计算机应用（评审）"),
            List.of("杭州", "浙江"), Set.of(EmploymentType.ESTABLISHMENT, EmploymentType.PUBLIC_INSTITUTION_FORMAL),
            "profile-test", Set.of("Java", "PostgreSQL"), Set.of("AI"), Set.of(JobFamily.SOFTWARE, JobFamily.INFORMATION_SYSTEMS),
            Set.of(OrganizationType.PUBLIC_INSTITUTION, OrganizationType.UNIVERSITY, OrganizationType.HOSPITAL),
            List.of(
                new EducationRecord(null, "中国", EducationLevel.BACHELOR, "计算机科学与技术", 2014, 6, CompletionStatus.COMPLETED, CredentialVerificationStatus.NOT_REQUIRED),
                new EducationRecord("示例海外大学", "示例国", EducationLevel.MASTER, "计算机科学", 2027, 6, CompletionStatus.EXPECTED, CredentialVerificationStatus.PLANNED)
            ), Gender.FEMALE, PoliticalAffiliation.UNKNOWN, employment);
    }

    private static CandidateProfile withPoliticalAffiliation(CandidateProfile base, PoliticalAffiliation affiliation) {
        return new CandidateProfile(base.id(), base.displayName(), base.birthDate(), base.highestEducation(),
            base.majors(), base.graduationYear(), base.experienceYears(), base.professionalTitles(), base.preferredLocations(),
            base.acceptedEmploymentTypes(), base.profileVersion(), base.skills(), base.researchKeywords(), base.targetJobFamilies(),
            base.preferredOrganizationTypes(), base.educationRecords(), base.gender(), affiliation, base.employmentRecords());
    }

    private static HistoricalJob withCredentialRule(HistoricalJob base, String rule) {
        return new HistoricalJob(base.jobId(), base.eventId(), base.year(), base.publishedOn(), base.applicationStartsOn(),
            base.applicationEndsOn(), base.writtenExamOn(), base.ageReferenceDate(), base.writtenExamSubjects(),
            base.organizationName(), base.organizationType(), base.title(), base.jobFamily(), base.employmentType(),
            base.minimumEducation(), base.maximumAge(), base.minimumExperienceYears(), base.requiredProfessionalTitles(),
            base.candidateScope(), base.requirements(), base.sourceUrl(), base.evidenceComplete(), base.exactMajors(),
            base.acceptedGraduationYears(), base.genderRequirement(), rule);
    }

    private static CareerPlan.JobScenarioOutcome outcomeFor(HistoricalJob job, CandidateProfile candidate,
        String scenarioCode) {
        var service = new CareerPlanService((id, from, to, asOf) -> new CareerPlanData(
            candidate, List.of(job), coverage(), LOADED_AT, List.of(), CandidateFacts.confirmed(candidate)));
        return service.generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 22)).recommendedRoutes().stream()
            .filter(route -> route.code().equals("PUBLIC_TECH")).findFirst().orElseThrow()
            .representativeJobs().getFirst().scenarioOutcomes().stream()
            .filter(value -> value.scenarioCode().equals(scenarioCode)).findFirst().orElseThrow();
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

    private static HistoricalJob constrainedJob() {
        return new HistoricalJob(UUID.fromString("20000000-0000-0000-0000-000000000004"),
            UUID.fromString("10000000-0000-0000-0000-000000000004"), 2026,
            LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 25), LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 25), LocalDate.of(2026, 3, 31), List.of("职业能力倾向测验"),
            "杭州市数字治理中心", OrganizationType.PUBLIC_INSTITUTION, "地理信息技术",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.PUBLIC_INSTITUTION_FORMAL, EducationLevel.BACHELOR,
            38, null, Set.of(), "社会人员", "限男性，地理信息科学专业，2025届", "https://example.gov.cn/jobs/4", true,
            List.of("地理信息科学"), List.of(2025), "男性", null);
    }

    private static List<CoverageSignal> coverage() {
        return List.of(
            new CoverageSignal("HZ_HRSS_INSTITUTION", 2026, COMPLETE, LOADED_AT),
            new CoverageSignal("ZJ_HRSS_INSTITUTION", 2026, PARTIAL, LOADED_AT));
    }
}
