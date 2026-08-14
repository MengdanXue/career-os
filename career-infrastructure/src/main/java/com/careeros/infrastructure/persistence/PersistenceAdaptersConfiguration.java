package com.careeros.infrastructure.persistence;

import com.careeros.application.RepositoryPorts;
import com.careeros.domain.*;
import com.careeros.domain.DomainEnums.*;
import com.careeros.domain.EligibilityAssessment.RuleResult;
import java.util.*;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;

@Configuration
public class PersistenceAdaptersConfiguration {
    @Bean RepositoryPorts.RecruitmentEvents recruitmentEvents(RecruitmentEventJpaRepository r) { return new RecruitmentEventAdapter(r,this::toEventEntity,this::toEvent); }
    @Bean RepositoryPorts.Organizations organizations(OrganizationJpaRepository r) { return new OrganizationAdapter(r,this::toOrganizationEntity,this::toOrganization); }
    @Bean RepositoryPorts.JobPostings jobPostings(JobPostingJpaRepository r) { return new JobAdapter(r,this::toJobEntity,this::toJob); }
    @Bean RepositoryPorts.CandidateProfiles candidateProfiles(CandidateProfileJpaRepository r) { return new CandidateAdapter(r,this::toCandidateEntity,this::toCandidate); }
    @Bean RepositoryPorts.PolicyRules policyRules(PolicyRuleJpaRepository r) { return new PolicyAdapter(r,this::toPolicyEntity,this::toPolicy); }
    @Bean RepositoryPorts.EvidenceRecords evidenceRecords(EvidenceJpaRepository r) { return new EvidenceAdapter(r,this::toEvidenceEntity,this::toEvidence); }
    @Bean RepositoryPorts.EligibilityAssessments eligibilityAssessments(EligibilityAssessmentJpaRepository r) { return new AssessmentAdapter(r,this::toAssessmentEntity,this::toAssessment); }
    @Bean RepositoryPorts.Opportunities opportunities(OpportunityJpaRepository r) { return new OpportunityAdapter(r,this::toOpportunityEntity,this::toOpportunity); }

    private static class GenericAdapter<D,E extends JpaModels.UuidEntity> implements RepositoryPorts.Repository<D> {
        private final JpaRepository<E,UUID> repository; private final Function<D,E> toEntity; private final Function<E,D> toDomain;
        GenericAdapter(JpaRepository<E,UUID> repository,Function<D,E> toEntity,Function<E,D> toDomain){this.repository=repository;this.toEntity=toEntity;this.toDomain=toDomain;}
        public D save(D domain){return toDomain.apply(repository.save(toEntity.apply(domain)));}
        public Optional<D> findById(UUID id){return repository.findById(id).map(toDomain);}
        public List<D> findAll(){return repository.findAll().stream().map(toDomain).toList();}
        public void deleteById(UUID id){repository.deleteById(id);}
    }
    private static final class RecruitmentEventAdapter extends GenericAdapter<RecruitmentEvent,JpaModels.RecruitmentEventEntity> implements RepositoryPorts.RecruitmentEvents { RecruitmentEventAdapter(RecruitmentEventJpaRepository r,Function<RecruitmentEvent,JpaModels.RecruitmentEventEntity>a,Function<JpaModels.RecruitmentEventEntity,RecruitmentEvent>b){super(r,a,b);} }
    private static final class OrganizationAdapter extends GenericAdapter<Organization,JpaModels.OrganizationEntity> implements RepositoryPorts.Organizations { OrganizationAdapter(OrganizationJpaRepository r,Function<Organization,JpaModels.OrganizationEntity>a,Function<JpaModels.OrganizationEntity,Organization>b){super(r,a,b);} }
    private static final class JobAdapter extends GenericAdapter<JobPosting,JpaModels.JobPostingEntity> implements RepositoryPorts.JobPostings {
        private final JobPostingJpaRepository repository; private final Function<JobPosting,JpaModels.JobPostingEntity> toEntity; private final Function<JpaModels.JobPostingEntity,JobPosting> toDomain;
        JobAdapter(JobPostingJpaRepository r,Function<JobPosting,JpaModels.JobPostingEntity>a,Function<JpaModels.JobPostingEntity,JobPosting>b){super(r,a,b);repository=r;toEntity=a;toDomain=b;}
        @Override public JobPosting save(JobPosting value){var entity=toEntity.apply(value);repository.findById(value.id()).ifPresent(existing->{entity.stableJobKey=existing.stableJobKey;entity.contentFingerprint=existing.contentFingerprint;entity.active=existing.active;entity.firstSeenAt=existing.firstSeenAt;entity.lastSeenAt=existing.lastSeenAt;});return toDomain.apply(repository.save(entity));}
    }
    private static final class CandidateAdapter extends GenericAdapter<CandidateProfile,JpaModels.CandidateProfileEntity> implements RepositoryPorts.CandidateProfiles { CandidateAdapter(CandidateProfileJpaRepository r,Function<CandidateProfile,JpaModels.CandidateProfileEntity>a,Function<JpaModels.CandidateProfileEntity,CandidateProfile>b){super(r,a,b);} }
    private static final class PolicyAdapter extends GenericAdapter<PolicyRule,JpaModels.PolicyRuleEntity> implements RepositoryPorts.PolicyRules { PolicyAdapter(PolicyRuleJpaRepository r,Function<PolicyRule,JpaModels.PolicyRuleEntity>a,Function<JpaModels.PolicyRuleEntity,PolicyRule>b){super(r,a,b);} }
    private static final class EvidenceAdapter extends GenericAdapter<Evidence,JpaModels.EvidenceEntity> implements RepositoryPorts.EvidenceRecords { EvidenceAdapter(EvidenceJpaRepository r,Function<Evidence,JpaModels.EvidenceEntity>a,Function<JpaModels.EvidenceEntity,Evidence>b){super(r,a,b);} }
    private static final class AssessmentAdapter extends GenericAdapter<EligibilityAssessment,JpaModels.EligibilityAssessmentEntity> implements RepositoryPorts.EligibilityAssessments { AssessmentAdapter(EligibilityAssessmentJpaRepository r,Function<EligibilityAssessment,JpaModels.EligibilityAssessmentEntity>a,Function<JpaModels.EligibilityAssessmentEntity,EligibilityAssessment>b){super(r,a,b);} }
    private static final class OpportunityAdapter extends GenericAdapter<Opportunity,JpaModels.OpportunityEntity> implements RepositoryPorts.Opportunities { OpportunityAdapter(OpportunityJpaRepository r,Function<Opportunity,JpaModels.OpportunityEntity>a,Function<JpaModels.OpportunityEntity,Opportunity>b){super(r,a,b);} }

