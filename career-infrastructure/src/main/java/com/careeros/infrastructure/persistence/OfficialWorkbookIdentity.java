package com.careeros.infrastructure.persistence;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class OfficialWorkbookIdentity {
    private OfficialWorkbookIdentity() {}

    static String of(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) return "";
        try {
            URI uri = URI.create(sourceUrl.strip());
            String fileName = queryValue(uri, "fileName");
            if (fileName == null) return sourceUrl.strip();
            String authority = uri.getAuthority() == null ? "" : uri.getAuthority().toLowerCase(Locale.ROOT);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String identity = scheme + "://" + authority + path + "?fileName="
                + fileName.strip().toLowerCase(Locale.ROOT);
            String objectPath = stableObjectPath(queryValue(uri, "fileUrl"));
            return objectPath == null ? identity : identity + "&objectPath=" + objectPath;
        } catch (RuntimeException exception) {
            return sourceUrl.strip();
        }
    }

    private static String stableObjectPath(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI nested = URI.create(value.strip());
            String path = nested.getPath();
            if (path == null || path.isBlank()) return null;
            String lower = path.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".pdf"))) return null;
            String authority = nested.getAuthority() == null ? "" : nested.getAuthority().toLowerCase(Locale.ROOT);
            return authority + path;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String queryValue(URI uri, String expectedName) {
        if (uri.getRawQuery() == null) return null;
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) continue;
            String name = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            if (expectedName.equalsIgnoreCase(name)) {
                return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
