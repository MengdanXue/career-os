package com.careeros.domain.acquisition;

import java.net.URI;
import java.util.Objects;

public record TargetSource(
    String code,
    String name,
    String routeCode,
    String region,
    AuthorityLevel authorityLevel,
    ConnectionStatus connectionStatus,
    String officialRootUrl
) {
    public enum AuthorityLevel { OFFICIAL_AGGREGATOR, OFFICIAL_ORGANIZATION }
    public enum ConnectionStatus { CONNECTED, PARTIAL, FAILED, NOT_CONNECTED }

    public TargetSource {
        code = required(code, "code");
        name = required(name, "name");
        routeCode = required(routeCode, "routeCode");
        region = required(region, "region");
        Objects.requireNonNull(authorityLevel, "authorityLevel");
        Objects.requireNonNull(connectionStatus, "connectionStatus");
        officialRootUrl = required(officialRootUrl, "officialRootUrl");
        URI root = URI.create(officialRootUrl);
        if (!("https".equalsIgnoreCase(root.getScheme()) || "http".equalsIgnoreCase(root.getScheme()))
            || root.getHost() == null) {
            throw new IllegalArgumentException("officialRootUrl must be an HTTP(S) URL");
        }
    }

    public boolean supportsAbsenceConclusion() {
        return connectionStatus == ConnectionStatus.CONNECTED;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
