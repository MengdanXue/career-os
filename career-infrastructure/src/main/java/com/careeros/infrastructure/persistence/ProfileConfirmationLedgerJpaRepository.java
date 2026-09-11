package com.careeros.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileConfirmationLedgerJpaRepository extends JpaRepository<
    JpaModels.ProfileConfirmationLedgerEntity,
    JpaModels.ProfileConfirmationLedgerId
> {
}
