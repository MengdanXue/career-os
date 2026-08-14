package com.careeros.domain;

import com.careeros.domain.DomainEnums.OrganizationType;
import java.util.Objects;
import java.util.UUID;

public record Organization(UUID id, String name, OrganizationType organizationType, String administrativeLevel, String province, String city, String district, UUID parentOrganizationId, String officialWebsite) {
    public Organization { Objects.requireNonNull(id); require(name, "name"); Objects.requireNonNull(organizationType); }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); }
}
