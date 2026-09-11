package com.careeros.infrastructure.persistence;

import com.careeros.application.RepositoryPorts;
import com.careeros.application.JobContentFingerprint;
import com.careeros.application.ExtractionPorts.ExtractionPersistence;
import com.careeros.application.ExtractionPorts.ReviewPersistence;
import com.careeros.application.ExtractionPorts.ExtractionBundle;
import com.careeros.application.ExtractionPorts.FailedExtractionBundle;
import com.careeros.application.ExtractionPorts.PersistedExtraction;
import com.careeros.application.ExtractionPorts.ReviewDetails;
import com.careeros.application.ExtractionPorts.ReviewPage;
import com.careeros.application.ExtractionPorts.ReviewResolution;
import com.careeros.domain.*;
import com.careeros.domain.DomainEnums.*;
import com.careeros.domain.EligibilityAssessment.RuleResult;
import com.careeros.domain.CandidateFacts.CandidateFactConfirmation;
import com.careeros.infrastructure.extraction.JpaExtractionPersistence;
import java.util.*;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;

@Configuration
public class PersistenceAdaptersConfiguration {
    @Bean ExtractionPersistence extractionPersistence(JpaExtractionPersistence persistence) {
        return new ExtractionPersistence() {
            public Optional<PersistedExtraction> findByInputFingerprint(String fingerprint) { return persistence.findByInputFingerprint(fingerprint); }
            public PersistedExtraction save(ExtractionBundle bundle) { return persistence.save(bundle); }
            public PersistedExtraction saveFailure(FailedExtractionBundle bundle) { return persistence.saveFailure(bundle); }
            public PersistedExtraction findById(UUID id) { return persistence.findExtractionById(id); }
        };
    }
    @Bean ReviewPersistence reviewPersistence(JpaExtractionPersistence persistence) {
        return new ReviewPersistence() {
            public ReviewDetails findById(UUID id) { return persistence.findReviewById(id); }
            public ReviewPage findPage(ReviewStatus status,int page,int size) { return persistence.findPage(status,page,size); }
            public ReviewDetails apply(ReviewResolution resolution) { return persistence.apply(resolution); }
        };
    }
    @Bean RepositoryPorts.RecruitmentEvents recruitmentEvents(RecruitmentEventJpaRepository r) { return new RecruitmentEventAdapter(r,this::toEventEntity,this::toEvent); }
    @Bean RepositoryPorts.Organizations organizations(OrganizationJpaRepository r) { return new OrganizationAdapter(r,this::toOrganizationEntity,this::toOrganization); }
    @Bean RepositoryPorts.JobPostings jobPostings(JobPostingJpaRepository r) { return new JobAdapter(r,this::toJobEntity,this::toJob); }
    @Bean RepositoryPorts.CandidateProfiles candidateProfiles(CandidateProfileJpaRepository r) { return new CandidateAdapter(r,this::toCandidateEntity,this::toCandidate); }
    @Bean RepositoryPorts.CandidateFactConfirmations candidateFactConfirmations(CandidateFactConfirmationJpaRepository repository) {
        return new RepositoryPorts.CandidateFactConfirmations() {
            public List<CandidateFactConfirmation> findByCandidateId(UUID candidateId) {
                return repository.findAllByIdCandidateProfileId(candidateId).stream()
                    .map(PersistenceAdaptersConfiguration.this::toCandidateFact).toList();
            }
            public List<CandidateFactConfirmation> saveAll(List<CandidateFactConfirmation> values) {
                return repository.saveAll(values.stream().map(PersistenceAdaptersConfiguration.this::toCandidateFactEntity).toList())
                    .stream().map(PersistenceAdaptersConfiguration.this::toCandidateFact).toList();
            }
        };
    }
    @Bean com.careeros.application.personal.ProfileConfirmationPorts.ConfirmationLedger confirmationLedger(
        ProfileConfirmationLedgerJpaRepository repository
    ) {
        return new com.careeros.application.personal.ProfileConfirmationPorts.ConfirmationLedger() {
            public Optional<com.careeros.application.personal.ProfileConfirmationPorts.LedgerEntry> find(
                UUID candidateId, String idempotencyKey
            ) {
                return repository.findById(new JpaModels.ProfileConfirmationLedgerId(candidateId, idempotencyKey))
                    .map(PersistenceAdaptersConfiguration.this::toLedgerEntry);
            }
            public com.careeros.application.personal.ProfileConfirmationPorts.LedgerEntry save(
                com.careeros.application.personal.ProfileConfirmationPorts.LedgerEntry entry
            ) {
                return toLedgerEntry(repository.save(toLedgerEntity(entry)));
            }
        };
    }
    @Bean com.careeros.application.AgentSessionPorts.Sessions agentSessions(AgentSessionJpaRepository repository) {
        return new com.careeros.application.AgentSessionPorts.Sessions() {
            public Optional<com.careeros.application.AgentSession> find(UUID sessionId) {
                return repository.findById(sessionId).map(PersistenceAdaptersConfiguration.this::toAgentSession);
            }
            public com.careeros.application.AgentSession save(com.careeros.application.AgentSession session) {
                return toAgentSession(repository.save(toAgentSessionEntity(session)));
            }
        };
    }
    @Bean RepositoryPorts.PolicyRules policyRules(PolicyRuleJpaRepository r) { return new PolicyAdapter(r,this::toPolicyEntity,this::toPolicy); }
    private com.careeros.application.AgentSession toAgentSession(JpaModels.AgentSessionEntity entity) {
        var filters = new com.careeros.application.AgentSession.SessionFilters(
            entity.filterTier == null ? null : OpportunityTier.valueOf(entity.filterTier),
            entity.filterLocation,
            entity.filterJobFamily == null ? null : JobFamily.valueOf(entity.filterJobFamily),
            entity.filterLimit);
        var pending = entity.pendingConfirmations.stream()
            .map(value -> new com.careeros.application.AgentSession.PendingConfirmation(
                CandidateFacts.CandidateFactKey.valueOf(value.get("factKey")),
                value.get("question"),
                UUID.fromString(value.get("jobPostingId"))))
            .toList();
        return new com.careeros.application.AgentSession(entity.id(), entity.candidateProfileId, filters,
            entity.lastJobIds.stream().map(UUID::fromString).toList(), pending,
            entity.profileVersion, entity.updatedAt);
    }

