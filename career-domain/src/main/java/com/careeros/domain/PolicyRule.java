package com.careeros.domain;

import com.careeros.domain.DomainEnums.RuleType;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PolicyRule(UUID id, RuleType ruleType, String name, String jurisdiction, LocalDate validFrom, LocalDate validUntil, Map<String, String> parameters, UUID evidenceId) {
    public PolicyRule { Objects.requireNonNull(id); Objects.requireNonNull(ruleType); require(name, "name"); parameters = parameters == null ? Map.of() : Map.copyOf(parameters); }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); }
}
