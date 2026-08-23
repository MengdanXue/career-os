package com.careeros.application.planning;

import com.careeros.domain.CandidateProfile;
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
        Instant loadedAt
    ) {
        public CareerPlanData {
            Objects.requireNonNull(candidate);
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
            coverage = coverage == null ? List.of() : List.copyOf(coverage);
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
        boolean evidenceComplete
    ) {
        public HistoricalJob {
            Objects.requireNonNull(jobId); Objects.requireNonNull(eventId);
            Objects.requireNonNull(organizationType); Objects.requireNonNull(jobFamily);
            Objects.requireNonNull(employmentType); Objects.requireNonNull(minimumEducation);
            writtenExamSubjects = writtenExamSubjects == null ? List.of() : List.copyOf(writtenExamSubjects);
            requiredProfessionalTitles = requiredProfessionalTitles == null ? Set.of() : Set.copyOf(requiredProfessionalTitles);
        }
    }

    public record CoverageSignal(String sourceCode, int year, CoverageStatus status, Instant updatedAt) {
        public CoverageSignal {
            Objects.requireNonNull(sourceCode); Objects.requireNonNull(status); Objects.requireNonNull(updatedAt);
        }
    }
}
