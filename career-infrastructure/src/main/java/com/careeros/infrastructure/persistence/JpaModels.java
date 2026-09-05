package com.careeros.infrastructure.persistence;

import com.careeros.domain.DomainEnums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

public final class JpaModels {
    private JpaModels() {}

    @MappedSuperclass
    public abstract static class UuidEntity {
        @Id @Column(nullable = false, updatable = false) UUID id;
        public UUID id() { return id; }
    }

    @Entity @Table(name = "recruitment_event")
    public static class RecruitmentEventEntity extends UuidEntity {
        @Column(nullable = false, length = 500) String title;
        @Column(name = "recruitment_year", nullable = false) int recruitmentYear;
        @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false) EventType eventType;
        @Column(name = "published_on") LocalDate publishedOn;
        @Column(name = "application_starts_on") LocalDate applicationStartsOn;
        @Column(name = "application_ends_on") LocalDate applicationEndsOn;
        @Column(name = "source_url", nullable = false) String sourceUrl;
        @Enumerated(EnumType.STRING) @Column(name = "default_employment_type", nullable = false) EmploymentType defaultEmploymentType;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_ids", columnDefinition = "jsonb", nullable = false) List<UUID> evidenceIds = new ArrayList<>();
        protected RecruitmentEventEntity() {}
    }

    @Entity @Table(name = "organization")
    public static class OrganizationEntity extends UuidEntity {
        @Column(nullable = false, length = 300) String name;
        @Enumerated(EnumType.STRING) @Column(name = "organization_type", nullable = false) OrganizationType organizationType;
        @Column(name = "administrative_level") String administrativeLevel;
        String province; String city; String district;
        @Column(name = "parent_organization_id") UUID parentOrganizationId;
        @Column(name = "official_website") String officialWebsite;
        protected OrganizationEntity() {}
    }

    @Entity @Table(name = "job_posting")
    public static class JobPostingEntity extends UuidEntity {
        @Column(name = "recruitment_event_id", nullable = false) UUID recruitmentEventId;
        @Column(name = "organization_id", nullable = false) UUID organizationId;
        @Column(name = "external_job_code") String externalJobCode;
        @Column(nullable = false, length = 300) String title;
        @Enumerated(EnumType.STRING) @Column(name = "job_family", nullable = false) JobFamily jobFamily;
        @Enumerated(EnumType.STRING) @Column(name = "employment_type", nullable = false) EmploymentType employmentType;
        String location;
        @Column(nullable = false) int headcount;
        @Enumerated(EnumType.STRING) @Column(name = "minimum_education", nullable = false) EducationLevel minimumEducation;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "exact_majors", columnDefinition = "jsonb", nullable = false) Set<String> exactMajors = new LinkedHashSet<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "accepted_graduation_years", columnDefinition = "jsonb", nullable = false) Set<Integer> acceptedGraduationYears = new LinkedHashSet<>();
        @Column(name = "maximum_age") Integer maximumAge;
        @Column(name = "age_reference_date") LocalDate ageReferenceDate;
        @Column(name = "minimum_experience_years") Integer minimumExperienceYears;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "required_professional_titles", columnDefinition = "jsonb", nullable = false) Set<String> requiredProfessionalTitles = new LinkedHashSet<>();
        String duties;
        @Column(name = "source_url", nullable = false) String sourceUrl;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_ids", columnDefinition = "jsonb", nullable = false) List<UUID> evidenceIds = new ArrayList<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "field_evidence", columnDefinition = "jsonb", nullable = false) Map<String, List<UUID>> fieldEvidence = new LinkedHashMap<>();
        @Column(name = "stable_job_key", unique = true) String stableJobKey;
        @Column(name = "content_fingerprint", length = 64) String contentFingerprint;
        @Column(nullable = false) boolean active = true;
        @Column(name = "first_seen_at", nullable = false) Instant firstSeenAt = Instant.now();
        @Column(name = "last_seen_at", nullable = false) Instant lastSeenAt = Instant.now();
        protected JobPostingEntity() {}
    }

    @Entity @Table(name = "candidate_profile")
    public static class CandidateProfileEntity extends UuidEntity {
        @Column(name = "display_name", nullable = false) String displayName;
        @Column(name = "birth_year", nullable = false) int birthYear;
        @Column(name = "birth_month", nullable = false) int birthMonth;
        @Column(name = "birth_day") Integer birthDay;
        @Enumerated(EnumType.STRING) @Column(name = "highest_education", nullable = false) EducationLevel highestEducation;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "majors", columnDefinition = "jsonb", nullable = false) Set<String> majors = new LinkedHashSet<>();
        @Column(name = "graduation_year") Integer graduationYear;
        @Column(name = "experience_years") Integer experienceYears;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "professional_titles", columnDefinition = "jsonb", nullable = false) Set<String> professionalTitles = new LinkedHashSet<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "preferred_locations", columnDefinition = "jsonb", nullable = false) List<String> preferredLocations = new ArrayList<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "accepted_employment_types", columnDefinition = "jsonb", nullable = false) Set<EmploymentType> acceptedEmploymentTypes = new LinkedHashSet<>();
        @Column(name = "profile_version", nullable = false) String profileVersion;
        protected CandidateProfileEntity() {}
    }

    @Entity @Table(name = "policy_rule")
    public static class PolicyRuleEntity extends UuidEntity {
        @Enumerated(EnumType.STRING) @Column(name = "rule_type", nullable = false) RuleType ruleType;
        @Column(nullable = false, length = 300) String name;
        String jurisdiction;
        @Column(name = "valid_from") LocalDate validFrom;
        @Column(name = "valid_until") LocalDate validUntil;
        @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) Map<String,String> parameters = new LinkedHashMap<>();
        @Column(name = "evidence_id") UUID evidenceId;
        protected PolicyRuleEntity() {}
    }

    @Entity @Table(name = "evidence")
    public static class EvidenceEntity extends UuidEntity {
        @Column(name = "source_artifact_id") UUID sourceArtifactId;
        @Enumerated(EnumType.STRING) @Column(name = "evidence_type", nullable = false) EvidenceType evidenceType;
        @Column(name = "source_url", nullable = false) String sourceUrl;
        @Column(name = "source_title") String sourceTitle;
        String excerpt;
        @Column(name = "content_hash", length = 64) String contentHash;
        @Column(name = "captured_at", nullable = false) Instant capturedAt;
        protected EvidenceEntity() {}
    }

    @Entity @Table(name = "eligibility_assessment")
    public static class EligibilityAssessmentEntity extends UuidEntity {
        @Column(name = "candidate_profile_id", nullable = false) UUID candidateProfileId;
        @Column(name = "job_posting_id", nullable = false) UUID jobPostingId;
        @Enumerated(EnumType.STRING) @Column(nullable = false) EligibilityStatus status;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "rule_results", columnDefinition = "jsonb", nullable = false) Map<String, Map<String,String>> ruleResults = new LinkedHashMap<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "required_confirmations", columnDefinition = "jsonb", nullable = false) List<String> requiredConfirmations = new ArrayList<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_ids", columnDefinition = "jsonb", nullable = false) List<UUID> evidenceIds = new ArrayList<>();
        @Column(name = "evaluator_version", nullable = false) String evaluatorVersion;
        @Column(name = "assessed_at", nullable = false) Instant assessedAt;
        protected EligibilityAssessmentEntity() {}
    }

    @Entity @Table(name = "opportunity")
    public static class OpportunityEntity extends UuidEntity {
        @Column(name = "candidate_profile_id", nullable = false) UUID candidateProfileId;
        @Column(name = "job_posting_id", nullable = false) UUID jobPostingId;
        @Column(name = "eligibility_assessment_id") UUID eligibilityAssessmentId;
        @Enumerated(EnumType.STRING) @Column(nullable = false) OpportunityStatus status;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "scorecard", columnDefinition = "jsonb", nullable = false) Map<String, Object> scorecard = new LinkedHashMap<>();
        @Enumerated(EnumType.STRING) @Column(name = "strategy_grade", nullable = false) StrategyGrade strategyGrade;
        @Column(name = "decision_note") String decisionNote;
        @Column(name = "created_at", nullable = false) Instant createdAt = Instant.now();
        @Column(name = "updated_at", nullable = false) Instant updatedAt = Instant.now();
        protected OpportunityEntity() {}
    }
}
