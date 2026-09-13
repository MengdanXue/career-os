package com.careeros.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateJobWatchJpaRepository extends JpaRepository<
    JpaModels.CandidateJobWatchEntity,
    JpaModels.CandidateJobWatchId
> {
    List<JpaModels.CandidateJobWatchEntity> findAllByIdCandidateProfileId(UUID candidateProfileId);
}
