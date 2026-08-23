package com.careeros.application.planning;

import com.careeros.domain.CandidateProfile;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OrganizationType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class CareerPlanPorts {
    private CareerPlanPorts() {}

    @FunctionalInterface
    public interface CareerPlanQuery {
        CareerPlanData load(UUID candidateId, int fromYear, int toYear, LocalDate asOf);
    }

    public enum CoverageStatus {
        NOT_DISCOVERED, DISCOVERED_NOT_FETCHED, ACCESS_FAILED, FETCHED_NOT_PARSED,
        PARTIAL, COMPLETE, NO_TARGET_RECORDS
    }

    public record CareerPlanData(
        CandidateProfile candidate,
        List<HistoricalJob> jobs,
        List<CoverageSignal> coverage,
        Instant loadedAt,
        List<String> failedSections,
        CandidateFacts candidateFacts
    ) {
        public CareerPlanData(CandidateProfile candidate, List<HistoricalJob> jobs,
            List<CoverageSignal> coverage, Instant loadedAt) {
            this(candidate, jobs, coverage, loadedAt, List.of(), CandidateFacts.confirmed(candidate));
        }
        public CareerPlanData(CandidateProfile candidate, List<HistoricalJob> jobs,
            List<CoverageSignal> coverage, Instant loadedAt, List<String> failedSections) {
            this(candidate, jobs, coverage, loadedAt, failedSections, CandidateFacts.confirmed(candidate));
        }
        public CareerPlanData {
            Objects.requireNonNull(candidate);
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
            coverage = coverage == null ? List.of() : List.copyOf(coverage);
            failedSections = failedSections == null ? List.of() : List.copyOf(failedSections);
            candidateFacts = candidateFacts == null ? CandidateFacts.resolve(candidate, List.of()) : candidateFacts;
            Objects.requireNonNull(loadedAt);
        }
    }

    public record HistoricalJob(
        UUID jobId,
        UUID eventId,
        int year,
        LocalDate publishedOn,
        LocalDate applicationStartsOn,
        LocalDate applicationEndsOn,
        LocalDate writtenExamOn,
        LocalDate ageReferenceDate,
        List<String> writtenExamSubjects,
        String organizationName,
        OrganizationType organizationType,
        String title,
        JobFamily jobFamily,
        EmploymentType employmentType,
        EducationLevel minimumEducation,
        Integer maximumAge,
        Integer minimumExperienceYears,
        Set<String> requiredProfessionalTitles,
        String candidateScope,
        String requirements,
        String sourceUrl,
        boolean evidenceComplete,
        List<String> exactMajors,
        List<Integer> acceptedGraduationYears,
        String genderRequirement,
        String overseasDegreeRule
    ) {
        public HistoricalJob(UUID jobId, UUID eventId, int year, LocalDate publishedOn,
            LocalDate applicationStartsOn, LocalDate applicationEndsOn, LocalDate writtenExamOn,
            LocalDate ageReferenceDate, List<String> writtenExamSubjects, String organizationName,
            OrganizationType organizationType, String title, JobFamily jobFamily, EmploymentType employmentType,
            EducationLevel minimumEducation, Integer maximumAge, Integer minimumExperienceYears,
            Set<String> requiredProfessionalTitles, String candidateScope, String requirements,
            String sourceUrl, boolean evidenceComplete) {
            this(jobId, eventId, year, publishedOn, applicationStartsOn, applicationEndsOn, writtenExamOn,
                ageReferenceDate, writtenExamSubjects, organizationName, organizationType, title, jobFamily,
                employmentType, minimumEducation, maximumAge, minimumExperienceYears, requiredProfessionalTitles,
                candidateScope, requirements, sourceUrl, evidenceComplete, List.of(), List.of(), null, null);
        }
        public HistoricalJob {
            Objects.requireNonNull(jobId); Objects.requireNonNull(eventId);
            Objects.requireNonNull(organizationType); Objects.requireNonNull(jobFamily);
            Objects.requireNonNull(employmentType); Objects.requireNonNull(minimumEducation);
            writtenExamSubjects = writtenExamSubjects == null ? List.of() : List.copyOf(writtenExamSubjects);
            requiredProfessionalTitles = requiredProfessionalTitles == null ? Set.of() : Set.copyOf(requiredProfessionalTitles);
            exactMajors = exactMajors == null ? List.of() : List.copyOf(exactMajors);
            acceptedGraduationYears = acceptedGraduationYears == null ? List.of() : List.copyOf(acceptedGraduationYears);
        }
    }

    public record CoverageSignal(String sourceCode, int year, CoverageStatus status, Instant updatedAt) {
        public CoverageSignal {
            Objects.requireNonNull(sourceCode); Objects.requireNonNull(status); Objects.requireNonNull(updatedAt);
        }
    }
}
