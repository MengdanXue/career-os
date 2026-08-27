package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquisitionHttpPorts.AttachmentDiscoverer;
import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.HttpReadContract;
import com.careeros.domain.acquisition.RecruitmentSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;

public final class HtmlAttachmentDiscoverer implements AttachmentDiscoverer {
    @Override
    public List<DiscoveredLink> discover(RecruitmentSource source, URI pageUri, byte[] html) {
        return discover(source, new DiscoveredLink(pageUri, "official announcement"), html);
    }

    @Override
    public List<DiscoveredLink> discover(
        RecruitmentSource source, DiscoveredLink page, byte[] html
    ) {
        Set<String> hosts = allowedHosts(source);
        String selector = source.configuration().getOrDefault("attachmentSelector", "a[href]").toString();
        var distinct = new LinkedHashMap<URI, DiscoveredLink>();
        var document = Jsoup.parse(new String(html, StandardCharsets.UTF_8), page.uri().toString());
        for (var anchor : document.select(selector)) {
            String href = anchor.attr("href").trim();
            if (href.isEmpty()) continue;
            URI uri;
            try { uri = CanonicalUri.normalize(page.uri().resolve(href)); }
            catch (IllegalArgumentException ignored) { continue; }
            if (!authorized(page.readContract(), hosts, uri)
                || !supportedCandidate(uri)) continue;
            String title = anchor.text().strip();
            if (title.isEmpty()) title = filename(uri);
            distinct.putIfAbsent(uri, new DiscoveredLink(uri, title, page.readContract()));
        }
        var result = new ArrayList<>(distinct.values());
        result.sort(java.util.Comparator.comparing(link -> link.uri().toString()));
        return List.copyOf(result);
    }

    private static boolean authorized(HttpReadContract contract, Set<String> legacyHosts, URI uri) {
        if (contract != null) return contract.authorizesTarget(uri);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
            && legacyHosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    private static Set<String> allowedHosts(RecruitmentSource source) {
        Set<String> result = new LinkedHashSet<>();
        result.add(source.baseUri().getHost().toLowerCase(Locale.ROOT));
        result.add(source.entryUri().getHost().toLowerCase(Locale.ROOT));
        Object configured = source.configuration().get("allowedHosts");
        if (configured instanceof Collection<?> values) {
            values.stream().map(Object::toString).map(value -> value.toLowerCase(Locale.ROOT)).forEach(result::add);
        }
        return Set.copyOf(result);
    }

    private static boolean supportedCandidate(URI uri) {
        String value = uri.toString().toLowerCase(Locale.ROOT);
        return value.matches(".*\\.(pdf|xls|xlsx)(?:[?#].*)?$")
            || value.contains("/module/download/") || value.contains("/downfile.")
            || downloadFilenameIsSupported(uri);
    }

    private static boolean downloadFilenameIsSupported(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (!path.endsWith("/document/download")) return false;
        String query = uri.getRawQuery();
        if (query == null) return false;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equalsIgnoreCase("fileName")) {
                String filename = pair[1].toLowerCase(Locale.ROOT);
                return filename.endsWith(".pdf") || filename.endsWith(".xls") || filename.endsWith(".xlsx");
            }
        }
        return false;
    }

    private static String filename(URI uri) {
        String path = uri.getPath();
        int slash = path == null ? -1 : path.lastIndexOf('/');
        String value = slash >= 0 ? path.substring(slash + 1) : path;
        return value == null || value.isBlank() ? "官方招聘附件" : value;
    }
}
