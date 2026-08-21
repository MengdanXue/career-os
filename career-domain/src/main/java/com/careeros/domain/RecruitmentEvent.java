package com.careeros.domain;

import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.EventType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RecruitmentEvent(
    UUID id, String title, int recruitmentYear, EventType eventType, LocalDate publishedOn,
    LocalDate applicationStartsOn, LocalDate applicationEndsOn, String sourceUrl,
    EmploymentType defaultEmploymentType, List<UUID> evidenceIds,
    OffsetDateTime applicationStartsAt, OffsetDateTime applicationEndsAt,
    LocalDate ageReferenceDate, String registrationUrl,
    OffsetDateTime qualificationReviewEndsOn, OffsetDateTime paymentEndsOn,
    LocalDate admissionTicketStartsOn, LocalDate admissionTicketEndsOn, LocalDate writtenExamOn,
    List<String> writtenExamSubjects, String graduateRule, String overseasDegreeRule,
    String experienceEvidenceRule, String employmentStatement, String interviewRule
) {
    public RecruitmentEvent {
        Objects.requireNonNull(id);
        require(title, "title");
        if (recruitmentYear < 2000 || recruitmentYear > 2100) {
            throw new IllegalArgumentException("recruitmentYear is invalid");
        }
        Objects.requireNonNull(eventType);
        require(sourceUrl, "sourceUrl");
        Objects.requireNonNull(defaultEmploymentType);
        if (applicationStartsOn != null && applicationEndsOn != null
            && applicationEndsOn.isBefore(applicationStartsOn)) {
            throw new IllegalArgumentException("application period is invalid");
        }
        if (applicationStartsAt != null && applicationEndsAt != null
            && applicationEndsAt.isBefore(applicationStartsAt)) {
            throw new IllegalArgumentException("application time period is invalid");
        }
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        writtenExamSubjects = writtenExamSubjects == null ? List.of() : List.copyOf(writtenExamSubjects);
    }

    public RecruitmentEvent(
        UUID id, String title, int recruitmentYear, EventType eventType, LocalDate publishedOn,
        LocalDate applicationStartsOn, LocalDate applicationEndsOn, String sourceUrl,
        EmploymentType defaultEmploymentType, List<UUID> evidenceIds
    ) {
        this(id, title, recruitmentYear, eventType, publishedOn, applicationStartsOn,
            applicationEndsOn, sourceUrl, defaultEmploymentType, evidenceIds,
            null, null, null, null, null, null, null, null, null,
            List.of(), null, null, null, null, null);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
