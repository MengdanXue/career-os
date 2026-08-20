package com.careeros.infrastructure.persistence;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EligibilityAssessmentJpaRepository extends JpaRepository<JpaModels.EligibilityAssessmentEntity, UUID> { Optional<JpaModels.EligibilityAssessmentEntity> findByCandidateProfileIdAndJobPostingIdAndProfileVersionAndJobContentFingerprintAndEvaluatorVersion(UUID candidateProfileId, UUID jobPostingId, String profileVersion, String jobContentFingerprint, String evaluatorVersion); }
