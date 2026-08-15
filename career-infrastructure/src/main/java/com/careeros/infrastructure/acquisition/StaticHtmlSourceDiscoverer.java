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
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

public final class StaticHtmlSourceDiscoverer implements SourceDiscoverer {
    @Override
    public List<DiscoveredLink> discover(RecruitmentSource source, URI pageUri, byte[] html) {
        Map<String, Object> config = source.configuration();
        Pattern article = Pattern.compile(required(config, "articleUrlRegex"));
        Pattern include = Pattern.compile(required(config, "titleIncludeRegex"));
        Pattern exclude = Pattern.compile(required(config, "titleExcludeRegex"));
        String selector = required(config, "linkSelector");
        String allowedHost = source.baseUri().getHost();
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
            String title = element.text().strip();
            if (title.isEmpty()) title = element.attr("title").strip();
            if (!"https".equalsIgnoreCase(resolved.getScheme())
                || !allowedHost.equalsIgnoreCase(resolved.getHost())
                || !article.matcher(resolved.toString()).matches()
                || !include.matcher(title).find()
                || exclude.matcher(title).find()) continue;
            distinct.putIfAbsent(resolved, new DiscoveredLink(resolved, title));
        }
        var result = new ArrayList<>(distinct.values());
        result.sort(java.util.Comparator.comparing(link -> link.uri().toString()));
        return List.copyOf(result);
    }

    private static String required(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Source configuration requires " + key);
        }
        return text;
    }
}
