package com.careeros.application;

import com.careeros.domain.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.careeros.domain.CandidateFacts.CandidateFactConfirmation;

public final class RepositoryPorts {
    private RepositoryPorts() {}
    public interface Repository<T> { T save(T aggregate); Optional<T> findById(UUID id); List<T> findAll(); void deleteById(UUID id); }
    public interface RecruitmentEvents extends Repository<RecruitmentEvent> {}
    public interface Organizations extends Repository<Organization> {}
    public interface JobPostings extends Repository<JobPosting> {}
    public interface CandidateProfiles extends Repository<CandidateProfile> {
        Optional<CandidateProfile> findByIdForUpdate(UUID id);
    }
    public interface CandidateFactConfirmations {
        List<CandidateFactConfirmation> findByCandidateId(UUID candidateId);
        List<CandidateFactConfirmation> saveAll(List<CandidateFactConfirmation> confirmations);
    }
    public interface PolicyRules extends Repository<PolicyRule> {}
    public interface EvidenceRecords extends Repository<Evidence> {}
    public interface EligibilityAssessments extends Repository<EligibilityAssessment> {}
    public interface Opportunities extends Repository<Opportunity> {}
}
