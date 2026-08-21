package com.careeros.application;

import com.careeros.application.DecisionPorts.JobContexts;
import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.application.JobAdmissionPorts.JobFieldEvidence;
import com.careeros.application.JobAdmissionPorts.FieldEvidenceCoverage;
import com.careeros.domain.DomainEnums.*;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.EligibilityAssessment;
import com.careeros.domain.EligibilityEvaluator;
import com.careeros.domain.FitEvaluator;
import com.careeros.domain.JobAdmission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class CandidateMatchService {
    private final RepositoryPorts.CandidateProfiles candidates;
    private final RepositoryPorts.CandidateFactConfirmations candidateFacts;
    private final JobContexts jobs;
    private final JobAdmissions admissions;
    private final EligibilityEvaluator eligibility;
    private final FitEvaluator fit;
    private final JobFieldEvidence fieldEvidence;

    public CandidateMatchService(
        RepositoryPorts.CandidateProfiles candidates,
        RepositoryPorts.CandidateFactConfirmations candidateFacts,
        JobContexts jobs,
        JobAdmissions admissions,
        EligibilityEvaluator eligibility,
        FitEvaluator fit,
        JobFieldEvidence fieldEvidence
    ) {
        this.candidates=Objects.requireNonNull(candidates); this.candidateFacts=Objects.requireNonNull(candidateFacts);
        this.jobs=Objects.requireNonNull(jobs); this.admissions=Objects.requireNonNull(admissions);
        this.eligibility=Objects.requireNonNull(eligibility); this.fit=Objects.requireNonNull(fit);
        this.fieldEvidence=Objects.requireNonNull(fieldEvidence);
    }

    public CandidateMatchService(
        RepositoryPorts.CandidateProfiles candidates,
        RepositoryPorts.CandidateFactConfirmations candidateFacts,
        JobContexts jobs,
        JobAdmissions admissions,
        EligibilityEvaluator eligibility,
        FitEvaluator fit
    ) {
        this(candidates, candidateFacts, jobs, admissions, eligibility, fit,
            jobId -> new FieldEvidenceCoverage(Set.of(), Set.of(), false, false, false));
    }

    public MatchPage list(UUID candidateId, MatchQuery query, Instant now) {
        Objects.requireNonNull(candidateId, "candidateId"); Objects.requireNonNull(query, "query");
        Objects.requireNonNull(now, "now");
        if (query.page() < 0) throw new IllegalArgumentException("page must not be negative");
        if (query.size() < 1 || query.size() > 100) throw new IllegalArgumentException("size must be between 1 and 100");
        CandidateProfile candidate = candidates.findById(candidateId)
            .orElseThrow(() -> new DecisionExceptions.CandidateNotFoundException("Candidate not found: " + candidateId));
        CandidateFacts facts = CandidateFacts.resolve(candidate, candidateFacts.findByCandidateId(candidateId));
        var targetAdmissions = new LinkedHashMap<UUID, JobAdmission>();
        admissions.findCandidateMatches().stream().filter(CandidateMatchService::isTarget)
            .forEach(admission -> targetAdmissions.put(admission.jobPostingId(), admission));
        var evidenceByJob = fieldEvidence.coverage(targetAdmissions.keySet());
        List<CandidateMatch> matches = jobs.findActiveByJobIds(targetAdmissions.keySet()).stream()
            .map(context -> match(candidate, facts, context,
                targetAdmissions.get(context.job().id()),
                evidenceByJob.getOrDefault(context.job().id(),
                    new FieldEvidenceCoverage(Set.of(), Set.of(), false, false, false)), now))
            .filter(value -> value.eligibilityStatus() != EligibilityStatus.INELIGIBLE
                && value.eligibilityStatus() != EligibilityStatus.LIKELY_INELIGIBLE)
            .sorted(order()).toList();
        int from = (int)Math.min((long)query.page() * query.size(), matches.size());
        int to = Math.min(from + query.size(), matches.size());
        return new MatchPage(matches.subList(from, to), query.page(), query.size(), matches.size());
    }

    private CandidateMatch match(
        CandidateProfile candidate, CandidateFacts facts, DecisionPorts.JobContext context,
        JobAdmission admission, FieldEvidenceCoverage evidenceCoverage, Instant now
    ) {
        var eligibilityResult = eligibility.evaluate(
            candidate, facts, context.job(), context.contentFingerprint(), now);
        var fitResult = fit.evaluate(
            candidate, facts, context.job(), context.organization(), context.contentFingerprint(), now);
        boolean identityConfirmed = context.job().employmentType() != EmploymentType.UNKNOWN;
        QualitySummary qualitySummary = qualitySummary(evidenceCoverage);
        var warnings = new LinkedHashSet<String>();
        if (!identityConfirmed) warnings.add("用工身份待官方证据确认");
        if (!qualitySummary.missingFields().isEmpty()) {
            warnings.add("缺少 " + qualitySummary.missingFields().size() + " 项官网字段证据");
        }
        if (!qualitySummary.conflictFields().isEmpty()) warnings.add("官网字段存在冲突，需人工核对");
        eligibilityResult.ruleResults().values().stream()
            .filter(result -> result.status() != EligibilityStatus.ELIGIBLE)
            .map(EligibilityAssessment.RuleResult::explanation).forEach(warnings::add);
        return new CandidateMatch(
            context.job().id(), context.job().externalJobCode(), context.job().title(),
            context.organization().name(), context.job().location(),
            eligibilityResult.status(), fitResult.score(), fitResult.coveragePercent(),
            context.job().employmentType(), identityConfirmed, admission.reasonCodes(), List.copyOf(warnings),
            context.job().sourceUrl(), context.contentFingerprint(),
            context.job().headcount(), context.job().jobFamily(), context.job().minimumEducation(),
            context.job().exactMajors(), context.job().acceptedGraduationYears(), context.job().maximumAge(),
            context.job().ageReferenceDate(), context.job().minimumExperienceYears(),
            context.job().requiredProfessionalTitles(), context.job().duties(), context.event().title(),
            context.event().publishedOn(), context.event().applicationStartsOn(), context.event().applicationEndsOn(),
            admission.dataQualityStatus(), context.job().supervisingDepartment(), context.job().jobCategory(),
            context.job().jobGrade(), context.job().educationRequirementText(), context.job().degreeRequirement(),
            context.job().majorRequirementText(), context.job().ageRequirementText(), context.job().genderRequirement(),
            context.job().candidateScope(), context.job().otherRequirements(), context.job().originalRequirementText(),
            context.job().interviewRatio(), context.job().professionalTestRequired(), context.job().contactPhone(),
            context.event().sourceUrl(), context.event().applicationStartsAt(), context.event().applicationEndsAt(),
            context.event().registrationUrl(), context.event().qualificationReviewEndsOn(), context.event().paymentEndsOn(),
            context.event().admissionTicketStartsOn(), context.event().admissionTicketEndsOn(),
            context.event().writtenExamOn(), context.event().writtenExamSubjects(), context.event().graduateRule(),
            context.event().overseasDegreeRule(), context.event().experienceEvidenceRule(),
            context.event().employmentStatement(), context.event().interviewRule(), qualitySummary);
    }

    private static QualitySummary qualitySummary(FieldEvidenceCoverage evidence) {
        List<String> required = List.of("title", "organizationName", "headcount",
            "educationRequirementText", "majorRequirementText", "ageRequirementText");
        var missing = new ArrayList<String>();
        required.stream().filter(field -> !evidence.explicit(field)).forEach(missing::add);
        if (!evidence.applicationDeadlineExplicit()) missing.add("applicationDeadline");
        if (!evidence.employmentIdentityExplicit()) missing.add("employmentType");
        if (!evidence.officialAttachment()) missing.add("officialAttachment");
        return new QualitySummary(required.size() + 3 - missing.size(), missing,
            evidence.conflictFields().stream().sorted().toList(), evidence.evidenceReferences());
    }

    private static boolean isTarget(JobAdmission admission) {
        return (admission.dataQualityStatus() == DataQualityStatus.VERIFIED
                || admission.dataQualityStatus() == DataQualityStatus.NORMALIZED
                || admission.dataQualityStatus() == DataQualityStatus.REVIEW_REQUIRED)
            && admission.targetScopeStatus() != TargetScopeStatus.EXCLUDED
            && admission.reasonCodes().contains(JobAdmissionReason.TARGET_TECHNICAL_ROLE);
    }

    private static Comparator<CandidateMatch> order() {
        return Comparator.comparingInt((CandidateMatch value) -> severity(value.eligibilityStatus()))
            .thenComparing(Comparator.comparing(CandidateMatch::employmentIdentityConfirmed).reversed())
            .thenComparingInt(value -> value.warnings().size())
            .thenComparing(Comparator.comparingInt(CandidateMatch::fitScore).reversed())
            .thenComparing(CandidateMatch::organizationName, Comparator.nullsLast(String::compareTo))
            .thenComparing(CandidateMatch::jobTitle)
            .thenComparing(CandidateMatch::jobId);
    }

    private static int severity(EligibilityStatus value) {
        return switch (value) {
            case ELIGIBLE -> 0;
            case LIKELY_ELIGIBLE -> 1;
            case UNCERTAIN -> 2;
            case LIKELY_INELIGIBLE -> 3;
            case INELIGIBLE -> 4;
        };
    }

    public record MatchQuery(int page, int size) {}
    public record MatchPage(List<CandidateMatch> items, int page, int size, long total) {
        public MatchPage { items = List.copyOf(items); }
    }
    public record QualitySummary(
        int verifiedFieldCount,
        List<String> missingFields,
        List<String> conflictFields,
        List<JobAdmissionPorts.EvidenceReference> evidenceReferences
    ) {
        public QualitySummary(int verifiedFieldCount, List<String> missingFields, List<String> conflictFields) {
            this(verifiedFieldCount, missingFields, conflictFields, List.of());
        }

        public QualitySummary {
            missingFields = List.copyOf(missingFields);
            conflictFields = List.copyOf(conflictFields);
            evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        }
    }
    public record CandidateMatch(
        UUID jobId, String externalJobCode, String jobTitle, String organizationName, String location,
        EligibilityStatus eligibilityStatus, int fitScore, int coveragePercent,
        EmploymentType employmentType, boolean employmentIdentityConfirmed,
        Set<JobAdmissionReason> admissionReasons, List<String> warnings,
        String sourceUrl, String jobContentFingerprint,
        int headcount, JobFamily jobFamily, EducationLevel minimumEducation,
        Set<String> exactMajors, Set<Integer> acceptedGraduationYears,
        Integer maximumAge, LocalDate ageReferenceDate, Integer minimumExperienceYears,
        Set<String> requiredProfessionalTitles, String duties, String eventTitle,
        LocalDate publishedOn, LocalDate applicationStartsOn, LocalDate applicationEndsOn,
        DataQualityStatus dataQualityStatus,
        String supervisingDepartment, String jobCategory, String jobGrade,
        String educationRequirementText, String degreeRequirement, String majorRequirementText,
        String ageRequirementText, String genderRequirement, String candidateScope,
        String otherRequirements, String originalRequirementText, String interviewRatio,
        Boolean professionalTestRequired, String contactPhone, String attachmentSourceUrl,
        OffsetDateTime applicationStartsAt, OffsetDateTime applicationEndsAt, String registrationUrl,
        OffsetDateTime qualificationReviewEndsOn, OffsetDateTime paymentEndsOn,
        LocalDate admissionTicketStartsOn, LocalDate admissionTicketEndsOn, LocalDate writtenExamOn,
        List<String> writtenExamSubjects, String graduateRule, String overseasDegreeRule,
        String experienceEvidenceRule, String employmentStatement, String interviewRule,
        QualitySummary qualitySummary
    ) {
        public CandidateMatch {
            admissionReasons = Set.copyOf(admissionReasons);
            warnings = List.copyOf(warnings);
            exactMajors = Set.copyOf(exactMajors);
            acceptedGraduationYears = Set.copyOf(acceptedGraduationYears);
            requiredProfessionalTitles = Set.copyOf(requiredProfessionalTitles);
            writtenExamSubjects = writtenExamSubjects == null ? List.of() : List.copyOf(writtenExamSubjects);
            qualitySummary = qualitySummary == null
                ? new QualitySummary(0, List.of(), List.of(), List.of()) : qualitySummary;
        }

        public CandidateMatch(
            UUID jobId, String externalJobCode, String jobTitle, String organizationName, String location,
            EligibilityStatus eligibilityStatus, int fitScore, int coveragePercent,
            EmploymentType employmentType, boolean employmentIdentityConfirmed,
            Set<JobAdmissionReason> admissionReasons, List<String> warnings,
            String sourceUrl, String jobContentFingerprint,
            int headcount, JobFamily jobFamily, EducationLevel minimumEducation,
            Set<String> exactMajors, Set<Integer> acceptedGraduationYears,
            Integer maximumAge, LocalDate ageReferenceDate, Integer minimumExperienceYears,
            Set<String> requiredProfessionalTitles, String duties, String eventTitle,
            LocalDate publishedOn, LocalDate applicationStartsOn, LocalDate applicationEndsOn
        ) {
            this(jobId, externalJobCode, jobTitle, organizationName, location, eligibilityStatus,
                fitScore, coveragePercent, employmentType, employmentIdentityConfirmed, admissionReasons,
                warnings, sourceUrl, jobContentFingerprint, headcount, jobFamily, minimumEducation,
                exactMajors, acceptedGraduationYears, maximumAge, ageReferenceDate, minimumExperienceYears,
                requiredProfessionalTitles, duties, eventTitle, publishedOn, applicationStartsOn,
                applicationEndsOn, DataQualityStatus.NORMALIZED,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                sourceUrl, null, null, null, null, null, null, null, null, List.of(), null, null, null, null, null,
                new QualitySummary(0, List.of(), List.of()));
        }
    }
}
