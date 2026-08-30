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
import java.util.regex.Pattern;
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
        Object imageSelector = source.configuration().get("imageEvidenceSelector");
        if (imageSelector != null && !imageSelector.toString().isBlank()) {
            for (var image : document.select(imageSelector.toString())) {
                String src = image.attr("src").trim();
                if (src.isEmpty()) continue;
                URI uri;
                try { uri = CanonicalUri.normalize(page.uri().resolve(src)); }
                catch (IllegalArgumentException ignored) { continue; }
                if (!authorized(page.readContract(), hosts, uri)) continue;
                String title = image.attr("alt").strip();
                if (title.isEmpty()) title = filename(uri);
                distinct.putIfAbsent(uri, new DiscoveredLink(uri, title, page.readContract()));
            }
        }
        discoverConfiguredScriptAttachments(source, page, document, hosts, distinct);
        var result = new ArrayList<>(distinct.values());
        result.sort(java.util.Comparator.comparing(link -> link.uri().toString()));
        return List.copyOf(result);
    }

    private static void discoverConfiguredScriptAttachments(
        RecruitmentSource source,
        DiscoveredLink page,
        org.jsoup.nodes.Document document,
        Set<String> hosts,
        LinkedHashMap<URI, DiscoveredLink> distinct
    ) {
        Object configured = source.configuration().get("scriptAttachmentVariable");
        if (configured == null || configured.toString().isBlank()) return;
        String variable = configured.toString().trim();
        if (!variable.matches("[A-Za-z_$][A-Za-z0-9_$]{0,63}")) {
            throw new IllegalArgumentException("scriptAttachmentVariable is invalid");
        }
        Pattern assignment = Pattern.compile(
            "(?s)(?:\\b(?:var|let|const)\\s+)?\\b" + Pattern.quote(variable)
                + "\\s*=\\s*(['\"])(.{0,65536}?)\\1");
        int accepted = 0;
        for (var script : document.select("script")) {
            var matcher = assignment.matcher(script.data());
            while (matcher.find() && accepted < 50) {
                for (String candidate : matcher.group(2).split("\\|", -1)) {
                    if (accepted >= 50) break;
                    String value = candidate.trim();
                    if (!value.matches("(?i)^\\./[A-Za-z0-9][A-Za-z0-9._-]{0,254}\\.(?:pdf|xls|xlsx)(?:[?#].*)?$")) {
                        continue;
                    }
                    URI uri;
                    try { uri = CanonicalUri.normalize(page.uri().resolve(value)); }
                    catch (IllegalArgumentException ignored) { continue; }
                    if (!authorized(page.readContract(), hosts, uri)
                        || !supportedCandidate(uri)) continue;
                    distinct.putIfAbsent(uri,
                        new DiscoveredLink(uri, filename(uri), page.readContract()));
                    accepted++;
                }
            }
        }
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
            || downloadFilenameIsSupported(uri) || hospitalAccessoryDownload(uri);
    }

    private static boolean hospitalAccessoryDownload(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!path.equalsIgnoreCase("/apply/downloadAccessory.action")) return false;
        String query = uri.getRawQuery();
        if (query == null) return false;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equalsIgnoreCase("keycode") && !pair[1].isBlank()) {
                return true;
            }
        }
        return false;
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