    private JpaModels.AgentSessionEntity toAgentSessionEntity(com.careeros.application.AgentSession session) {
        var entity = new JpaModels.AgentSessionEntity();
        entity.id = session.sessionId();
        entity.candidateProfileId = session.candidateId();
        entity.filterTier = session.filters().tier() == null ? null : session.filters().tier().name();
        entity.filterLocation = session.filters().location();
        entity.filterJobFamily = session.filters().jobFamily() == null ? null : session.filters().jobFamily().name();
        entity.filterLimit = session.filters().limit();
        // 顺序就是语义：序号按位置解析，所以这里必须保持列表原序。
        entity.lastJobIds = session.lastJobIdsInOrder().stream().map(UUID::toString)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        entity.pendingConfirmations = session.pendingConfirmations().stream()
            .map(value -> {
                var row = new LinkedHashMap<String, String>();
                row.put("factKey", value.factKey().name());
                row.put("question", value.question());
                row.put("jobPostingId", value.jobPostingId().toString());
                return (Map<String, String>) row;
            })
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        entity.profileVersion = session.profileVersion();
        entity.updatedAt = session.updatedAt();
        return entity;
    }

    private com.careeros.application.personal.ProfileConfirmationPorts.LedgerEntry toLedgerEntry(
        JpaModels.ProfileConfirmationLedgerEntity entity
    ) {
        return new com.careeros.application.personal.ProfileConfirmationPorts.LedgerEntry(
            entity.id.candidateProfileId, entity.id.idempotencyKey,
            CandidateFacts.CandidateFactKey.valueOf(entity.factKey), entity.declaredValue,
            com.careeros.application.personal.ProfileConfirmationPorts.Stage.valueOf(entity.stage),
            entity.profileVersionBefore, entity.profileVersionAfter, entity.recordedAt);
    }

