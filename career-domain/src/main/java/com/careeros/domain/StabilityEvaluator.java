package com.careeros.domain;

import static com.careeros.domain.DomainEnums.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StabilityEvaluator {
    public static final String VERSION = "stability-v1";
    private static final Map<AssessmentDimensionType,Integer> ORGANIZATION_WEIGHTS = Map.of(
        AssessmentDimensionType.FUNDING_STABILITY, 15,
        AssessmentDimensionType.ORGANIZATION_STABILITY, 15,
        AssessmentDimensionType.POLICY_STABILITY, 10,
        AssessmentDimensionType.BUSINESS_VOLATILITY, 10,
        AssessmentDimensionType.LAYOFF_RISK, 10
    );
    private static final Set<OrganizationType> SEMI_PUBLIC = Set.of(
        OrganizationType.GOVERNMENT, OrganizationType.PUBLIC_INSTITUTION, OrganizationType.STATE_OWNED_ENTERPRISE,
        OrganizationType.UNIVERSITY, OrganizationType.HOSPITAL, OrganizationType.RESEARCH_INSTITUTE
    );

    public Result evaluate(CandidateProfile candidate, JobPosting job, Organization organization, List<OrganizationStabilityFact> facts, String jobFingerprint, Instant now) {
        return evaluate(candidate, job, organization, facts, jobFingerprint, now, VERSION);
    }

    public Result evaluate(CandidateProfile candidate, JobPosting job, Organization organization,
                           List<OrganizationStabilityFact> facts, String jobFingerprint,
                           Instant now, String evaluatorVersion) {
        var dimensions = new ArrayList<AssessmentDimension>();
        dimensions.add(employment(job));
        var byType = new EnumMap<AssessmentDimensionType,OrganizationStabilityFact>(AssessmentDimensionType.class);
        facts.stream().filter(f -> f.organizationId().equals(organization.id())).forEach(f -> byType.put(f.dimensionType(), f));
        ORGANIZATION_WEIGHTS.forEach((type, maximum) -> dimensions.add(fromFact(type, maximum, byType.get(type))));
        dimensions.add(contract(job));
        var assessment = new StabilityAssessment(UUID.randomUUID(), candidate.id(), job.id(), dimensions, evaluatorVersion, candidate.profileVersion(), jobFingerprint, now);
        return new Result(assessment, tier(job, organization));
    }

    public OpportunityTier tier(JobPosting job, Organization organization) {
        boolean evidenced = !job.evidenceIds().isEmpty();
        if (job.employmentType() == EmploymentType.ESTABLISHMENT && evidenced) return OpportunityTier.T1;
        if ((job.employmentType() == EmploymentType.PUBLIC_INSTITUTION_FORMAL
                || job.employmentType() == EmploymentType.CONTRACT
                || job.employmentType() == EmploymentType.PERSONNEL_AGENCY)
            && SEMI_PUBLIC.contains(organization.organizationType()) && evidenced) return OpportunityTier.T2;
        return OpportunityTier.T3;
    }

    private AssessmentDimension employment(JobPosting job) {
        return switch (job.employmentType()) {
            case ESTABLISHMENT -> explicit(AssessmentDimensionType.EMPLOYMENT_SECURITY, 30, 30, "EMPLOYMENT_ESTABLISHMENT", job);
            case PUBLIC_INSTITUTION_FORMAL -> explicit(AssessmentDimensionType.EMPLOYMENT_SECURITY, 24, 30, "EMPLOYMENT_PUBLIC_INSTITUTION_FORMAL", job);
            case PERSONNEL_AGENCY -> explicit(AssessmentDimensionType.EMPLOYMENT_SECURITY, 18, 30, "EMPLOYMENT_AGENCY", job);
            case CONTRACT -> explicit(AssessmentDimensionType.EMPLOYMENT_SECURITY, 15, 30, "EMPLOYMENT_CONTRACT", job);
            case LABOR_DISPATCH -> explicit(AssessmentDimensionType.EMPLOYMENT_SECURITY, 5, 30, "EMPLOYMENT_DISPATCH", job);
            case PROJECT_BASED -> explicit(AssessmentDimensionType.EMPLOYMENT_SECURITY, 2, 30, "EMPLOYMENT_PROJECT", job);
            case UNKNOWN -> unknown(AssessmentDimensionType.EMPLOYMENT_SECURITY, 30);
        };
    }

    private AssessmentDimension contract(JobPosting job) {
        return switch (job.employmentType()) {
            case ESTABLISHMENT -> explicit(AssessmentDimensionType.CONTRACT_RISK, 10, 10, "CONTRACT_LOW_RISK", job);
            case PUBLIC_INSTITUTION_FORMAL -> explicit(AssessmentDimensionType.CONTRACT_RISK, 8, 10, "CONTRACT_PUBLIC_INSTITUTION_FORMAL", job);
            case PERSONNEL_AGENCY -> explicit(AssessmentDimensionType.CONTRACT_RISK, 6, 10, "CONTRACT_AGENCY", job);
            case CONTRACT -> explicit(AssessmentDimensionType.CONTRACT_RISK, 5, 10, "CONTRACT_STANDARD", job);
            case LABOR_DISPATCH -> explicit(AssessmentDimensionType.CONTRACT_RISK, 2, 10, "CONTRACT_DISPATCH", job);
            case PROJECT_BASED -> explicit(AssessmentDimensionType.CONTRACT_RISK, 0, 10, "CONTRACT_PROJECT", job);
            case UNKNOWN -> unknown(AssessmentDimensionType.CONTRACT_RISK, 10);
        };
    }

    private AssessmentDimension fromFact(AssessmentDimensionType type, int maximum, OrganizationStabilityFact fact) {
        if (fact == null) return unknown(type, maximum);
        int scaled = Math.round(maximum * fact.achievedPoints() / (float) fact.maximumPoints());
        return new AssessmentDimension(type, scaled, maximum, AssessmentFactStatus.EXPLICIT, fact.reasonCode(), fact.explanation(), fact.evidenceIds());
    }

    private static AssessmentDimension explicit(AssessmentDimensionType type, int points, int maximum, String reason, JobPosting job) {
        return new AssessmentDimension(type, points, maximum, AssessmentFactStatus.EXPLICIT, reason, "根据公告明确用工身份评估", job.evidenceIds());
    }
    private static AssessmentDimension unknown(AssessmentDimensionType type, int maximum) {
        return new AssessmentDimension(type, 0, maximum, AssessmentFactStatus.UNKNOWN, type.name() + "_UNKNOWN", "缺少可核验稳定性证据", List.of());
    }

    public record Result(StabilityAssessment assessment, OpportunityTier tier) {}
}