    private JpaModels.RecruitmentEventEntity toEventEntity(RecruitmentEvent value) { var e=new JpaModels.RecruitmentEventEntity(); e.id=value.id(); e.title=value.title(); e.recruitmentYear=value.recruitmentYear(); e.eventType=value.eventType(); e.publishedOn=value.publishedOn(); e.applicationStartsOn=value.applicationStartsOn(); e.applicationEndsOn=value.applicationEndsOn(); e.sourceUrl=value.sourceUrl(); e.defaultEmploymentType=value.defaultEmploymentType(); e.evidenceIds=new ArrayList<>(value.evidenceIds()); return e; }
    private RecruitmentEvent toEvent(JpaModels.RecruitmentEventEntity e) { return new RecruitmentEvent(e.id,e.title,e.recruitmentYear,e.eventType,e.publishedOn,e.applicationStartsOn,e.applicationEndsOn,e.sourceUrl,e.defaultEmploymentType,e.evidenceIds); }

    private JpaModels.OrganizationEntity toOrganizationEntity(Organization value) { var e=new JpaModels.OrganizationEntity(); e.id=value.id(); e.name=value.name(); e.organizationType=value.organizationType(); e.administrativeLevel=value.administrativeLevel(); e.province=value.province(); e.city=value.city(); e.district=value.district(); e.parentOrganizationId=value.parentOrganizationId(); e.officialWebsite=value.officialWebsite(); return e; }
    private Organization toOrganization(JpaModels.OrganizationEntity e) { return new Organization(e.id,e.name,e.organizationType,e.administrativeLevel,e.province,e.city,e.district,e.parentOrganizationId,e.officialWebsite); }

    private JpaModels.JobPostingEntity toJobEntity(JobPosting value) { var e=new JpaModels.JobPostingEntity(); e.id=value.id(); e.recruitmentEventId=value.recruitmentEventId(); e.organizationId=value.organizationId(); e.externalJobCode=value.externalJobCode(); e.title=value.title(); e.jobFamily=value.jobFamily(); e.employmentType=value.employmentType(); e.location=value.location(); e.headcount=value.headcount(); e.minimumEducation=value.minimumEducation(); e.exactMajors=new LinkedHashSet<>(value.exactMajors()); e.acceptedGraduationYears=new LinkedHashSet<>(value.acceptedGraduationYears()); e.maximumAge=value.maximumAge(); e.ageReferenceDate=value.ageReferenceDate(); e.minimumExperienceYears=value.minimumExperienceYears(); e.requiredProfessionalTitles=new LinkedHashSet<>(value.requiredProfessionalTitles()); e.duties=value.duties(); e.sourceUrl=value.sourceUrl(); e.evidenceIds=new ArrayList<>(value.evidenceIds()); e.active=true; return e; }
    private JobPosting toJob(JpaModels.JobPostingEntity e) { return new JobPosting(e.id,e.recruitmentEventId,e.organizationId,e.externalJobCode,e.title,e.jobFamily,e.employmentType,e.location,e.headcount,e.minimumEducation,e.exactMajors,e.acceptedGraduationYears,e.maximumAge,e.ageReferenceDate,e.minimumExperienceYears,e.requiredProfessionalTitles,e.duties,e.sourceUrl,e.evidenceIds); }

