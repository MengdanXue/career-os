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
    List<Route> recommendedRoutes,
    List<AgeWindow> ageWindows,
    List<RecruitmentWindow> recruitmentWindows,
    List<ExamPattern> examPatterns,
    List<AnnualSummary> historicalSummary,
    List<Risk> qualificationRisks,
    List<ActionItem> actionTimeline,
    DataCoverage dataCoverage,
    Instant generatedAt,
    String algorithmVersion
) {
    public CareerPlan {
        Objects.requireNonNull(candidateId); Objects.requireNonNull(asOf); Objects.requireNonNull(candidateSnapshot);
        Objects.requireNonNull(currentScenario); Objects.requireNonNull(dataCoverage); Objects.requireNonNull(generatedAt);
        futureScenarios = copy(futureScenarios); recommendedRoutes = copy(recommendedRoutes); ageWindows = copy(ageWindows);
        recruitmentWindows = copy(recruitmentWindows); examPatterns = copy(examPatterns);
        historicalSummary = copy(historicalSummary); qualificationRisks = copy(qualificationRisks); actionTimeline = copy(actionTimeline);
    }

    private static <T> List<T> copy(List<T> values) { return values == null ? List.of() : List.copyOf(values); }

    public enum EvidenceStrength { STRONG, MODERATE, LIMITED, INSUFFICIENT }
    public enum RiskSeverity { HIGH, MEDIUM, LOW }

    public record CandidateSnapshot(
        String displayName, LocalDate birthDate, Gender gender, String profileVersion,
        String educationSummary, Integer expectedMasterGraduationYear
    ) {}

    public record Scenario(String code, String label, String description, LocalDate effectiveFrom, boolean current) {}

    public record RepresentativeJob(
        UUID jobId, String organizationName, String title, int year, String sourceUrl, boolean evidenceComplete
    ) {}

    public record Route(
        String code,
        String label,
        int priorityScore,
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
        EvidenceStrength evidenceStrength
    ) {
        public Route {
            organizations = copy(organizations); jobFamilies = copy(jobFamilies); applicableScenarios = copy(applicableScenarios);
            advantages = copy(advantages); risks = copy(risks); preparationFocus = copy(preparationFocus);
            representativeJobs = copy(representativeJobs);
        }
    }

    public record AgeWindow(
        int year, LocalDate referenceDate, int maximumAge, int candidateAge, boolean eligible,
        String label, String basis, boolean conditional
    ) {}

    public record RecruitmentWindow(int month, int eventCount, String label, String basis) {}
    public record ExamPattern(String subject, int eventCount) {}
    public record AnnualSummary(int year, int jobCount, int eventCount, int formalJobCount, boolean coverageComplete) {}
    public record Risk(String code, RiskSeverity severity, String title, String detail) {}
    public record ActionItem(LocalDate startsOn, LocalDate endsOn, String title, String detail, String status) {}
    public record DataCoverage(
        boolean complete, int sourceYearCount, int completeSourceYearCount, List<String> incompleteSourceYears,
        List<String> warnings, Instant loadedAt
    ) {
        public DataCoverage {
            incompleteSourceYears = copy(incompleteSourceYears); warnings = copy(warnings); Objects.requireNonNull(loadedAt);
        }
    }
}