    private JpaModels.ProfileConfirmationLedgerEntity toLedgerEntity(
        com.careeros.application.personal.ProfileConfirmationPorts.LedgerEntry entry
    ) {
        var entity = new JpaModels.ProfileConfirmationLedgerEntity();
        entity.id = new JpaModels.ProfileConfirmationLedgerId(entry.candidateId(), entry.idempotencyKey());
        entity.factKey = entry.factKey().name();
        entity.declaredValue = entry.declaredValue();
        entity.stage = entry.stage().name();
        entity.profileVersionBefore = entry.profileVersionBefore();
        entity.profileVersionAfter = entry.profileVersionAfter();
        entity.recordedAt = entry.recordedAt();
        return entity;
    }

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
        @Override public JobPosting save(JobPosting value){var entity=toEntity.apply(value);entity.contentFingerprint=JobContentFingerprint.of(value);repository.findById(value.id()).ifPresent(existing->{entity.stableJobKey=existing.stableJobKey;entity.active=existing.active;entity.firstSeenAt=existing.firstSeenAt;entity.lastSeenAt=existing.lastSeenAt;});return toDomain.apply(repository.save(entity));}
    }
    private static final class CandidateAdapter extends GenericAdapter<CandidateProfile,JpaModels.CandidateProfileEntity> implements RepositoryPorts.CandidateProfiles {
        private final CandidateProfileJpaRepository repository;
        private final Function<JpaModels.CandidateProfileEntity,CandidateProfile> toDomain;
        CandidateAdapter(CandidateProfileJpaRepository r,Function<CandidateProfile,JpaModels.CandidateProfileEntity>a,Function<JpaModels.CandidateProfileEntity,CandidateProfile>b){super(r,a,b);repository=r;toDomain=b;}
        public Optional<CandidateProfile> findByIdForUpdate(UUID id){return repository.findByIdForUpdate(id).map(toDomain);}
    }
    private static final class PolicyAdapter extends GenericAdapter<PolicyRule,JpaModels.PolicyRuleEntity> implements RepositoryPorts.PolicyRules { PolicyAdapter(PolicyRuleJpaRepository r,Function<PolicyRule,JpaModels.PolicyRuleEntity>a,Function<JpaModels.PolicyRuleEntity,PolicyRule>b){super(r,a,b);} }
    private static final class EvidenceAdapter extends GenericAdapter<Evidence,JpaModels.EvidenceEntity> implements RepositoryPorts.EvidenceRecords { EvidenceAdapter(EvidenceJpaRepository r,Function<Evidence,JpaModels.EvidenceEntity>a,Function<JpaModels.EvidenceEntity,Evidence>b){super(r,a,b);} }
    private static final class AssessmentAdapter extends GenericAdapter<EligibilityAssessment,JpaModels.EligibilityAssessmentEntity> implements RepositoryPorts.EligibilityAssessments { AssessmentAdapter(EligibilityAssessmentJpaRepository r,Function<EligibilityAssessment,JpaModels.EligibilityAssessmentEntity>a,Function<JpaModels.EligibilityAssessmentEntity,EligibilityAssessment>b){super(r,a,b);} }
    private static final class OpportunityAdapter extends GenericAdapter<Opportunity,JpaModels.OpportunityEntity> implements RepositoryPorts.Opportunities { OpportunityAdapter(OpportunityJpaRepository r,Function<Opportunity,JpaModels.OpportunityEntity>a,Function<JpaModels.OpportunityEntity,Opportunity>b){super(r,a,b);} }

    private JpaModels.RecruitmentEventEntity toEventEntity(RecruitmentEvent value) {
        var e = new JpaModels.RecruitmentEventEntity();
        e.id=value.id(); e.title=value.title(); e.recruitmentYear=value.recruitmentYear();
        e.eventType=value.eventType(); e.publishedOn=value.publishedOn();
        e.applicationStartsOn=value.applicationStartsOn(); e.applicationEndsOn=value.applicationEndsOn();
        e.applicationStartsAt=value.applicationStartsAt(); e.applicationEndsAt=value.applicationEndsAt();
        e.ageReferenceDate=value.ageReferenceDate(); e.registrationUrl=value.registrationUrl();
        e.qualificationReviewEndsOn=value.qualificationReviewEndsOn(); e.paymentEndsOn=value.paymentEndsOn();
        e.admissionTicketStartsOn=value.admissionTicketStartsOn(); e.admissionTicketEndsOn=value.admissionTicketEndsOn();
        e.writtenExamOn=value.writtenExamOn(); e.writtenExamSubjects=new ArrayList<>(value.writtenExamSubjects());
        e.graduateRule=value.graduateRule(); e.overseasDegreeRule=value.overseasDegreeRule();
        e.experienceEvidenceRule=value.experienceEvidenceRule(); e.employmentStatement=value.employmentStatement();
        e.interviewRule=value.interviewRule(); e.graduateRuleJson=value.graduateEligibilityRule();
        e.writtenExamState=value.writtenExamState(); e.professionalTestState=value.professionalTestState();
        e.interviewState=value.interviewState(); e.interviewOn=value.interviewOn();
        e.interviewMethod=value.interviewMethod(); e.scoreFormula=value.scoreFormula(); e.sourceUrl=value.sourceUrl();
        var process=value.processFacts();
        e.noticeState=process.notice().state(); e.applicationState=process.application().state();
        e.qualificationReviewState=process.qualificationReview().state(); e.paymentState=process.payment().state();
        e.admissionTicketState=process.admissionTicket().state();
        e.physicalExamState=process.physicalExam().state(); e.physicalExamRule=process.physicalExam().detail();
        e.investigationState=process.investigation().state(); e.investigationRule=process.investigation().detail();
        e.publicationState=process.publication().state(); e.publicationRule=process.publication().detail();
        e.appointmentState=process.appointment().state(); e.appointmentRule=process.appointment().detail();
        e.defaultEmploymentType=value.defaultEmploymentType(); e.evidenceIds=new ArrayList<>(value.evidenceIds());
        return e;
    }
    private RecruitmentEvent toEvent(JpaModels.RecruitmentEventEntity e) {
        return new RecruitmentEvent(e.id,e.title,e.recruitmentYear,e.eventType,e.publishedOn,
            e.applicationStartsOn,e.applicationEndsOn,e.sourceUrl,e.defaultEmploymentType,e.evidenceIds,
            e.applicationStartsAt,e.applicationEndsAt,e.ageReferenceDate,e.registrationUrl,
            e.qualificationReviewEndsOn,e.paymentEndsOn,e.admissionTicketStartsOn,e.admissionTicketEndsOn,
            e.writtenExamOn,e.writtenExamSubjects,e.graduateRule,e.overseasDegreeRule,
            e.experienceEvidenceRule,e.employmentStatement,e.interviewRule,e.graduateRuleJson,
            e.writtenExamState,e.professionalTestState,e.interviewState,e.interviewOn,
            e.interviewMethod,e.scoreFormula,new RecruitmentProcessFacts(
                stage(e.noticeState,null),stage(e.applicationState,null),stage(e.qualificationReviewState,null),
                stage(e.paymentState,null),stage(e.admissionTicketState,null),stage(e.writtenExamState,null),
                stage(e.professionalTestState,null),stage(e.interviewState,e.interviewRule),
                stage(e.physicalExamState,e.physicalExamRule),stage(e.investigationState,e.investigationRule),
                stage(e.publicationState,e.publicationRule),stage(e.appointmentState,e.appointmentRule)));
    }

    private static RecruitmentProcessFacts.ProcessStage stage(
        com.careeros.domain.GraduateEligibilityRule.EvidenceState state, String detail) {
        return new RecruitmentProcessFacts.ProcessStage(state, detail);
    }

    private JpaModels.OrganizationEntity toOrganizationEntity(Organization value) { var e=new JpaModels.OrganizationEntity(); e.id=value.id(); e.name=value.name(); e.organizationType=value.organizationType(); e.administrativeLevel=value.administrativeLevel(); e.province=value.province(); e.city=value.city(); e.district=value.district(); e.parentOrganizationId=value.parentOrganizationId(); e.officialWebsite=value.officialWebsite(); return e; }
    private Organization toOrganization(JpaModels.OrganizationEntity e) { return new Organization(e.id,e.name,e.organizationType,e.administrativeLevel,e.province,e.city,e.district,e.parentOrganizationId,e.officialWebsite); }

    private JpaModels.JobPostingEntity toJobEntity(JobPosting value) {
        var e=new JpaModels.JobPostingEntity();
        e.id=value.id(); e.recruitmentEventId=value.recruitmentEventId(); e.organizationId=value.organizationId();
        e.externalJobCode=value.externalJobCode(); e.title=value.title(); e.jobFamily=value.jobFamily();
        e.employmentType=value.employmentType(); e.location=value.location(); e.headcount=value.headcount();
        e.minimumEducation=value.minimumEducation(); e.exactMajors=new LinkedHashSet<>(value.exactMajors());
        e.acceptedGraduationYears=new LinkedHashSet<>(value.acceptedGraduationYears());
        e.maximumAge=value.maximumAge(); e.ageReferenceDate=value.ageReferenceDate();
        e.minimumExperienceYears=value.minimumExperienceYears();
        e.requiredProfessionalTitles=new LinkedHashSet<>(value.requiredProfessionalTitles()); e.duties=value.duties();
        e.supervisingDepartment=value.supervisingDepartment(); e.jobCategory=value.jobCategory(); e.jobGrade=value.jobGrade();
        e.educationRequirementText=value.educationRequirementText(); e.degreeRequirement=value.degreeRequirement();
        e.majorRequirementText=value.majorRequirementText(); e.ageRequirementText=value.ageRequirementText();
        e.genderRequirement=value.genderRequirement(); e.candidateScope=value.candidateScope();
        e.otherRequirements=value.otherRequirements(); e.originalRequirementText=value.originalRequirementText();
        e.interviewRatio=value.interviewRatio(); e.professionalTestRequired=value.professionalTestRequired();
        e.contactPhone=value.contactPhone(); e.actualEmployer=value.actualEmployer(); e.worksite=value.worksite();
        e.employmentEvidence=value.employmentEvidence(); e.sourceUrl=value.sourceUrl();
        e.evidenceIds=new ArrayList<>(value.evidenceIds()); e.active=true;
        return e;
    }
    private JobPosting toJob(JpaModels.JobPostingEntity e) {
        return new JobPosting(e.id,e.recruitmentEventId,e.organizationId,e.externalJobCode,e.title,e.jobFamily,
            e.employmentType,e.location,e.headcount,e.minimumEducation,e.exactMajors,e.acceptedGraduationYears,
            e.maximumAge,e.ageReferenceDate,e.minimumExperienceYears,e.requiredProfessionalTitles,e.duties,
            e.sourceUrl,e.evidenceIds,e.supervisingDepartment,e.jobCategory,e.jobGrade,e.educationRequirementText,
            e.degreeRequirement,e.majorRequirementText,e.ageRequirementText,e.genderRequirement,e.candidateScope,
            e.otherRequirements,e.originalRequirementText,e.interviewRatio,e.professionalTestRequired,e.contactPhone,
            e.actualEmployer,e.worksite,e.employmentEvidence);
    }

    private JpaModels.CandidateProfileEntity toCandidateEntity(CandidateProfile value) { var e=new JpaModels.CandidateProfileEntity(); e.id=value.id(); e.displayName=value.displayName(); e.birthYear=value.birthDate().year(); e.birthMonth=value.birthDate().month(); e.birthDay=value.birthDate().day(); e.gender=value.gender(); e.politicalAffiliation=value.politicalAffiliation(); e.highestEducation=value.highestEducation(); e.majors=new LinkedHashSet<>(value.majors()); e.graduationYear=value.graduationYear(); e.experienceYears=value.experienceYears(); e.professionalTitles=new LinkedHashSet<>(value.professionalTitles()); e.preferredLocations=new ArrayList<>(value.preferredLocations()); e.acceptedEmploymentTypes=new LinkedHashSet<>(value.acceptedEmploymentTypes()); e.profileVersion=value.profileVersion(); e.skills=new LinkedHashSet<>(value.skills()); e.researchKeywords=new LinkedHashSet<>(value.researchKeywords()); e.targetJobFamilies=new LinkedHashSet<>(value.targetJobFamilies()); e.preferredOrganizationTypes=new LinkedHashSet<>(value.preferredOrganizationTypes()); e.educationRecords=new ArrayList<>(value.educationRecords().stream().map(this::toEducationValue).toList()); e.employmentRecords=new ArrayList<>(value.employmentRecords().stream().map(this::toEmploymentValue).toList()); e.employerSettlementAtApplication=value.employerSettlementAtApplication(); e.socialInsuranceAtApplication=value.socialInsuranceAtApplication(); return e; }
    private CandidateProfile toCandidate(JpaModels.CandidateProfileEntity e) { return new CandidateProfile(e.id,e.displayName,new PartialDate(e.birthYear,e.birthMonth,e.birthDay),e.highestEducation,e.majors,e.graduationYear,e.experienceYears,e.professionalTitles,e.preferredLocations,e.acceptedEmploymentTypes,e.profileVersion,e.skills,e.researchKeywords,e.targetJobFamilies,e.preferredOrganizationTypes,e.educationRecords.stream().map(this::toEducationRecord).toList(),e.gender,e.politicalAffiliation,e.employmentRecords.stream().map(this::toEmploymentRecord).toList(),e.employerSettlementAtApplication,e.socialInsuranceAtApplication); }

    private JpaModels.CandidateEducationValue toEducationValue(EducationRecord value) {
        var entity = new JpaModels.CandidateEducationValue();
        entity.institutionName=value.institutionName(); entity.countryOrRegion=value.countryOrRegion();
        entity.educationLevel=value.educationLevel(); entity.majorName=value.majorName();
        entity.graduationYear=value.graduationYear(); entity.graduationMonth=value.graduationMonth();
        entity.completionStatus=value.completionStatus();
        entity.credentialVerificationStatus=value.credentialVerificationStatus();
        return entity;
    }
    private EducationRecord toEducationRecord(JpaModels.CandidateEducationValue value) {
        return new EducationRecord(value.institutionName,value.countryOrRegion,value.educationLevel,value.majorName,
            value.graduationYear,value.graduationMonth,value.completionStatus,value.credentialVerificationStatus);
    }
    private JpaModels.CandidateEmploymentValue toEmploymentValue(CandidateEmploymentRecord value) {
        var entity = new JpaModels.CandidateEmploymentValue();
        entity.employerName=value.employerName(); entity.roleTitle=value.roleTitle();
        entity.startsOn=value.startsOn(); entity.endsOn=value.endsOn(); entity.employmentMode=value.employmentMode();
        entity.verificationStatus=value.verificationStatus(); entity.evidenceTypes=new LinkedHashSet<>(value.evidenceTypes());
        return entity;
    }
    private CandidateEmploymentRecord toEmploymentRecord(JpaModels.CandidateEmploymentValue value) {
        return new CandidateEmploymentRecord(value.employerName,value.roleTitle,value.startsOn,value.endsOn,
            value.employmentMode,value.verificationStatus,value.evidenceTypes);
    }

    private JpaModels.CandidateFactConfirmationEntity toCandidateFactEntity(CandidateFactConfirmation value) {
        var entity = new JpaModels.CandidateFactConfirmationEntity();
        entity.id = new JpaModels.CandidateFactConfirmationId(value.candidateProfileId(), value.factKey().name());
        entity.status = value.status(); entity.valueFingerprint = value.valueFingerprint(); entity.source = value.source();
        entity.confirmedAt = value.confirmedAt(); entity.updatedAt = value.updatedAt();
        return entity;
    }
    private CandidateFactConfirmation toCandidateFact(JpaModels.CandidateFactConfirmationEntity entity) {
        return new CandidateFactConfirmation(entity.id.candidateProfileId,
            CandidateFacts.CandidateFactKey.valueOf(entity.id.factKey), entity.status, entity.valueFingerprint,
            entity.source, entity.confirmedAt, entity.updatedAt);
    }

    private JpaModels.PolicyRuleEntity toPolicyEntity(PolicyRule value) { var e=new JpaModels.PolicyRuleEntity(); e.id=value.id(); e.ruleType=value.ruleType(); e.name=value.name(); e.jurisdiction=value.jurisdiction(); e.validFrom=value.validFrom(); e.validUntil=value.validUntil(); e.parameters=new LinkedHashMap<>(value.parameters()); e.evidenceId=value.evidenceId(); return e; }
    private PolicyRule toPolicy(JpaModels.PolicyRuleEntity e) { return new PolicyRule(e.id,e.ruleType,e.name,e.jurisdiction,e.validFrom,e.validUntil,e.parameters,e.evidenceId); }

    private JpaModels.EvidenceEntity toEvidenceEntity(Evidence value) { var e=new JpaModels.EvidenceEntity(); e.id=value.id(); e.sourceArtifactId=value.sourceArtifactId(); e.evidenceType=value.type(); e.sourceUrl=value.sourceUrl(); e.sourceTitle=value.sourceTitle(); e.excerpt=value.excerpt(); e.contentHash=value.contentHash(); e.capturedAt=value.capturedAt(); return e; }
    private Evidence toEvidence(JpaModels.EvidenceEntity e) { return new Evidence(e.id,e.sourceArtifactId,e.evidenceType,e.sourceUrl,e.sourceTitle,e.excerpt,e.contentHash,e.capturedAt); }

    /** V6 之前落库的行没有 evidenceIds，读成空表而不是让整行读不出来。 */
    @SuppressWarnings("unchecked")
    private static List<UUID> readEvidenceIds(Object raw) {
        if (!(raw instanceof java.util.Collection<?> values)) return List.of();
        return values.stream().map(String::valueOf).map(UUID::fromString).toList();
    }

    private JpaModels.EligibilityAssessmentEntity toAssessmentEntity(EligibilityAssessment value) { var e=new JpaModels.EligibilityAssessmentEntity(); e.id=value.id(); e.candidateProfileId=value.candidateProfileId(); e.jobPostingId=value.jobPostingId(); e.status=value.status(); value.ruleResults().forEach((key,result)->e.ruleResults.put(key.name(),Map.of("status",result.status().name(),"explanation",result.explanation(),"evidenceIds",result.evidenceIds().stream().map(UUID::toString).toList()))); e.evidenceIds=new ArrayList<>(value.evidenceIds()); e.evaluatorVersion=value.evaluatorVersion(); e.assessedAt=value.assessedAt(); e.profileVersion=value.profileVersion(); e.jobContentFingerprint=value.jobContentFingerprint(); return e; }
    private EligibilityAssessment toAssessment(JpaModels.EligibilityAssessmentEntity e) { var results=new EnumMap<RuleType,RuleResult>(RuleType.class); e.ruleResults.forEach((key,value)->results.put(RuleType.valueOf(key),new RuleResult(EligibilityStatus.valueOf(String.valueOf(value.get("status"))),String.valueOf(value.get("explanation")),readEvidenceIds(value.get("evidenceIds"))))); return new EligibilityAssessment(e.id,e.candidateProfileId,e.jobPostingId,e.status,results,e.evidenceIds,e.evaluatorVersion,e.assessedAt,e.profileVersion,e.jobContentFingerprint); }

    private JpaModels.OpportunityEntity toOpportunityEntity(Opportunity value) { var e=new JpaModels.OpportunityEntity(); e.id=value.id(); e.candidateProfileId=value.candidateProfileId(); e.jobPostingId=value.jobPostingId(); e.eligibilityAssessmentId=value.eligibilityAssessmentId(); e.status=value.status(); e.matchScore=value.matchScore(); e.decisionNote=value.decisionNote(); e.createdAt=value.createdAt(); e.updatedAt=value.updatedAt(); return e; }
    private Opportunity toOpportunity(JpaModels.OpportunityEntity e) { return new Opportunity(e.id,e.candidateProfileId,e.jobPostingId,e.eligibilityAssessmentId,e.status,e.matchScore,e.decisionNote,e.createdAt,e.updatedAt); }
}
