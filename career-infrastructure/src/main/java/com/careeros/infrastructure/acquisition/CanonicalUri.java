package com.careeros.infrastructure.acquisition;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

public final class CanonicalUri {
    private static final Set<String> TRACKING = Set.of("from", "spm", "source", "ref");
    private CanonicalUri() {}

    public static URI normalize(URI input) {
        if (input == null || !input.isAbsolute() || input.getHost() == null) {
            throw new IllegalArgumentException("URI must be absolute and have a host");
        }
        String scheme = input.getScheme().toLowerCase(Locale.ROOT);
        String host = input.getHost().toLowerCase(Locale.ROOT);
        int port = input.getPort();
        if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
        String path = input.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        String query = normalizeQuery(input.getRawQuery());
        StringBuilder normalized = new StringBuilder(scheme).append("://");
        if (input.getRawUserInfo() != null) normalized.append(input.getRawUserInfo()).append('@');
        normalized.append(host.contains(":") ? '[' + host + ']' : host);
        if (port >= 0) normalized.append(':').append(port);
        normalized.append(path);
        if (query != null) normalized.append('?').append(query);
        return URI.create(normalized.toString()).normalize();
    }

    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        var values = new ArrayList<String>();
        for (String part : rawQuery.split("&")) {
            if (part.isBlank()) continue;
            String key = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (key.startsWith("utm_") || TRACKING.contains(key)) continue;
            values.add(part);
        }
        values.sort(Comparator.naturalOrder());
        return values.isEmpty() ? null : String.join("&", values);
    }
}
