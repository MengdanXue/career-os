package com.careeros.infrastructure.persistence;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OpportunityJpaRepository extends JpaRepository<JpaModels.OpportunityEntity, UUID> { Optional<JpaModels.OpportunityEntity> findByCandidateProfileIdAndJobPostingId(UUID candidateProfileId, UUID jobPostingId); }
