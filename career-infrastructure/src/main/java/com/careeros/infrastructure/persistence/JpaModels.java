package com.careeros.infrastructure.persistence;

import com.careeros.domain.DomainEnums.*;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.CandidateEmploymentRecord;
import com.careeros.domain.CandidateEmploymentRecord.EmploymentMode;
import com.careeros.domain.CandidateEmploymentRecord.VerificationStatus;
import com.careeros.domain.EducationRecord;
import com.careeros.domain.EducationRecord.CompletionStatus;
import com.careeros.domain.EducationRecord.CredentialVerificationStatus;
import com.careeros.domain.GraduateEligibilityRule;
import com.careeros.domain.GraduateEligibilityRule.EvidenceState;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.io.Serializable;
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
        @Column(name = "application_starts_at") OffsetDateTime applicationStartsAt;
        @Column(name = "application_ends_at") OffsetDateTime applicationEndsAt;
        @Column(name = "age_reference_date") LocalDate ageReferenceDate;
        @Column(name = "registration_url") String registrationUrl;
        @Column(name = "qualification_review_ends_on") OffsetDateTime qualificationReviewEndsOn;
        @Column(name = "payment_ends_on") OffsetDateTime paymentEndsOn;
        @Column(name = "admission_ticket_starts_on") LocalDate admissionTicketStartsOn;
        @Column(name = "admission_ticket_ends_on") LocalDate admissionTicketEndsOn;
        @Column(name = "written_exam_on") LocalDate writtenExamOn;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "written_exam_subjects", columnDefinition = "jsonb", nullable = false) List<String> writtenExamSubjects = new ArrayList<>();
        @Column(name = "graduate_rule") String graduateRule;
        @Column(name = "overseas_degree_rule") String overseasDegreeRule;
        @Column(name = "experience_evidence_rule") String experienceEvidenceRule;
        @Column(name = "employment_statement") String employmentStatement;
        @Column(name = "interview_rule") String interviewRule;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "graduate_rule_json", columnDefinition = "jsonb")
        GraduateEligibilityRule graduateRuleJson;
        @Enumerated(EnumType.STRING) @Column(name = "written_exam_state", nullable = false)
        EvidenceState writtenExamState = EvidenceState.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "professional_test_state", nullable = false)
        EvidenceState professionalTestState = EvidenceState.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "interview_state", nullable = false)
        EvidenceState interviewState = EvidenceState.UNKNOWN;
        @Column(name = "interview_on") LocalDate interviewOn;
        @Column(name = "interview_method") String interviewMethod;
        @Column(name = "score_formula") String scoreFormula;
        @Enumerated(EnumType.STRING) @Column(name = "notice_state", nullable = false)
        EvidenceState noticeState = EvidenceState.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "application_state", nullable = false)
        EvidenceState applicationState = EvidenceState.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "qualification_review_state", nullable = false)
        EvidenceState qualificationReviewState = EvidenceState.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "payment_state", nullable = false)
        EvidenceState paymentState = EvidenceState.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "admission_ticket_state", nullable = false)
        EvidenceState admissionTicketState = EvidenceState.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "physical_exam_state", nullable = false)
        EvidenceState physicalExamState = EvidenceState.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "investigation_state", nullable = false)
        EvidenceState investigationState = EvidenceState.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "publication_state", nullable = false)
        EvidenceState publicationState = EvidenceState.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "appointment_state", nullable = false)
        EvidenceState appointmentState = EvidenceState.UNKNOWN;
        @Column(name = "physical_exam_rule") String physicalExamRule;
        @Column(name = "investigation_rule") String investigationRule;
        @Column(name = "publication_rule") String publicationRule;
        @Column(name = "appointment_rule") String appointmentRule;
        @Column(name = "legacy_workbook_snapshot", nullable = false) boolean legacyWorkbookSnapshot;
        @Column(name = "workbook_identity") String workbookIdentity;
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
        @Column(name = "supervising_department") String supervisingDepartment;
        @Column(name = "job_category") String jobCategory;
        @Column(name = "job_grade") String jobGrade;
        @Column(name = "education_requirement_text") String educationRequirementText;
        @Column(name = "degree_requirement") String degreeRequirement;
        @Column(name = "major_requirement_text") String majorRequirementText;
        @Column(name = "age_requirement_text") String ageRequirementText;
        @Column(name = "gender_requirement") String genderRequirement;
        @Column(name = "candidate_scope") String candidateScope;
        @Column(name = "other_requirements") String otherRequirements;
        @Column(name = "original_requirement_text") String originalRequirementText;
        @Column(name = "interview_ratio") String interviewRatio;
        @Column(name = "professional_test_required") Boolean professionalTestRequired;
        @Column(name = "contact_phone") String contactPhone;
        @Column(name = "actual_employer", length = 500) String actualEmployer;
        @Column(length = 500) String worksite;
        @Column(name = "employment_identity_evidence", columnDefinition = "text") String employmentEvidence;
        @Column(name = "source_url", nullable = false) String sourceUrl;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_ids", columnDefinition = "jsonb", nullable = false) List<UUID> evidenceIds = new ArrayList<>();
        @Column(name = "stable_job_key", unique = true) String stableJobKey;
        @Column(name = "content_fingerprint", length = 64) String contentFingerprint;
        @Column(nullable = false) boolean active = true;
        @Column(name = "first_seen_at", nullable = false) Instant firstSeenAt = Instant.now();
        @Column(name = "last_seen_at", nullable = false) Instant lastSeenAt = Instant.now();
        protected JobPostingEntity() {}
    }

    @Entity @Table(name = "job_admission")
    public static class JobAdmissionEntity {
        @Id @Column(name = "job_posting_id", nullable = false, updatable = false) UUID jobPostingId;
        @Enumerated(EnumType.STRING) @Column(name = "data_quality_status", nullable = false) DataQualityStatus dataQualityStatus;
        @Enumerated(EnumType.STRING) @Column(name = "target_scope_status", nullable = false) TargetScopeStatus targetScopeStatus;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "reason_codes", columnDefinition = "jsonb", nullable = false) Set<JobAdmissionReason> reasonCodes = new LinkedHashSet<>();
        @Column(name = "evaluator_version", nullable = false) String evaluatorVersion;
        @Column(name = "assessed_at", nullable = false) Instant assessedAt;
        @Column(name = "human_verified", nullable = false) boolean humanVerified;
        protected JobAdmissionEntity() {}
    }

    @Embeddable
    public static class CandidateEducationValue {
        @Column(name = "institution_name") String institutionName;
        @Column(name = "country_or_region") String countryOrRegion;
        @Enumerated(EnumType.STRING) @Column(name = "education_level", nullable = false) EducationLevel educationLevel;
        @Column(name = "major_name", nullable = false) String majorName;
        @Column(name = "graduation_year") Integer graduationYear;
        @Column(name = "graduation_month") Integer graduationMonth;
        @Enumerated(EnumType.STRING) @Column(name = "completion_status", nullable = false) CompletionStatus completionStatus;
        @Enumerated(EnumType.STRING) @Column(name = "credential_verification_status", nullable = false) CredentialVerificationStatus credentialVerificationStatus;
        protected CandidateEducationValue() {}
    }

    @Embeddable
    public static class CandidateEmploymentValue {
        @Column(name = "employer_name", nullable = false) String employerName;
        @Column(name = "role_title", nullable = false) String roleTitle;
        @Column(name = "starts_on", nullable = false) LocalDate startsOn;
        @Column(name = "ends_on") LocalDate endsOn;
        @Enumerated(EnumType.STRING) @Column(name = "employment_mode", nullable = false) EmploymentMode employmentMode;
        @Enumerated(EnumType.STRING) @Column(name = "verification_status", nullable = false) VerificationStatus verificationStatus;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_types", columnDefinition = "jsonb", nullable = false) Set<String> evidenceTypes = new LinkedHashSet<>();
        protected CandidateEmploymentValue() {}
    }

    @Entity @Table(name = "candidate_profile")
    public static class CandidateProfileEntity extends UuidEntity {
        @Column(name = "display_name", nullable = false) String displayName;
        @Column(name = "birth_year", nullable = false) int birthYear;
        @Column(name = "birth_month", nullable = false) int birthMonth;
        @Column(name = "birth_day") Integer birthDay;
        @Enumerated(EnumType.STRING) @Column(name = "gender", nullable = false) Gender gender = Gender.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "political_affiliation", nullable = false) PoliticalAffiliation politicalAffiliation = PoliticalAffiliation.UNKNOWN;
        @Enumerated(EnumType.STRING) @Column(name = "employer_settlement_at_application", nullable = false) ApplicationTimeStatus employerSettlementAtApplication = ApplicationTimeStatus.UNDECLARED;
        @Enumerated(EnumType.STRING) @Column(name = "social_insurance_at_application", nullable = false) ApplicationTimeStatus socialInsuranceAtApplication = ApplicationTimeStatus.UNDECLARED;
        @Enumerated(EnumType.STRING) @Column(name = "highest_education", nullable = false) EducationLevel highestEducation;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "majors", columnDefinition = "jsonb", nullable = false) Set<String> majors = new LinkedHashSet<>();
        @Column(name = "graduation_year") Integer graduationYear;
        @Column(name = "experience_years") Integer experienceYears;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "professional_titles", columnDefinition = "jsonb", nullable = false) Set<String> professionalTitles = new LinkedHashSet<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "preferred_locations", columnDefinition = "jsonb", nullable = false) List<String> preferredLocations = new ArrayList<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "accepted_employment_types", columnDefinition = "jsonb", nullable = false) Set<EmploymentType> acceptedEmploymentTypes = new LinkedHashSet<>();
        @Column(name = "profile_version", nullable = false) String profileVersion;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "skills", columnDefinition = "jsonb", nullable = false) Set<String> skills = new LinkedHashSet<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "research_keywords", columnDefinition = "jsonb", nullable = false) Set<String> researchKeywords = new LinkedHashSet<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "target_job_families", columnDefinition = "jsonb", nullable = false) Set<JobFamily> targetJobFamilies = new LinkedHashSet<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "preferred_organization_types", columnDefinition = "jsonb", nullable = false) Set<OrganizationType> preferredOrganizationTypes = new LinkedHashSet<>();
        @ElementCollection(fetch = FetchType.EAGER)
        @CollectionTable(name = "candidate_education_record", joinColumns = @JoinColumn(name = "candidate_profile_id"))
        @OrderColumn(name = "record_order")
        List<CandidateEducationValue> educationRecords = new ArrayList<>();
        @ElementCollection(fetch = FetchType.EAGER)
        @CollectionTable(name = "candidate_employment_record", joinColumns = @JoinColumn(name = "candidate_profile_id"))
        @OrderColumn(name = "record_order")
        List<CandidateEmploymentValue> employmentRecords = new ArrayList<>();
        protected CandidateProfileEntity() {}
    }

    @Embeddable
    public static class CandidateFactConfirmationId implements Serializable {
        @Column(name = "candidate_profile_id", nullable = false) UUID candidateProfileId;
        @Column(name = "fact_key", nullable = false) String factKey;
        protected CandidateFactConfirmationId() {}
        CandidateFactConfirmationId(UUID candidateProfileId, String factKey) {
            this.candidateProfileId = candidateProfileId;
            this.factKey = factKey;
        }
        @Override public boolean equals(Object other) {
            return other instanceof CandidateFactConfirmationId value
                && Objects.equals(candidateProfileId, value.candidateProfileId)
                && Objects.equals(factKey, value.factKey);
        }
        @Override public int hashCode() { return Objects.hash(candidateProfileId, factKey); }
    }

    @Entity @Table(name = "candidate_fact_confirmation")
    public static class CandidateFactConfirmationEntity {
        @EmbeddedId CandidateFactConfirmationId id;
        @Enumerated(EnumType.STRING) @Column(nullable = false) CandidateFacts.CandidateFactStatus status;
        @Column(name = "value_fingerprint", nullable = false, length = 64) String valueFingerprint;
        @Enumerated(EnumType.STRING) @Column(nullable = false) CandidateFacts.CandidateFactSource source;
        @Column(name = "confirmed_at") Instant confirmedAt;
        @Column(name = "updated_at", nullable = false) Instant updatedAt;
        protected CandidateFactConfirmationEntity() {}
    }

    @Embeddable
    public static class ProfileConfirmationLedgerId implements java.io.Serializable {
        @Column(name = "candidate_profile_id", nullable = false) UUID candidateProfileId;
        @Column(name = "idempotency_key", nullable = false, length = 120) String idempotencyKey;
        protected ProfileConfirmationLedgerId() {}
        ProfileConfirmationLedgerId(UUID candidateProfileId, String idempotencyKey) {
            this.candidateProfileId = candidateProfileId;
            this.idempotencyKey = idempotencyKey;
        }
        @Override public boolean equals(Object other) {
            return other instanceof ProfileConfirmationLedgerId value
                && Objects.equals(candidateProfileId, value.candidateProfileId)
                && Objects.equals(idempotencyKey, value.idempotencyKey);
        }
        @Override public int hashCode() { return Objects.hash(candidateProfileId, idempotencyKey); }
    }

    /** 主键就是候选人加幂等钥匙：同一把钥匙写两次在库里不成立。 */
    @Entity @Table(name = "profile_confirmation_ledger")
    public static class ProfileConfirmationLedgerEntity {
        @EmbeddedId ProfileConfirmationLedgerId id;
        @Column(name = "fact_key", nullable = false, length = 64) String factKey;
        @Column(name = "declared_value", nullable = false, length = 200) String declaredValue;
        @Column(nullable = false, length = 16) String stage;
        @Column(name = "profile_version_before", nullable = false, length = 80) String profileVersionBefore;
        @Column(name = "profile_version_after", nullable = false, length = 80) String profileVersionAfter;
        @Column(name = "recorded_at", nullable = false) Instant recordedAt;
        protected ProfileConfirmationLedgerEntity() {}
    }

    /** last_job_ids 顺序敏感：序号要按位置解析，所以存列表而不是集合。 */
    @Entity @Table(name = "agent_session")
    public static class AgentSessionEntity extends UuidEntity {
        @Column(name = "candidate_profile_id", nullable = false) UUID candidateProfileId;
        @Column(name = "filter_tier", length = 16) String filterTier;
        @Column(name = "filter_location", length = 80) String filterLocation;
        @Column(name = "filter_job_family", length = 48) String filterJobFamily;
        @Column(name = "filter_limit", nullable = false) int filterLimit;
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "last_job_ids", columnDefinition = "jsonb", nullable = false)
        List<String> lastJobIds = new ArrayList<>();
        @JdbcTypeCode(SqlTypes.JSON)
        @Column(name = "pending_confirmations", columnDefinition = "jsonb", nullable = false)
        List<Map<String, String>> pendingConfirmations = new ArrayList<>();
        @Column(name = "profile_version", nullable = false, length = 80) String profileVersion;
        @Column(name = "updated_at", nullable = false) Instant updatedAt;
        protected AgentSessionEntity() {}
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
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "rule_results", columnDefinition = "jsonb", nullable = false) Map<String, Map<String,Object>> ruleResults = new LinkedHashMap<>();
        @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_ids", columnDefinition = "jsonb", nullable = false) List<UUID> evidenceIds = new ArrayList<>();
        @Column(name = "evaluator_version", nullable = false) String evaluatorVersion;
        @Column(name = "assessed_at", nullable = false) Instant assessedAt;
        @Column(name = "profile_version", nullable = false) String profileVersion;
        @Column(name = "job_content_fingerprint", nullable = false) String jobContentFingerprint;
        protected EligibilityAssessmentEntity() {}
    }

    @Entity @Table(name = "opportunity")
    public static class OpportunityEntity extends UuidEntity {
        @Column(name = "candidate_profile_id", nullable = false) UUID candidateProfileId;
        @Column(name = "job_posting_id", nullable = false) UUID jobPostingId;
        @Column(name = "eligibility_assessment_id") UUID eligibilityAssessmentId;
        @Enumerated(EnumType.STRING) @Column(nullable = false) OpportunityStatus status;
        @Column(name = "match_score", nullable = false) int matchScore;
        @Column(name = "decision_note") String decisionNote;
        @Column(name = "created_at", nullable = false) Instant createdAt = Instant.now();
        @Column(name = "updated_at", nullable = false) Instant updatedAt = Instant.now();
        protected OpportunityEntity() {}
    }
}
