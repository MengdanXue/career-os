package com.careeros.infrastructure.persistence;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface JobPostingJpaRepository extends JpaRepository<JpaModels.JobPostingEntity, UUID> { Optional<JpaModels.JobPostingEntity> findByStableJobKey(String stableJobKey); List<JpaModels.JobPostingEntity> findByRecruitmentEventId(UUID recruitmentEventId); List<JpaModels.JobPostingEntity> findByActiveTrue(); long countByActiveFalse(); }
