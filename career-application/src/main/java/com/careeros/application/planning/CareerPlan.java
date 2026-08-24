package com.careeros.application.planning;

import com.careeros.domain.DomainEnums.Gender;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CareerPlan(
    UUID candidateId,
    int targetYear,
    LocalDate asOf,
    CandidateSnapshot candidateSnapshot,
    Scenario currentScenario,
    List<Scenario> futureScenarios,
    GraduateTrackSummary graduateTrack,
    List<Route> recommendedRoutes,
    List<AgeWindow> ageWindows,
    List<RecruitmentWindow> recruitmentWindows,
    List<ExamPattern> examPatterns,
    ExamSummary examSummary,
    List<ProcessWindow> processWindows,
    List<AnnualSummary> historicalSummary,
    List<Risk> qualificationRisks,
    List<ActionItem> actionTimeline,
    DataCoverage dataCoverage,
    Instant generatedAt,
    String algorithmVersion
) {
    public CareerPlan {
        Objects.requireNonNull(candidateId); Objects.requireNonNull(asOf); Objects.requireNonNull(candidateSnapshot);
        Objects.requireNonNull(currentScenario); Objects.requireNonNull(graduateTrack);
        Objects.requireNonNull(examSummary); Objects.requireNonNull(dataCoverage); Objects.requireNonNull(generatedAt);
        futureScenarios = copy(futureScenarios); recommendedRoutes = copy(recommendedRoutes); ageWindows = copy(ageWindows);
        recruitmentWindows = copy(recruitmentWindows); examPatterns = copy(examPatterns); processWindows = copy(processWindows);
        historicalSummary = copy(historicalSummary); qualificationRisks = copy(qualificationRisks); actionTimeline = copy(actionTimeline);
    }

    private static <T> List<T> copy(List<T> values) { return values == null ? List.of() : List.copyOf(values); }

    public enum EvidenceStrength { STRONG, MODERATE, LIMITED, INSUFFICIENT }
    public enum RiskSeverity { HIGH, MEDIUM, LOW }
    public enum QualificationOutcome { ELIGIBLE, CONDITIONALLY_ELIGIBLE, UNCERTAIN, INELIGIBLE }
    public enum RouteRankingState { RANKED, LIMITED, NOT_COVERED, NO_TARGET_RECORDS, DATA_FAILURE }

    public record CandidateSnapshot(
        String displayName, LocalDate birthDate, Gender gender, String profileVersion,
        String educationSummary, Integer expectedMasterGraduationYear
    ) {}

    public record Scenario(String code, String label, String description, LocalDate effectiveFrom, boolean current) {}

    public record GraduateTrackSummary(
        String code, String label, String detail, QualificationOutcome outcome
    ) {}

    public record RepresentativeJob(
        UUID jobId, String organizationName, String title, int year, String sourceUrl, boolean evidenceComplete,
        List<JobScenarioOutcome> scenarioOutcomes,
        JobScenarioOutcome historicalActual,
        JobScenarioOutcome targetYearAnalog
    ) {
        public RepresentativeJob {
            scenarioOutcomes = copy(scenarioOutcomes);
        }

        public ProjectedJobOutcome projectedOutcome() {
            return new ProjectedJobOutcome(historicalActual, targetYearAnalog);
        }
    }

    public record ProjectedJobOutcome(JobScenarioOutcome historicalActual, JobScenarioOutcome targetYearAnalog) {}

    public record JobScenarioOutcome(
        String scenarioCode, QualificationOutcome outcome, List<String> reasons
    ) {
        public JobScenarioOutcome { reasons = copy(reasons); }
    }

    public record ScenarioBreakdown(
        String scenarioCode, int eligible, int conditionallyEligible, int uncertain, int ineligible,
        List<String> notes
    ) {
        public ScenarioBreakdown { notes = copy(notes); }
    }

    public record ScoreComponent(
        String code, String label, int score, int weight, String basis, boolean evidenceBacked
    ) {}

    public record Route(
        String code,
        String label,
        Integer priorityScore,
        String priorityLabel,
        int historicalJobCount,
        int eventCount,
        int formalJobCount,
        List<String> organizations,
        List<String> jobFamilies,
        List<String> applicableScenarios,
        List<String> advantages,
        List<String> risks,
        List<String> preparationFocus,
        List<RepresentativeJob> representativeJobs,
        List<ScenarioBreakdown> scenarioBreakdowns,
        List<ScoreComponent> scoreComponents,
        EvidenceStrength evidenceStrength,
        RouteRankingState rankingState,
        String rankingReason
    ) {
        public Route {
            organizations = copy(organizations); jobFamilies = copy(jobFamilies); applicableScenarios = copy(applicableScenarios);
            advantages = copy(advantages); risks = copy(risks); preparationFocus = copy(preparationFocus);
            representativeJobs = copy(representativeJobs); scenarioBreakdowns = copy(scenarioBreakdowns);
            scoreComponents = copy(scoreComponents);
        }
    }

    public record AgeWindow(
        int year, LocalDate referenceDate, int maximumAge, int candidateAge, boolean eligible,
        String label, String basis, boolean conditional
    ) {}

    public record RecruitmentWindow(int month, int eventCount, String label, String basis) {}
    public record ExamPattern(String subject, int eventCount) {}
    public record ExamSummary(
        int totalEvents,
        int writtenExamConfirmed, int writtenExamNotRequired, int writtenExamNotPublished,
        int writtenExamNotCollected, int writtenExamParseFailed, int writtenExamReviewRequired, int writtenExamUnknown,
        int professionalTestConfirmed, int professionalTestNotRequired, int professionalTestNotPublished,
        int professionalTestNotCollected, int professionalTestParseFailed, int professionalTestReviewRequired,
        int professionalTestUnknown,
        int interviewConfirmed, int interviewNotRequired, int interviewNotPublished,
        int interviewNotCollected, int interviewParseFailed, int interviewReviewRequired, int interviewUnknown,
        List<ExamPattern> subjects, List<ExamPattern> interviewMethods,
        int applicationToWrittenExamSamples, Integer averageApplicationToWrittenExamDays
    ) {
        public ExamSummary {
            subjects = copy(subjects);
            interviewMethods = copy(interviewMethods);
        }
    }
    public record ProcessWindow(String stage, int month, int eventCount) {}
    public record AnnualSummary(int year, int jobCount, int eventCount, int formalJobCount, boolean coverageComplete) {}
    public record Risk(String code, RiskSeverity severity, String title, String detail) {}
    public record ActionItem(LocalDate startsOn, LocalDate endsOn, String title, String detail, String status) {}
    public record DataCoverage(
        boolean complete, int sourceYearCount, int completeSourceYearCount, List<String> incompleteSourceYears,
        List<String> warnings, List<String> failedSections, Instant loadedAt
    ) {
        public DataCoverage {
            incompleteSourceYears = copy(incompleteSourceYears); warnings = copy(warnings);
            failedSections = copy(failedSections); Objects.requireNonNull(loadedAt);
        }
    }
}
