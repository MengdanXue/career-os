package com.careeros.infrastructure.persistence;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OrganizationStabilityFactJpaRepository extends JpaRepository<DecisionJpaModels.OrganizationStabilityFactEntity,UUID> { List<DecisionJpaModels.OrganizationStabilityFactEntity> findByOrganizationId(UUID organizationId); }
