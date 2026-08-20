package com.careeros.infrastructure.persistence;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface CandidateProfileJpaRepository extends JpaRepository<JpaModels.CandidateProfileEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select candidate from JpaModels$CandidateProfileEntity candidate where candidate.id = :id")
    Optional<JpaModels.CandidateProfileEntity> findByIdForUpdate(@Param("id") UUID id);
}