    private JpaModels.CandidateProfileEntity toCandidateEntity(CandidateProfile value) { var e=new JpaModels.CandidateProfileEntity(); e.id=value.id(); e.displayName=value.displayName(); e.birthYear=value.birthDate().year(); e.birthMonth=value.birthDate().month(); e.birthDay=value.birthDate().day(); e.highestEducation=value.highestEducation(); e.majors=new LinkedHashSet<>(value.majors()); e.graduationYear=value.graduationYear(); e.experienceYears=value.experienceYears(); e.professionalTitles=new LinkedHashSet<>(value.professionalTitles()); e.preferredLocations=new ArrayList<>(value.preferredLocations()); e.acceptedEmploymentTypes=new LinkedHashSet<>(value.acceptedEmploymentTypes()); e.profileVersion=value.profileVersion(); return e; }
    private CandidateProfile toCandidate(JpaModels.CandidateProfileEntity e) { return new CandidateProfile(e.id,e.displayName,new PartialDate(e.birthYear,e.birthMonth,e.birthDay),e.highestEducation,e.majors,e.graduationYear,e.experienceYears,e.professionalTitles,e.preferredLocations,e.acceptedEmploymentTypes,e.profileVersion); }

    private JpaModels.PolicyRuleEntity toPolicyEntity(PolicyRule value) { var e=new JpaModels.PolicyRuleEntity(); e.id=value.id(); e.ruleType=value.ruleType(); e.name=value.name(); e.jurisdiction=value.jurisdiction(); e.validFrom=value.validFrom(); e.validUntil=value.validUntil(); e.parameters=new LinkedHashMap<>(value.parameters()); e.evidenceId=value.evidenceId(); return e; }
    private PolicyRule toPolicy(JpaModels.PolicyRuleEntity e) { return new PolicyRule(e.id,e.ruleType,e.name,e.jurisdiction,e.validFrom,e.validUntil,e.parameters,e.evidenceId); }

    private JpaModels.EvidenceEntity toEvidenceEntity(Evidence value) { var e=new JpaModels.EvidenceEntity(); e.id=value.id(); e.evidenceType=value.type(); e.sourceUrl=value.sourceUrl(); e.sourceTitle=value.sourceTitle(); e.excerpt=value.excerpt(); e.contentHash=value.contentHash(); e.capturedAt=value.capturedAt(); return e; }
    private Evidence toEvidence(JpaModels.EvidenceEntity e) { return new Evidence(e.id,null,e.evidenceType,e.sourceUrl,e.sourceTitle,e.excerpt,e.contentHash,e.capturedAt); }

    private JpaModels.EligibilityAssessmentEntity toAssessmentEntity(EligibilityAssessment value) { var e=new JpaModels.EligibilityAssessmentEntity(); e.id=value.id(); e.candidateProfileId=value.candidateProfileId(); e.jobPostingId=value.jobPostingId(); e.status=value.status(); value.ruleResults().forEach((key,result)->e.ruleResults.put(key.name(),Map.of("status",result.status().name(),"explanation",result.explanation()))); e.evidenceIds=new ArrayList<>(value.evidenceIds()); e.evaluatorVersion=value.evaluatorVersion(); e.assessedAt=value.assessedAt(); return e; }
    private EligibilityAssessment toAssessment(JpaModels.EligibilityAssessmentEntity e) { var results=new EnumMap<RuleType,RuleResult>(RuleType.class); e.ruleResults.forEach((key,value)->results.put(RuleType.valueOf(key),new RuleResult(EligibilityStatus.valueOf(value.get("status")),value.get("explanation")))); return new EligibilityAssessment(e.id,e.candidateProfileId,e.jobPostingId,e.status,results,e.evidenceIds,e.evaluatorVersion,e.assessedAt); }

    private JpaModels.OpportunityEntity toOpportunityEntity(Opportunity value) { var e=new JpaModels.OpportunityEntity(); e.id=value.id(); e.candidateProfileId=value.candidateProfileId(); e.jobPostingId=value.jobPostingId(); e.eligibilityAssessmentId=value.eligibilityAssessmentId(); e.status=value.status(); e.matchScore=value.matchScore(); e.decisionNote=value.decisionNote(); e.createdAt=value.createdAt(); e.updatedAt=value.updatedAt(); return e; }
    private Opportunity toOpportunity(JpaModels.OpportunityEntity e) { return new Opportunity(e.id,e.candidateProfileId,e.jobPostingId,e.eligibilityAssessmentId,e.status,e.matchScore,e.decisionNote,e.createdAt,e.updatedAt); }
}
