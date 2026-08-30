package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquisitionHttpPorts.HttpReadContract;
import com.careeros.application.AcquisitionHttpPorts.TransportPolicy;
import com.careeros.domain.acquisition.RecruitmentSource;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ListingEntryContract(
    String code,
    URI entryUri,
    Role role,
    Mode mode,
    Set<Integer> recruitmentYears,
    Set<Integer> knownArchiveGapYears,
    boolean completenessRequired,
    String articleUrlRegex,
    String linkSelector,
    String titleIncludeRegex,
    String titleExcludeRegex,
    HttpReadContract readContract,
    Map<String, Object> configuration
) {
    public enum Role { PRIMARY, HISTORICAL, SECTOR, LIFECYCLE, CAMPAIGN_STATE }

    public enum Mode {
        JCMS_PARAM_JSON,
        STATIC_SUFFIX_TEMPLATE,
        LINKED_PAGE,
        QUERY_PAGE,
        JSON_API,
        EMBEDDED_DATA,
        CAMPAIGN_STATE,
        FIXED_EVIDENCE
    }

    public ListingEntryContract {
        code = requiredText(code, "entry code").trim();
        Objects.requireNonNull(entryUri, "entryUri");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(mode, "mode");
        recruitmentYears = recruitmentYears == null ? Set.of() : Set.copyOf(recruitmentYears);
        knownArchiveGapYears = knownArchiveGapYears == null ? Set.of() : Set.copyOf(knownArchiveGapYears);
        for (int year : recruitmentYears) {
            if (year < 2000 || year > 2100) {
                throw new IllegalArgumentException("entry recruitment year is invalid: " + year);
            }
        }
        for (int year : knownArchiveGapYears) {
            if (year < 2000 || year > 2100) {
                throw new IllegalArgumentException("known archive gap year is invalid: " + year);
            }
            if (!recruitmentYears.isEmpty() && !recruitmentYears.contains(year)) {
                throw new IllegalArgumentException(
                    "known archive gap year must be an applicable recruitment year: " + year);
            }
        }
        Objects.requireNonNull(readContract, "readContract");
        configuration = immutableMap(configuration == null ? Map.of() : configuration);
    }

    public static List<ListingEntryContract> from(RecruitmentSource source) {
        Objects.requireNonNull(source, "source");
        Object configuredEntries = source.configuration().get("listingEntries");
        List<ListingEntryContract> parsed = new ArrayList<>();
        if (configuredEntries == null) {
            parsed.add(parse(source, Map.of(), true));
        } else {
            if (!(configuredEntries instanceof List<?> entries) || entries.isEmpty()) {
                throw new IllegalArgumentException("listingEntries must be a nonempty array");
            }
            for (Object value : entries) {
                if (!(value instanceof Map<?, ?> raw)) {
                    throw new IllegalArgumentException("each listing entry must be an object");
                }
                parsed.add(parse(source, stringKeyed(raw), false));
            }
        }

        Set<String> codes = new LinkedHashSet<>();
        for (ListingEntryContract entry : parsed) {
            if (!codes.add(entry.code())) {
                throw new IllegalArgumentException("duplicate listing entry code: " + entry.code());
            }
        }
        return List.copyOf(parsed);
    }

    private static ListingEntryContract parse(
        RecruitmentSource source, Map<String, Object> entry, boolean legacy
    ) {
        Map<String, Object> merged = new LinkedHashMap<>(source.configuration());
        merged.remove("listingEntries");
        merged.putAll(entry);

        String code = legacy ? "legacy" : required(merged, "code");
        URI entryUri = legacy ? source.entryUri() : uri(merged);
        Role role = enumValue(
            Role.class, value(merged, "role", "PRIMARY"), "entry role for " + code);
        Mode mode = mode(merged, code);
        Set<Integer> years = years(merged);
        Set<Integer> knownArchiveGapYears = configuredYears(
            merged.get("knownArchiveGapYears"), "known archive gap year");
        boolean completenessRequired = booleanValue(
            merged.get("completenessRequired"),
            role != Role.LIFECYCLE && role != Role.CAMPAIGN_STATE);
        TransportPolicy transportPolicy = enumValue(
            TransportPolicy.class,
            value(merged, "transportPolicy", TransportPolicy.HTTPS_ONLY.name()),
            "transportPolicy");

        if ("http".equalsIgnoreCase(entryUri.getScheme())
            && transportPolicy != TransportPolicy.AUDITED_HTTP_READ_ONLY) {
            throw new IllegalArgumentException(
                "plain HTTP listing entries require AUDITED_HTTP_READ_ONLY");
        }
        if (transportPolicy == TransportPolicy.AUDITED_HTTP_READ_ONLY
            && !"http".equalsIgnoreCase(entryUri.getScheme())) {
            throw new IllegalArgumentException(
                "AUDITED_HTTP_READ_ONLY is only valid for a plain HTTP listing entry");
        }
        if (!"http".equalsIgnoreCase(entryUri.getScheme())
            && !"https".equalsIgnoreCase(entryUri.getScheme())) {
            throw new IllegalArgumentException("listing entry URI must use HTTP or HTTPS");
        }
        if (entryUri.getHost() == null || !entryUri.isAbsolute()) {
            throw new IllegalArgumentException("listing entry URI must be absolute with a host");
        }
        if (entryUri.getUserInfo() != null) {
            throw new IllegalArgumentException("listing entry URI must not contain user-info");
        }

        Set<String> exactHosts = hosts(source, entryUri, merged, transportPolicy);
        Set<String> exactAuthorities = strings(merged.get("exactAuthorities"));
        Set<String> pathPrefixes = strings(merged.get("allowedPathPrefixes"));
        HttpReadContract readContract = new HttpReadContract(
            transportPolicy, exactHosts, exactAuthorities, pathPrefixes);
        if (!readContract.authorizesTarget(entryUri)) {
            throw new IllegalArgumentException(
                "listing entry URI is outside its transport read contract");
        }

        return new ListingEntryContract(
            code, entryUri, role, mode, years, knownArchiveGapYears, completenessRequired,
            optionalText(merged.get("articleUrlRegex")),
            optionalText(merged.get("linkSelector")),
            optionalText(merged.get("titleIncludeRegex")),
            optionalText(merged.get("titleExcludeRegex")),
            readContract, merged);
    }

    private static Mode mode(Map<String, Object> values, String entryCode) {
        Object configured = values.get("mode");
        if (configured == null) configured = values.get("historicalPaginationMode");
        String mode = requiredText(configured == null ? null : configured.toString(), "entry mode")
            .toUpperCase(Locale.ROOT);
        if ("STATIC_PAGE_SUFFIX".equals(mode)) mode = Mode.STATIC_SUFFIX_TEMPLATE.name();
        if ("FIXED_HTTPS_EVIDENCE".equals(mode)) mode = Mode.FIXED_EVIDENCE.name();
        return enumValue(Mode.class, mode, "entry mode for " + entryCode);
    }

    private static URI uri(Map<String, Object> values) {
        Object configured = values.get("entryUri");
        if (configured == null) configured = values.get("uri");
        try {
            return URI.create(requiredText(
                configured == null ? null : configured.toString(), "entryUri"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("entryUri is invalid", invalid);
        }
    }

    private static Set<Integer> years(Map<String, Object> values) {
        Object configured = values.get("recruitmentYears");
        if (configured == null) configured = values.get("applicableYears");
        if (configured == null) configured = values.get("years");
        if (configured == null) return Set.of();
        return configuredYears(configured, "entry recruitment year");
    }

    private static Set<Integer> configuredYears(Object configured, String description) {
        if (configured == null) return Set.of();
        if (!(configured instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException(description + " values must be an array");
        }
        Set<Integer> years = new LinkedHashSet<>();
        for (Object value : iterable) {
            try {
                years.add(value instanceof Number number
                    ? new BigDecimal(number.toString()).intValueExact()
                    : Integer.parseInt(String.valueOf(value)));
            } catch (ArithmeticException | NumberFormatException invalid) {
                throw new IllegalArgumentException(description + " is invalid: " + value, invalid);
            }
        }
        return Set.copyOf(years);
    }

    private static Set<String> hosts(
        RecruitmentSource source,
        URI entryUri,
        Map<String, Object> values,
        TransportPolicy policy
    ) {
        Set<String> configured = strings(values.get("allowedHosts"));
        if (policy == TransportPolicy.AUDITED_HTTP_READ_ONLY) return configured;
        Set<String> hosts = new LinkedHashSet<>(configured);
        hosts.add(source.baseUri().getHost());
        hosts.add(source.entryUri().getHost());
        hosts.add(entryUri.getHost());
        return Set.copyOf(hosts);
    }

    private static Set<String> strings(Object configured) {
        if (configured == null) return Set.of();
        if (!(configured instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException("transport scope must be an array");
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object value : iterable) values.add(String.valueOf(value));
        return Set.copyOf(values);
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, immutableValue(value)));
        return Map.copyOf(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) return immutableMap(stringKeyed(map));
        if (value instanceof List<?> list) return list.stream()
            .map(ListingEntryContract::immutableValue).toList();
        if (value instanceof Set<?> set) return set.stream()
            .map(ListingEntryContract::immutableValue)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return value;
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean bool) return bool;
        if ("true".equalsIgnoreCase(value.toString())) return true;
        if ("false".equalsIgnoreCase(value.toString())) return false;
        throw new IllegalArgumentException("completenessRequired must be boolean");
    }

    private static String required(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return requiredText(value == null ? null : value.toString(), "entry " + key);
    }

    private static String value(Map<String, Object> values, String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private static String optionalText(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static <T extends Enum<T>> T enumValue(
        Class<T> type, String value, String field
    ) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("unsupported " + field + ": " + value, invalid);
        }
    }
}
