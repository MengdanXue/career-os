package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.SourceDiscoverer;
import com.careeros.domain.acquisition.RecruitmentSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

public final class HospitalOfficialEvidenceDiscoverer implements SourceDiscoverer {
    private static final Set<String> HTTPS_HOSTS = Set.of(
        "zp.hz-hospital.com",
        "www.hz-hospital.com",
        "wsjkw.hangzhou.gov.cn",
        "hrss.hangzhou.gov.cn"
    );

    @Override
    public List<DiscoveredLink> discover(RecruitmentSource source, URI pageUri, byte[] html) {
        Pattern include = Pattern.compile(required(source.configuration(), "titleIncludeRegex"));
        Pattern exclude = Pattern.compile(required(source.configuration(), "titleExcludeRegex"));
        return candidates(source, pageUri, html).stream()
            .filter(link -> include.matcher(link.title()).find())
            .filter(link -> !exclude.matcher(link.title()).find())
            .toList();
    }

    @Override
    public List<DiscoveredLink> discoverAll(RecruitmentSource source, URI pageUri, byte[] html) {
        return candidates(source, pageUri, html);
    }

    private static List<DiscoveredLink> candidates(
        RecruitmentSource source, URI pageUri, byte[] html
    ) {
        Pattern article = Pattern.compile(required(source.configuration(), "articleUrlRegex"));
        String selector = required(source.configuration(), "linkSelector");
        var distinct = new LinkedHashMap<URI, DiscoveredLink>();
        var document = Jsoup.parse(new String(html, StandardCharsets.UTF_8), pageUri.toString());
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
            if (title.isEmpty()
                || !"https".equalsIgnoreCase(resolved.getScheme())
                || !HTTPS_HOSTS.contains(resolved.getHost().toLowerCase(java.util.Locale.ROOT))
                || !article.matcher(resolved.toString()).matches()) continue;
            distinct.putIfAbsent(resolved, new DiscoveredLink(resolved, title));
        }
        var result = new ArrayList<>(distinct.values());
        result.sort(java.util.Comparator.comparing(link -> link.uri().toString()));
        return List.copyOf(result);
    }

    private static String required(Map<String, Object> configuration, String key) {
        Object value = configuration.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.toString();
    }
}
