package com.careeros.domain.acquisition;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RecruitmentSource(
    UUID id,
    String code,
    String name,
    URI baseUri,
    URI entryUri,
    SourceType sourceType,
    String region,
    CrawlMode crawlMode,
    boolean enabled,
    String cronExpression,
    String timeZone,
    Duration minimumRequestInterval,
    Map<String, Object> configuration,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    Instant nextDueAt,
    int consecutiveFailureCount,
    Instant createdAt,
    Instant updatedAt
) {
    public RecruitmentSource {
        Objects.requireNonNull(id, "id");
        code = requireText(code, "code");
        name = requireText(name, "name");
        requireHttps(baseUri, "baseUri");
        requireHttps(entryUri, "entryUri");
        Objects.requireNonNull(sourceType, "sourceType");
        region = requireText(region, "region");
        Objects.requireNonNull(crawlMode, "crawlMode");
        cronExpression = requireText(cronExpression, "cronExpression");
        timeZone = requireText(timeZone, "timeZone");
        Objects.requireNonNull(minimumRequestInterval, "minimumRequestInterval");
        if (minimumRequestInterval.isNegative()) throw new IllegalArgumentException("minimumRequestInterval cannot be negative");
        configuration = Map.copyOf(new LinkedHashMap<>(configuration == null ? Map.of() : configuration));
        if (consecutiveFailureCount < 0) throw new IllegalArgumentException("consecutiveFailureCount cannot be negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public RecruitmentSource succeeded(Instant at, Instant nextDue) {
        return new RecruitmentSource(id, code, name, baseUri, entryUri, sourceType, region, crawlMode,
            enabled, cronExpression, timeZone, minimumRequestInterval, configuration, at, lastFailureAt,
            nextDue, 0, createdAt, at);
    }

    public RecruitmentSource failed(Instant at, Instant nextDue) {
        return new RecruitmentSource(id, code, name, baseUri, entryUri, sourceType, region, crawlMode,
            enabled, cronExpression, timeZone, minimumRequestInterval, configuration, lastSuccessAt, at,
            nextDue, consecutiveFailureCount + 1, createdAt, at);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static void requireHttps(URI value, String field) {
        Objects.requireNonNull(value, field);
        if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null) {
            throw new IllegalArgumentException(field + " must be an absolute HTTPS URI");
        }
    }

    public enum SourceType {
        OFFICIAL_GOVERNMENT, OFFICIAL_ORGANIZATION, OFFICIAL_SOE,
        OFFICIAL_UNIVERSITY, AGGREGATOR, UNKNOWN
    }

    public enum CrawlMode { STATIC_HTML, PLAYWRIGHT }
}
