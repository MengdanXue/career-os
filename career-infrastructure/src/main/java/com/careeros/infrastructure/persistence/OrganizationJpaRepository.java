package com.careeros.infrastructure.persistence;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OrganizationJpaRepository extends JpaRepository<JpaModels.OrganizationEntity, UUID> { Optional<JpaModels.OrganizationEntity> findFirstByName(String name); }
