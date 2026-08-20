package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.*;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class DecisionJpaModels {
    private DecisionJpaModels() {}

    @MappedSuperclass
    public abstract static class UuidEntity {
        @Id @Column(nullable = false, updatable = false) UUID id;
    }

    @Entity @Table(name = "fit_assessment")
    public static class FitAssessmentEntity extends UuidEntity {
        @Column(name="candidate_profile_id",nullable=false) UUID candidateProfileId;
        @Column(name="job_posting_id",nullable=false) UUID jobPostingId;
        @Column(nullable=false) int score;
        @Column(name="coverage_percent",nullable=false) int coveragePercent;
        @Column(name="evaluator_version",nullable=false) String evaluatorVersion;
        @Column(name="profile_version",nullable=false) String profileVersion;
        @Column(name="job_content_fingerprint",nullable=false) String jobContentFingerprint;
        @Column(name="assessed_at",nullable=false) Instant assessedAt;
        protected FitAssessmentEntity() {}
    }

    @Entity @Table(name = "stability_assessment")
    public static class StabilityAssessmentEntity extends UuidEntity {
        @Column(name="candidate_profile_id",nullable=false) UUID candidateProfileId;
        @Column(name="job_posting_id",nullable=false) UUID jobPostingId;
        @Column(nullable=false) int score;
        @Column(name="coverage_percent",nullable=false) int coveragePercent;
        @Column(name="evaluator_version",nullable=false) String evaluatorVersion;
        @Column(name="profile_version",nullable=false) String profileVersion;
        @Column(name="job_content_fingerprint",nullable=false) String jobContentFingerprint;
        @Column(name="assessed_at",nullable=false) Instant assessedAt;
        protected StabilityAssessmentEntity() {}
    }

    @Entity @Table(name = "decision_assessment")
    public static class DecisionAssessmentEntity extends UuidEntity {
        @Column(name="candidate_profile_id",nullable=false) UUID candidateProfileId;
        @Column(name="job_posting_id",nullable=false) UUID jobPostingId;
        @Column(name="eligibility_assessment_id",nullable=false) UUID eligibilityAssessmentId;
        @Column(name="fit_assessment_id",nullable=false) UUID fitAssessmentId;
        @Column(name="stability_assessment_id",nullable=false) UUID stabilityAssessmentId;
        @Enumerated(EnumType.STRING) @Column(name="eligibility_status",nullable=false) EligibilityStatus eligibilityStatus;
        @Enumerated(EnumType.STRING) @Column(name="opportunity_tier",nullable=false) OpportunityTier opportunityTier;
        @Enumerated(EnumType.STRING) @Column(name="recommendation_status",nullable=false) RecommendationStatus recommendationStatus;
        @Column(name="fit_score",nullable=false) int fitScore;
        @Column(name="stability_score",nullable=false) int stabilityScore;
        @Column(name="coverage_percent",nullable=false) int coveragePercent;
        @Column(name="evaluator_version",nullable=false) String evaluatorVersion;
        @Column(name="profile_version",nullable=false) String profileVersion;
        @Column(name="job_content_fingerprint",nullable=false) String jobContentFingerprint;
        @Column(name="assessed_at",nullable=false) Instant assessedAt;
        protected DecisionAssessmentEntity() {}
    }

    @Entity @Table(name = "assessment_dimension")
    public static class AssessmentDimensionEntity extends UuidEntity {
        @Column(name="decision_assessment_id",nullable=false) UUID decisionAssessmentId;
        @Column(name="assessment_kind",nullable=false) String assessmentKind;
        @Enumerated(EnumType.STRING) @Column(name="dimension_type",nullable=false) AssessmentDimensionType dimensionType;
        @Column(name="achieved_points",nullable=false) int achievedPoints;
        @Column(name="maximum_points",nullable=false) int maximumPoints;
        @Enumerated(EnumType.STRING) @Column(name="fact_status",nullable=false) AssessmentFactStatus factStatus;
        @Column(name="reason_code",nullable=false) String reasonCode;
        @Column(nullable=false) String explanation;
        @ElementCollection(fetch=FetchType.EAGER)
        @CollectionTable(name="assessment_dimension_evidence",joinColumns=@JoinColumn(name="assessment_dimension_id"))
        @Column(name="evidence_id",nullable=false)
        Set<UUID> evidenceIds = new LinkedHashSet<>();
        protected AssessmentDimensionEntity() {}
    }

    @Entity @Table(name = "organization_stability_fact")
    public static class OrganizationStabilityFactEntity extends UuidEntity {
        @Column(name="organization_id",nullable=false) UUID organizationId;
        @Enumerated(EnumType.STRING) @Column(name="dimension_type",nullable=false) AssessmentDimensionType dimensionType;
        @Column(name="achieved_points",nullable=false) int achievedPoints;
        @Column(name="maximum_points",nullable=false) int maximumPoints;
        @Column(name="reason_code",nullable=false) String reasonCode;
        @Column(nullable=false) String explanation;
        @Column(name="observed_at",nullable=false) Instant observedAt;
        @ElementCollection(fetch=FetchType.EAGER)
        @CollectionTable(name="organization_stability_fact_evidence",joinColumns=@JoinColumn(name="organization_stability_fact_id"))
        @Column(name="evidence_id",nullable=false)
        Set<UUID> evidenceIds = new LinkedHashSet<>();
        protected OrganizationStabilityFactEntity() {}
    }
}
