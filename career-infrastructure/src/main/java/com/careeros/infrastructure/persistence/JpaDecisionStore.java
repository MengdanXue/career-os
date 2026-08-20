package com.careeros.infrastructure.persistence;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.domain.*;
import com.careeros.domain.EligibilityAssessment.RuleResult;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class JpaDecisionStore implements JobContexts, OrganizationStabilityFacts, DecisionSnapshots {
    private final JobPostingJpaRepository jobs;
    private final OrganizationJpaRepository organizations;
    private final RecruitmentEventJpaRepository events;
    private final EligibilityAssessmentJpaRepository eligibility;
    private final FitAssessmentJpaRepository fits;
    private final StabilityAssessmentJpaRepository stabilities;
    private final DecisionAssessmentJpaRepository decisions;
    private final AssessmentDimensionJpaRepository dimensions;
    private final OrganizationStabilityFactJpaRepository facts;

    public JpaDecisionStore(
        JobPostingJpaRepository jobs, OrganizationJpaRepository organizations, RecruitmentEventJpaRepository events,
        EligibilityAssessmentJpaRepository eligibility, FitAssessmentJpaRepository fits,
        StabilityAssessmentJpaRepository stabilities, DecisionAssessmentJpaRepository decisions,
        AssessmentDimensionJpaRepository dimensions, OrganizationStabilityFactJpaRepository facts
    ) {
        this.jobs=jobs; this.organizations=organizations; this.events=events; this.eligibility=eligibility;
        this.fits=fits; this.stabilities=stabilities; this.decisions=decisions; this.dimensions=dimensions; this.facts=facts;
    }

    @Override public Optional<JobContext> findByJobId(UUID id) { return jobs.findById(id).map(this::context); }
    @Override public List<JobContext> findActive() { return jobs.findByActiveTrue().stream().map(this::context).toList(); }

    @Override public List<OrganizationStabilityFact> findByOrganizationId(UUID organizationId) {
        return facts.findByOrganizationId(organizationId).stream().map(this::fact).toList();
    }

    @Override public Optional<DecisionBundle> findByInput(DecisionInputKey input) {
        return decisions.findByCandidateProfileIdAndJobPostingIdAndProfileVersionAndJobContentFingerprintAndEvaluatorVersion(
            input.candidateProfileId(), input.jobPostingId(), input.profileVersion(), input.jobContentFingerprint(), input.evaluatorVersion()
        ).map(this::bundle);
    }

    @Override @Transactional
    public DecisionBundle save(DecisionInputKey input, DecisionBundle bundle) {
        fits.save(fitEntity(bundle.fit()));
        stabilities.save(stabilityEntity(bundle.stability()));
        decisions.save(decisionEntity(bundle.decision()));
        saveDimensions(bundle.decision().id(), "FIT", bundle.fit().dimensions());
        saveDimensions(bundle.decision().id(), "STABILITY", bundle.stability().dimensions());
        return bundle;
    }

    @Override public List<DecisionBundle> findCurrentByCandidate(UUID candidateId) {
        var latestByJob = new LinkedHashMap<UUID,DecisionJpaModels.DecisionAssessmentEntity>();
        decisions.findByCandidateProfileIdOrderByAssessedAtDesc(candidateId).forEach(value -> latestByJob.putIfAbsent(value.jobPostingId, value));
        return latestByJob.values().stream().map(this::bundle).toList();
    }

    private void saveDimensions(UUID decisionId, String kind, List<AssessmentDimension> values) {
        for (AssessmentDimension value : values) {
            var entity = new DecisionJpaModels.AssessmentDimensionEntity();
            entity.id=UUID.randomUUID(); entity.decisionAssessmentId=decisionId; entity.assessmentKind=kind; entity.dimensionType=value.type();
            entity.achievedPoints=value.achievedPoints(); entity.maximumPoints=value.maximumPoints(); entity.factStatus=value.factStatus();
            entity.reasonCode=value.reasonCode(); entity.explanation=value.explanation(); entity.evidenceIds=new LinkedHashSet<>(value.evidenceIds());
            dimensions.save(entity);
        }
    }

    private DecisionBundle bundle(DecisionJpaModels.DecisionAssessmentEntity value) {
        var eligibilityValue = eligibility.findById(value.eligibilityAssessmentId).orElseThrow();
        var fitValue = fits.findById(value.fitAssessmentId).orElseThrow();
        var stabilityValue = stabilities.findById(value.stabilityAssessmentId).orElseThrow();
        List<DecisionJpaModels.AssessmentDimensionEntity> allDimensions = dimensions.findByDecisionAssessmentId(value.id);
        var fitDimensions = allDimensions.stream().filter(d -> d.assessmentKind.equals("FIT")).map(this::dimension).toList();
        var stabilityDimensions = allDimensions.stream().filter(d -> d.assessmentKind.equals("STABILITY")).map(this::dimension).toList();
        var fit = new FitAssessment(fitValue.id,fitValue.candidateProfileId,fitValue.jobPostingId,fitDimensions,fitValue.evaluatorVersion,fitValue.profileVersion,fitValue.jobContentFingerprint,fitValue.assessedAt);
        var stability = new StabilityAssessment(stabilityValue.id,stabilityValue.candidateProfileId,stabilityValue.jobPostingId,stabilityDimensions,stabilityValue.evaluatorVersion,stabilityValue.profileVersion,stabilityValue.jobContentFingerprint,stabilityValue.assessedAt);
        var decision = new DecisionAssessment(value.id,value.candidateProfileId,value.jobPostingId,value.eligibilityAssessmentId,value.fitAssessmentId,value.stabilityAssessmentId,value.eligibilityStatus,value.opportunityTier,value.recommendationStatus,value.fitScore,value.stabilityScore,value.coveragePercent,value.evaluatorVersion,value.profileVersion,value.jobContentFingerprint,value.assessedAt);
        return new DecisionBundle(eligibility(eligibilityValue),fit,stability,decision,context(jobs.findById(value.jobPostingId).orElseThrow()));
    }

    private JobContext context(JpaModels.JobPostingEntity job) {
        var organization = organizations.findById(job.organizationId).orElseThrow();
        var event = events.findById(job.recruitmentEventId).orElseThrow();
        String fingerprint = job.contentFingerprint == null || job.contentFingerprint.isBlank() ? "legacy-" + job.id : job.contentFingerprint;
        return new JobContext(
            new JobPosting(job.id,job.recruitmentEventId,job.organizationId,job.externalJobCode,job.title,job.jobFamily,job.employmentType,job.location,job.headcount,job.minimumEducation,job.exactMajors,job.acceptedGraduationYears,job.maximumAge,job.ageReferenceDate,job.minimumExperienceYears,job.requiredProfessionalTitles,job.duties,job.sourceUrl,job.evidenceIds),
            new Organization(organization.id,organization.name,organization.organizationType,organization.administrativeLevel,organization.province,organization.city,organization.district,organization.parentOrganizationId,organization.officialWebsite),
            new RecruitmentEvent(event.id,event.title,event.recruitmentYear,event.eventType,event.publishedOn,event.applicationStartsOn,event.applicationEndsOn,event.sourceUrl,event.defaultEmploymentType,event.evidenceIds),
            fingerprint, job.active
        );
    }

    private EligibilityAssessment eligibility(JpaModels.EligibilityAssessmentEntity value) {
        var results=new EnumMap<RuleType,RuleResult>(RuleType.class);
        value.ruleResults.forEach((key,result)->results.put(RuleType.valueOf(key),new RuleResult(EligibilityStatus.valueOf(result.get("status")),result.get("explanation"))));
        return new EligibilityAssessment(value.id,value.candidateProfileId,value.jobPostingId,value.status,results,value.evidenceIds,value.evaluatorVersion,value.assessedAt,value.profileVersion,value.jobContentFingerprint);
    }

    private AssessmentDimension dimension(DecisionJpaModels.AssessmentDimensionEntity value) { return new AssessmentDimension(value.dimensionType,value.achievedPoints,value.maximumPoints,value.factStatus,value.reasonCode,value.explanation,List.copyOf(value.evidenceIds)); }
    private OrganizationStabilityFact fact(DecisionJpaModels.OrganizationStabilityFactEntity value) { return new OrganizationStabilityFact(value.id,value.organizationId,value.dimensionType,value.achievedPoints,value.maximumPoints,value.reasonCode,value.explanation,List.copyOf(value.evidenceIds),value.observedAt); }

    private DecisionJpaModels.FitAssessmentEntity fitEntity(FitAssessment value) { var e=new DecisionJpaModels.FitAssessmentEntity(); e.id=value.id(); e.candidateProfileId=value.candidateProfileId(); e.jobPostingId=value.jobPostingId(); e.score=value.score(); e.coveragePercent=value.coveragePercent(); e.evaluatorVersion=value.evaluatorVersion(); e.profileVersion=value.profileVersion(); e.jobContentFingerprint=value.jobContentFingerprint(); e.assessedAt=value.assessedAt(); return e; }
    private DecisionJpaModels.StabilityAssessmentEntity stabilityEntity(StabilityAssessment value) { var e=new DecisionJpaModels.StabilityAssessmentEntity(); e.id=value.id(); e.candidateProfileId=value.candidateProfileId(); e.jobPostingId=value.jobPostingId(); e.score=value.score(); e.coveragePercent=value.coveragePercent(); e.evaluatorVersion=value.evaluatorVersion(); e.profileVersion=value.profileVersion(); e.jobContentFingerprint=value.jobContentFingerprint(); e.assessedAt=value.assessedAt(); return e; }
    private DecisionJpaModels.DecisionAssessmentEntity decisionEntity(DecisionAssessment value) { var e=new DecisionJpaModels.DecisionAssessmentEntity(); e.id=value.id(); e.candidateProfileId=value.candidateProfileId(); e.jobPostingId=value.jobPostingId(); e.eligibilityAssessmentId=value.eligibilityAssessmentId(); e.fitAssessmentId=value.fitAssessmentId(); e.stabilityAssessmentId=value.stabilityAssessmentId(); e.eligibilityStatus=value.eligibilityStatus(); e.opportunityTier=value.tier(); e.recommendationStatus=value.recommendationStatus(); e.fitScore=value.fitScore(); e.stabilityScore=value.stabilityScore(); e.coveragePercent=value.coveragePercent(); e.evaluatorVersion=value.evaluatorVersion(); e.profileVersion=value.profileVersion(); e.jobContentFingerprint=value.jobContentFingerprint(); e.assessedAt=value.assessedAt(); return e; }
}
