package com.careeros.infrastructure.persistence;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface JobPostingJpaRepository extends JpaRepository<JpaModels.JobPostingEntity, UUID> {
    Optional<JpaModels.JobPostingEntity> findByStableJobKey(String stableJobKey);
    List<JpaModels.JobPostingEntity> findByRecruitmentEventId(UUID recruitmentEventId);
    List<JpaModels.JobPostingEntity> findByActiveTrue();
    long countByActiveFalse();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from JpaModels$JobPostingEntity j where j.id = :id")
    Optional<JpaModels.JobPostingEntity> findByIdForDecision(@Param("id") UUID id);
}
