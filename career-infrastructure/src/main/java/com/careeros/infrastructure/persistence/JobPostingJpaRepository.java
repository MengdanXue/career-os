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
    boolean existsByRecruitmentEventIdAndActiveTrue(UUID recruitmentEventId);
    @Query("""
        select j from JpaModels$JobPostingEntity j
        where j.sourceUrl = :sourceUrl and j.organizationId = :organizationId
          and ((:externalJobCode is not null and j.externalJobCode = :externalJobCode)
            or (:externalJobCode is null and j.externalJobCode is null and j.title = :title))
        """)
    List<JpaModels.JobPostingEntity> findNaturalIdentityCandidates(
        @Param("sourceUrl") String sourceUrl,
        @Param("organizationId") UUID organizationId,
        @Param("externalJobCode") String externalJobCode,
        @Param("title") String title);
    List<JpaModels.JobPostingEntity> findByActiveTrue();
    List<JpaModels.JobPostingEntity> findBySourceUrlAndActiveTrue(String sourceUrl);
    long countByActiveFalse();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from JpaModels$JobPostingEntity j where j.id = :id")
    Optional<JpaModels.JobPostingEntity> findByIdForDecision(@Param("id") UUID id);
}
