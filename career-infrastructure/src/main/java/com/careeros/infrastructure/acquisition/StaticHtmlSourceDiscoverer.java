package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.SourceDiscoverer;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.RecruitmentLifecycle;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

public final class StaticHtmlSourceDiscoverer implements SourceDiscoverer {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public List<DiscoveredLink> discover(RecruitmentSource source, URI pageUri, byte[] html) {
        Map<String, Object> config = source.configuration();
        Pattern include = Pattern.compile(required(config, "titleIncludeRegex"));
        Pattern exclude = Pattern.compile(required(config, "titleExcludeRegex"));
        return discoverAll(source, pageUri, html).stream()
            .filter(link -> include.matcher(link.title()).find())
            .filter(link -> !exclude.matcher(link.title()).find())
            .toList();
    }

    public List<DiscoveredLink> discover(
        RecruitmentSource source, ListingEntryContract entry, URI pageUri, byte[] html
    ) {
        Pattern include = Pattern.compile(required(entry.configuration(), "titleIncludeRegex"));
        Pattern exclude = Pattern.compile(required(entry.configuration(), "titleExcludeRegex"));
        return discoverAll(source, entry, pageUri, html).stream()
            .filter(link -> include.matcher(link.title()).find())
            .filter(link -> !exclude.matcher(link.title()).find())
            .toList();
    }

    public List<DiscoveredLink> discoverAll(
        RecruitmentSource source, ListingEntryContract entry, URI pageUri, byte[] html
    ) {
        Pattern article = Pattern.compile(required(entry.configuration(), "articleUrlRegex"));
        String selector = required(entry.configuration(), "linkSelector");
        var distinct = new LinkedHashMap<URI, DiscoveredLink>();
        var document = Jsoup.parse(htmlPayload(html), pageUri.toString());
        for (var element : document.select(selector)) {
            String href = element.attr("href").trim();
            if (href.isEmpty()) continue;
            URI resolved;
            try {
                resolved = CanonicalUri.normalize(pageUri.resolve(href));
            } catch (IllegalArgumentException exception) {
                continue;
            }
            String title = element.attr("title").strip();
            if (title.isEmpty()) title = element.text().strip();
            boolean allowedScheme = "https".equalsIgnoreCase(resolved.getScheme())
                || entry.readContract().transportPolicy()
                    == com.careeros.application.AcquisitionHttpPorts.TransportPolicy.AUDITED_HTTP_READ_ONLY
                    && "http".equalsIgnoreCase(resolved.getScheme());
            if (title.isEmpty() || !allowedScheme
                || entry.readContract().exactHosts().stream()
                    .noneMatch(host -> host.equalsIgnoreCase(resolved.getHost()))
                || !article.matcher(resolved.toString()).matches()) continue;
            distinct.putIfAbsent(resolved, new DiscoveredLink(resolved, title));
        }
        var result = new ArrayList<>(distinct.values());
        result.sort(discoveryOrder());
        return List.copyOf(result);
    }

    public List<DiscoveredLink> discover(OfficialSourceCatalog.SourceDefinition source, byte[] html) {
        Objects.requireNonNull(source, "source");
        if (!source.enabled() || source.strategy() == OfficialSourceCatalog.DiscoveryStrategy.UNSUPPORTED) {
            throw new IllegalArgumentException("Source is not enabled for deterministic discovery: " + source.code());
        }
        URI pageUri = URI.create(source.listingUrl());
        Pattern article = Pattern.compile(source.articleUrlRegex());
        Pattern include = Pattern.compile(source.titleIncludeRegex());
        Pattern exclude = Pattern.compile(source.titleExcludeRegex());
        var distinct = new LinkedHashMap<URI, DiscoveredLink>();
        var document = Jsoup.parse(htmlPayload(html), pageUri.toString());
        for (var element : document.select(source.linkSelector())) {
            String href = element.attr("href").trim();
            if (href.isEmpty()) continue;
            URI resolved;
            try {
                resolved = CanonicalUri.normalize(pageUri.resolve(href));
            } catch (IllegalArgumentException exception) {
                continue;
            }
            String title = element.attr("title").strip();
            if (title.isEmpty()) title = element.text().strip();
            if (!"https".equalsIgnoreCase(resolved.getScheme())
                || !pageUri.getHost().equalsIgnoreCase(resolved.getHost())
                || !article.matcher(resolved.toString()).matches()
                || !include.matcher(title).find()
                || exclude.matcher(title).find()) continue;
            distinct.putIfAbsent(resolved, new DiscoveredLink(resolved, title));
        }
        var result = new ArrayList<>(distinct.values());
        result.sort(discoveryOrder());
        return List.copyOf(result);
    }

    @Override
    public List<DiscoveredLink> discoverAll(RecruitmentSource source, URI pageUri, byte[] html) {
        Map<String, Object> config = source.configuration();
        Pattern article = Pattern.compile(required(config, "articleUrlRegex"));
        String selector = required(config, "linkSelector");
        String allowedHost = source.baseUri().getHost();
        var distinct = new LinkedHashMap<URI, DiscoveredLink>();
        var document = Jsoup.parse(htmlPayload(html), pageUri.toString());
        for (var element : document.select(selector)) {
            String href = element.attr("href").trim();
            if (href.isEmpty()) continue;
            URI resolved;
            try {
                resolved = CanonicalUri.normalize(pageUri.resolve(href));
            } catch (IllegalArgumentException exception) {
                continue;
            }
            String title = element.attr("title").strip();
            if (title.isEmpty()) title = element.text().strip();
            if (!"https".equalsIgnoreCase(resolved.getScheme())
                || !allowedHost.equalsIgnoreCase(resolved.getHost())
                || !article.matcher(resolved.toString()).matches()) continue;
            distinct.putIfAbsent(resolved, new DiscoveredLink(resolved, title));
        }
        var result = new ArrayList<>(distinct.values());
        result.sort(discoveryOrder());
        return List.copyOf(result);
    }

    private static java.util.Comparator<DiscoveredLink> discoveryOrder() {
        return java.util.Comparator
            .comparing((DiscoveredLink link) -> !RecruitmentLifecycle.classify(link.title()).isEmpty())
            .thenComparing(link -> link.uri().toString());
    }

    private static String required(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Source configuration requires " + key);
        }
        return text;
    }

    private static String htmlPayload(byte[] content) {
        String payload = new String(content, StandardCharsets.UTF_8);
        if (!payload.stripLeading().startsWith("{")) return payload;
        try {
            var root = JSON.readTree(payload);
            String html = root.path("data").path("html").asText();
            return html.isBlank() ? payload : html;
        } catch (Exception ignored) {
            return payload;
        }
    }
}
