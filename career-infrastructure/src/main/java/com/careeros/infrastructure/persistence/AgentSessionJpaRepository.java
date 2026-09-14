package com.careeros.infrastructure.persistence;

import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentSessionJpaRepository extends JpaRepository<JpaModels.AgentSessionEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from JpaModels$AgentSessionEntity session where session.id = :id")
    Optional<JpaModels.AgentSessionEntity> findByIdForUpdate(@Param("id") UUID id);
}
