package com.careeros.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateFactConfirmationJpaRepository extends JpaRepository<
    JpaModels.CandidateFactConfirmationEntity,
    JpaModels.CandidateFactConfirmationId
> {
    List<JpaModels.CandidateFactConfirmationEntity> findAllByIdCandidateProfileId(UUID candidateProfileId);
}
