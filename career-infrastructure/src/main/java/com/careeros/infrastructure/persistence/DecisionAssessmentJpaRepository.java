package com.careeros.infrastructure.persistence;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DecisionAssessmentJpaRepository extends JpaRepository<DecisionJpaModels.DecisionAssessmentEntity,UUID> {
    Optional<DecisionJpaModels.DecisionAssessmentEntity> findByCandidateProfileIdAndJobPostingIdAndProfileVersionAndJobContentFingerprintAndEvaluatorVersion(UUID candidateId,UUID jobId,String profileVersion,String fingerprint,String evaluatorVersion);
    List<DecisionJpaModels.DecisionAssessmentEntity> findByCandidateProfileIdOrderByAssessedAtDesc(UUID candidateId);
}
