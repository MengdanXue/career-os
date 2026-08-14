package com.careeros.domain;

import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.EventType;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OrganizationType;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RecruitmentExtractionProposal(
    String schemaVersion,
    SourceProposal source,
    OrganizationProposal organization,
    EventProposal recruitmentEvent,
    List<JobProposal> jobs,
    List<String> warnings,
    double confidence,
    boolean completeSnapshot
) {
    public static final String SCHEMA_VERSION = "1.0.0";

    public RecruitmentExtractionProposal {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(recruitmentEvent, "recruitmentEvent");
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        requireConfidence(confidence);
    }

    public record SourceProposal(UUID evidenceId, String sourceUrl, String sourceTitle) {
        public SourceProposal {
            Objects.requireNonNull(evidenceId, "evidenceId");
            requireText(sourceUrl, "sourceUrl");
            requireText(sourceTitle, "sourceTitle");
        }
    }

    public record OrganizationProposal(String name, ExtractedFact<OrganizationType> organizationType) {
        public OrganizationProposal {
            requireText(name, "name");
            Objects.requireNonNull(organizationType, "organizationType");
        }
    }

    public record EventProposal(
        String title,
        Integer recruitmentYear,
        EventType eventType,
        ExtractedFact<LocalDate> publishedOn,
        ExtractedFact<LocalDate> applicationStartsOn,
        ExtractedFact<LocalDate> applicationEndsOn
    ) {
        public EventProposal {
            requireText(title, "title");
            if (recruitmentYear == null || recruitmentYear < 2000 || recruitmentYear > 2100) {
                throw new IllegalArgumentException("recruitmentYear is invalid");
            }
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(publishedOn, "publishedOn");
            Objects.requireNonNull(applicationStartsOn, "applicationStartsOn");
            Objects.requireNonNull(applicationEndsOn, "applicationEndsOn");
            if (applicationStartsOn.value() != null && applicationEndsOn.value() != null
                && applicationEndsOn.value().isBefore(applicationStartsOn.value())) {
                throw new IllegalArgumentException("application period is invalid");
            }
        }
    }

    public record JobProposal(
        ExtractedFact<String> title,
        String externalJobCode,
        ExtractedFact<Integer> headcount,
        ExtractedFact<EmploymentType> employmentType,
        String location,
        ExtractedFact<EducationLevel> minimumEducation,
        ExtractedFact<String> degree,
        ExtractedFact<String> majorText,
        ExtractedFact<Integer> maximumAge,
        ExtractedFact<Set<Integer>> acceptedGraduationYears,
        ExtractedFact<Integer> minimumExperienceYears,
        JobFamily jobFamily,
        String duties
    ) {
        public JobProposal {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(headcount, "headcount");
            Objects.requireNonNull(employmentType, "employmentType");
            Objects.requireNonNull(minimumEducation, "minimumEducation");
            Objects.requireNonNull(degree, "degree");
            Objects.requireNonNull(majorText, "majorText");
            Objects.requireNonNull(maximumAge, "maximumAge");
            Objects.requireNonNull(acceptedGraduationYears, "acceptedGraduationYears");
            Objects.requireNonNull(minimumExperienceYears, "minimumExperienceYears");
            Objects.requireNonNull(jobFamily, "jobFamily");
            if (headcount.value() != null && headcount.value() < 0) {
                throw new IllegalArgumentException("headcount must not be negative");
            }
            if (acceptedGraduationYears.value() != null) {
                acceptedGraduationYears = new ExtractedFact<>(
                    Set.copyOf(acceptedGraduationYears.value()),
                    acceptedGraduationYears.factStatus(),
                    acceptedGraduationYears.confidence(),
                    acceptedGraduationYears.evidenceFragmentIds(),
                    acceptedGraduationYears.interpretation());
            }
        }
    }

    private static void requireConfidence(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
