package com.careeros.infrastructure.acquisition;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record OfficialSourceCatalog(List<SourceDefinition> sources) {
    private static final String RESOURCE = "official-source-catalog.yml";

    public enum DiscoveryStrategy { JCMS_LISTING, STATIC_HTML, UNSUPPORTED }

    public record SourceDefinition(
        String code,
        String name,
        String routeCode,
        String officialRootUrl,
        String listingUrl,
        DiscoveryStrategy strategy,
        List<Integer> historicalYears,
        boolean enabled,
        String articleUrlRegex,
        String linkSelector,
        String titleIncludeRegex,
        String titleExcludeRegex,
        String historicalPaginationMode,
        String adapterType,
        List<Map<String, Object>> listingEntries,
        String imageEvidenceSelector,
        String scriptAttachmentVariable,
        List<String> allowedHosts,
        List<String> allowedPathPrefixes,
        String attachmentSelector,
        boolean listingAbsenceDeactivationEnabled
    ) {
        public SourceDefinition {
            code = required(code, "code");
            name = required(name, "name");
            routeCode = required(routeCode, "routeCode");
            officialRootUrl = httpsUrl(officialRootUrl, "officialRootUrl");
            listingUrl = optionalHttpsUrl(listingUrl, "listingUrl");
            Objects.requireNonNull(strategy, "strategy");
            historicalYears = historicalYears == null ? List.of() : List.copyOf(historicalYears);
            listingEntries = immutableEntries(listingEntries);
            boolean multiEntry = !listingEntries.isEmpty();
            if (enabled && ((listingUrl == null && !multiEntry)
                || strategy == DiscoveryStrategy.UNSUPPORTED)) {
                throw new IllegalArgumentException(code + " enabled source requires a supported listing URL");
            }
            if (enabled && !multiEntry && (articleUrlRegex == null || articleUrlRegex.isBlank())) {
                throw new IllegalArgumentException(code + " enabled source requires articleUrlRegex");
            }
            linkSelector = optional(linkSelector) == null ? "a[href]" : linkSelector.trim();
            titleIncludeRegex = optional(titleIncludeRegex) == null ? "招聘|招考|选聘|引进" : titleIncludeRegex.trim();
            titleExcludeRegex = optional(titleExcludeRegex) == null ? "拟聘|公示|成绩|体检|递补" : titleExcludeRegex.trim();
            historicalPaginationMode = optional(historicalPaginationMode);
            adapterType = optional(adapterType);
            imageEvidenceSelector = optional(imageEvidenceSelector);
            scriptAttachmentVariable = optional(scriptAttachmentVariable);
            allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
            allowedPathPrefixes = allowedPathPrefixes == null ? List.of() : List.copyOf(allowedPathPrefixes);
            attachmentSelector = optional(attachmentSelector);
            if (enabled && !multiEntry && (historicalPaginationMode == null || adapterType == null)) {
                throw new IllegalArgumentException(code + " enabled source requires pagination and adapter contracts");
            }
        }

        public SourceDefinition(
            String code, String name, String routeCode, String officialRootUrl,
            String listingUrl, DiscoveryStrategy strategy, List<Integer> historicalYears,
            boolean enabled, String articleUrlRegex, String linkSelector,
            String titleIncludeRegex, String titleExcludeRegex,
            String historicalPaginationMode, String adapterType
        ) {
            this(code, name, routeCode, officialRootUrl, listingUrl, strategy, historicalYears,
                enabled, articleUrlRegex, linkSelector, titleIncludeRegex, titleExcludeRegex,
                historicalPaginationMode, adapterType, List.of(), null, null, List.of(), List.of(), null, false);
        }

        public SourceDefinition(
            String code, String name, String routeCode, String officialRootUrl,
            String listingUrl, DiscoveryStrategy strategy, List<Integer> historicalYears,
            boolean enabled, String articleUrlRegex, String linkSelector,
            String titleIncludeRegex, String titleExcludeRegex,
            String historicalPaginationMode, String adapterType,
            List<Map<String, Object>> listingEntries
        ) {
            this(code, name, routeCode, officialRootUrl, listingUrl, strategy, historicalYears,
                enabled, articleUrlRegex, linkSelector, titleIncludeRegex, titleExcludeRegex,
                historicalPaginationMode, adapterType, listingEntries, null, null, List.of(), List.of(), null, false);
        }

        public SourceDefinition(
            String code, String name, String routeCode, String officialRootUrl,
            String listingUrl, DiscoveryStrategy strategy, List<Integer> historicalYears,
            boolean enabled, String articleUrlRegex, String linkSelector,
            String titleIncludeRegex, String titleExcludeRegex,
            String historicalPaginationMode, String adapterType,
            List<Map<String, Object>> listingEntries,
            String imageEvidenceSelector, String scriptAttachmentVariable
        ) {
            this(code, name, routeCode, officialRootUrl, listingUrl, strategy, historicalYears,
                enabled, articleUrlRegex, linkSelector, titleIncludeRegex, titleExcludeRegex,
                historicalPaginationMode, adapterType, listingEntries, imageEvidenceSelector,
                scriptAttachmentVariable, List.of(), List.of(), null, false);
        }

        public Map<String, Object> configuration() {
            Map<String, Object> values = new LinkedHashMap<>();
            if (historicalPaginationMode != null) values.put("historicalPaginationMode", historicalPaginationMode);
            if (adapterType != null) values.put("adapterType", adapterType);
            values.put("historicalYears", historicalYears);
            if (!listingEntries.isEmpty()) values.put("listingEntries", listingEntries);
            if (imageEvidenceSelector != null) values.put("imageEvidenceSelector", imageEvidenceSelector);
            if (scriptAttachmentVariable != null) {
                values.put("scriptAttachmentVariable", scriptAttachmentVariable);
            }
            if (!allowedHosts.isEmpty()) values.put("allowedHosts", allowedHosts);
            if (!allowedPathPrefixes.isEmpty()) {
                values.put("allowedPathPrefixes", allowedPathPrefixes);
            }
            if (attachmentSelector != null) values.put("attachmentSelector", attachmentSelector);
            if (listingAbsenceDeactivationEnabled) {
                values.put("listingAbsenceDeactivationEnabled", true);
            }
            return Map.copyOf(values);
        }

        private static List<Map<String, Object>> immutableEntries(
            List<Map<String, Object>> entries
        ) {
            if (entries == null || entries.isEmpty()) return List.of();
            return entries.stream().map(SourceDefinition::immutableMap).toList();
        }

        private static Map<String, Object> immutableMap(Map<String, Object> values) {
            Map<String, Object> result = new LinkedHashMap<>();
            values.forEach((key, value) -> result.put(key, immutableValue(value)));
            return Map.copyOf(result);
        }

        private static Object immutableValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> values = new LinkedHashMap<>();
                map.forEach((key, item) -> values.put(String.valueOf(key), item));
                return immutableMap(values);
            }
            if (value instanceof List<?> list) {
                return list.stream().map(SourceDefinition::immutableValue).toList();
            }
            return value;
        }
    }

    public OfficialSourceCatalog {
        sources = sources == null ? List.of() : List.copyOf(sources);
        var codes = new HashSet<String>();
        for (SourceDefinition source : sources) {
            if (!codes.add(source.code())) throw new IllegalArgumentException("Duplicate source code: " + source.code());
        }
    }

    public static OfficialSourceCatalog load() {
        try (InputStream input = OfficialSourceCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing " + RESOURCE);
            var mapper = new ObjectMapper(new YAMLFactory())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            CatalogDocument document = mapper.readValue(input, CatalogDocument.class);
            return new OfficialSourceCatalog(document.sources());
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load " + RESOURCE, exception);
        }
    }

    private record CatalogDocument(List<SourceDefinition> sources) {}

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String httpsUrl(String value, String field) {
        String result = required(value, field);
        URI uri = URI.create(result);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException(field + " must be an HTTPS URL");
        }
        return result;
    }

    private static String optionalHttpsUrl(String value, String field) {
        String result = optional(value);
        return result == null ? null : httpsUrl(result, field);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
