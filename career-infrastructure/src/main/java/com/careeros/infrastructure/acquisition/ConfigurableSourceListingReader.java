package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.DocumentFetcher;
import com.careeros.application.AcquisitionHttpPorts.FetchFailedException;
import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.FetchedDocument;
import com.careeros.application.AcquisitionHttpPorts.ListingEvidence;
import com.careeros.application.AcquisitionHttpPorts.ListingQuery;
import com.careeros.application.AcquisitionHttpPorts.ListingResult;
import com.careeros.application.AcquisitionHttpPorts.SourceDiscoverer;
import com.careeros.application.AcquisitionHttpPorts.SourceListingReader;
import com.careeros.application.AcquisitionHttpPorts.YearDiscoveredLink;
import com.careeros.domain.acquisition.RecruitmentSource;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

public final class ConfigurableSourceListingReader implements SourceListingReader {
    private static final Pattern YEAR = Pattern.compile("(?:^|[/\\s（(])(20\\d{2})(?:[/\\s年）)]|$)");
    private static final Pattern URL_DATE = Pattern.compile("/(20\\d{2})/(\\d{1,2})/(\\d{1,2})/");
    private static final Pattern LISTING_TOTAL = Pattern.compile("\\bcount\\s*=\\s*\"(\\d+)\"");
    private static final long MAX_LISTING_BYTES = 26_214_400L;
    private final DocumentFetcher fetcher;
    private final SourceDiscoverer discoverer;

    public ConfigurableSourceListingReader(DocumentFetcher fetcher, SourceDiscoverer discoverer) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.discoverer = Objects.requireNonNull(discoverer, "discoverer");
    }

    @Override
    public ListingResult read(RecruitmentSource source, ListingQuery query) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(query, "query");
        String mode = String.valueOf(source.configuration().get("historicalPaginationMode"));
        return switch (mode) {
            case "STATIC_PAGE_SUFFIX" -> readStaticSuffix(source, query);
            case "LINKED_PAGE" -> readLinkedPages(source, query);
            case "JCMS_PARAM_JSON" -> readJcms(source, query);
            case "FIXED_HTTPS_EVIDENCE" -> readFixedEvidence(source, query);
            default -> throw new IllegalArgumentException("Unsupported historicalPaginationMode: " + mode);
        };
    }

    private ListingResult readLinkedPages(RecruitmentSource source, ListingQuery query) {
        int maxPages = positive(source.configuration(), "historicalMaxPages");
        String nextPageSelector = required(source.configuration(), "nextPageSelector");
        LinkedHashMap<URI, YearDiscoveredLink> accepted = new LinkedHashMap<>();
        Set<URI> visitedPages = new HashSet<>();
        Set<String> pageFingerprints = new HashSet<>();
        int rawCount = 0;
        URI current = source.entryUri();
        for (int page = 1; page <= maxPages; page++) {
            current = canonical(current);
            if (!visitedPages.add(current)) {
                throw new FetchFailedException("Historical listing next-page link formed a cycle");
            }
            FetchedDocument fetched = fetch(source, current);
            if (!pageFingerprints.add(sha256(fetched.content()))) {
                throw new FetchFailedException("Historical listing repeated a non-terminal page");
            }
            List<DiscoveredLink> raw = canonicalDistinct(
                discoverer.discoverAll(source, fetched.finalUri(), fetched.content()));
            List<DiscoveredLink> candidates = canonicalDistinct(
                discoverer.discover(source, fetched.finalUri(), fetched.content()));
            rawCount += raw.size();
            for (DiscoveredLink link : candidates) {
                recruitmentYear(link).filter(query.recruitmentYears()::contains)
                    .ifPresent(year -> accepted.putIfAbsent(link.uri(), new YearDiscoveredLink(link, year)));
            }
            Optional<URI> next = nextPageUri(source, fetched.finalUri(), fetched.content(), nextPageSelector);
            if (next.isEmpty()) {
                return completed(query, List.copyOf(accepted.values()), page, rawCount,
                    "NO_NEXT_LINK", "official next-link traversal pages=" + page);
            }
            if (page == maxPages) {
                throw new FetchFailedException("Historical listing exceeded configured page limit");
            }
            current = next.orElseThrow();
        }
        throw new FetchFailedException("Historical listing exceeded configured page limit");
    }

    private ListingResult readStaticSuffix(RecruitmentSource source, ListingQuery query) {
        int maxPages = positive(source.configuration(), "historicalMaxPages");
        LinkedHashMap<URI, YearDiscoveredLink> accepted = new LinkedHashMap<>();
        Set<String> pageFingerprints = new HashSet<>();
        int rawCount = 0;
        int pageCount = 0;
        String stopReason = null;
        for (int page = 1; page <= maxPages; page++) {
            FetchedDocument fetched = fetch(source, staticPageUri(source.entryUri(), page));
            pageCount++;
            if (!pageFingerprints.add(sha256(fetched.content()))) {
                throw new FetchFailedException("Historical listing repeated a non-terminal page");
            }
            List<DiscoveredLink> raw = canonicalDistinct(
                discoverer.discoverAll(source, fetched.finalUri(), fetched.content()));
            List<DiscoveredLink> candidates = canonicalDistinct(
                discoverer.discover(source, fetched.finalUri(), fetched.content()));
            rawCount += raw.size();
            for (DiscoveredLink link : candidates) {
                Optional<Integer> year = recruitmentYear(link);
                if (year.isEmpty()) continue;
                int value = year.orElseThrow();
                if (query.recruitmentYears().contains(value)) {
                    accepted.putIfAbsent(link.uri(), new YearDiscoveredLink(link, value));
                }
            }
            if (raw.isEmpty()) {
                stopReason = "EMPTY_PAGE";
                break;
            }
        }
        if (stopReason == null) throw new FetchFailedException("Historical listing exceeded configured page limit");
        return completed(query, List.copyOf(accepted.values()), pageCount, rawCount,
            stopReason, "static suffix pages=" + pageCount + "; stop=" + stopReason);
    }

    private ListingResult readJcms(RecruitmentSource source, ListingQuery query) {
        int pageSize = positive(source.configuration(), "historicalPageSize");
        int maxPages = positive(source.configuration(), "historicalMaxPages");
        LinkedHashMap<URI, DiscoveredLink> rawDistinct = new LinkedHashMap<>();
        LinkedHashMap<URI, YearDiscoveredLink> accepted = new LinkedHashMap<>();
        Set<String> pageFingerprints = new HashSet<>();
        Integer total = null;
        for (int page = 1; page <= maxPages; page++) {
            FetchedDocument fetched = fetch(source, jcmsPageUri(source, page, pageSize));
            int reported = listingTotal(fetched.content());
            if (total == null) total = reported;
            else if (!total.equals(reported)) {
                throw new FetchFailedException("Historical listing total changed during traversal");
            }
            if (!pageFingerprints.add(sha256(fetched.content())) && (long) (page - 1) * pageSize < total) {
                throw new FetchFailedException("Historical listing repeated a non-terminal page");
            }
            int before = rawDistinct.size();
            for (DiscoveredLink link : canonicalDistinct(
                discoverer.discoverAll(source, fetched.finalUri(), fetched.content()))) {
                rawDistinct.putIfAbsent(link.uri(), link);
            }
            for (DiscoveredLink link : canonicalDistinct(
                discoverer.discover(source, fetched.finalUri(), fetched.content()))) {
                recruitmentYear(link).filter(query.recruitmentYears()::contains)
                    .ifPresent(year -> accepted.putIfAbsent(link.uri(), new YearDiscoveredLink(link, year)));
            }
            if (page > 1 && rawDistinct.size() == before && before < total) {
                throw new FetchFailedException("Historical listing page did not add any new entry");
            }
            if ((long) page * pageSize >= total) {
                if (rawDistinct.size() != total) {
                    throw new FetchFailedException("Historical listing unique entry count did not match reported total");
                }
                String basis = "official listing total=" + total + "; traversed pages=" + page
                    + "; pageSize=" + pageSize;
                return completed(query, List.copyOf(accepted.values()), page, total,
                    "REPORTED_TOTAL_REACHED", basis);
            }
        }
        throw new FetchFailedException("Historical listing exceeded configured page limit");
    }

    private ListingResult readFixedEvidence(RecruitmentSource source, ListingQuery query) {
        Object configured = source.configuration().get("historicalEvidenceByYear");
        if (!(configured instanceof Map<?, ?> evidence)) {
            throw new IllegalArgumentException("historicalEvidenceByYear is required for FIXED_HTTPS_EVIDENCE");
        }
        List<YearDiscoveredLink> links = new ArrayList<>();
        Map<Integer, ListingEvidence> byYear = new LinkedHashMap<>();
        for (int year : query.recruitmentYears()) {
            Object values = evidence.get(String.valueOf(year));
            if (values == null) values = evidence.get(year);
            List<?> uris = values instanceof List<?> list ? list : List.of();
            int accepted = 0;
            for (Object value : uris) {
                URI uri = URI.create(String.valueOf(value));
                requireOfficialHttps(source, uri);
                links.add(new YearDiscoveredLink(new DiscoveredLink(uri, year + "年官方招聘公告"), year));
                accepted++;
            }
            byYear.put(year, new ListingEvidence(0, accepted, accepted, 0, 0,
                null, null, false, "FIXED_EVIDENCE_SET", "explicit official HTTPS evidence"));
        }
        return new ListingResult(links, byYear);
    }

    private ListingResult completed(
        ListingQuery query, List<YearDiscoveredLink> links, int pageCount,
        int rawCount, String stopReason, String completionBasis
    ) {
        Map<Integer, ListingEvidence> evidence = new LinkedHashMap<>();
        for (int year : query.recruitmentYears()) {
            List<YearDiscoveredLink> annual = links.stream().filter(link -> link.recruitmentYear() == year).toList();
            LocalDate earliest = annual.stream().map(link -> publishedDate(link.link()))
                .flatMap(Optional::stream).min(LocalDate::compareTo).orElse(null);
            LocalDate latest = annual.stream().map(link -> publishedDate(link.link()))
                .flatMap(Optional::stream).max(LocalDate::compareTo).orElse(null);
            evidence.put(year, new ListingEvidence(pageCount, rawCount, annual.size(),
                Math.max(0, rawCount - links.size()), 0, earliest, latest,
                true, stopReason, completionBasis));
        }
        return new ListingResult(links, evidence);
    }

    private FetchedDocument fetch(RecruitmentSource source, URI uri) {
        requireOfficialHttps(source, uri);
        FetchedDocument fetched = fetcher.fetch(new FetchRequest(uri, allowedHosts(source), null, null,
            Duration.ofSeconds(20), MAX_LISTING_BYTES, source.id(), source.minimumRequestInterval()));
        if (fetched.status() != 200 || fetched.content().length == 0) {
            throw new FetchFailedException("Historical list page did not return content: " + fetched.status());
        }
        return fetched;
    }

    private static Set<String> allowedHosts(RecruitmentSource source) {
        Set<String> hosts = new java.util.LinkedHashSet<>();
        hosts.add(source.baseUri().getHost());
        hosts.add(source.entryUri().getHost());
        Object configured = source.configuration().get("allowedHosts");
        if (configured instanceof Iterable<?> values) values.forEach(value -> hosts.add(String.valueOf(value)));
        return Set.copyOf(hosts);
    }

    private static void requireOfficialHttps(RecruitmentSource source, URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
            || allowedHosts(source).stream().noneMatch(host -> host.equalsIgnoreCase(uri.getHost()))) {
            throw new IllegalArgumentException("Listing URI must use HTTPS on an allowed official host");
        }
    }

    private static URI jcmsPageUri(RecruitmentSource source, int page, int pageSize) {
        Object configured = source.configuration().get("listingApiUri");
        URI base = configured == null ? source.entryUri() : URI.create(configured.toString());
        requireOfficialHttps(source, base);
        String param = URLEncoder.encode(
            "{\"pageNo\":" + page + ",\"pageSize\":" + pageSize + "}", StandardCharsets.UTF_8);
        return URI.create(base + (base.getRawQuery() == null ? "?" : "&") + "paramJson=" + param);
    }

    private static int listingTotal(byte[] content) {
        String normalized = new String(content, StandardCharsets.UTF_8).replace("\\\"", "\"");
        Matcher matcher = LISTING_TOTAL.matcher(normalized);
        if (!matcher.find()) throw new FetchFailedException("Historical listing does not report a total count");
        return Integer.parseInt(matcher.group(1));
    }

    private static URI staticPageUri(URI entryUri, int page) {
        if (page == 1) return entryUri;
        String value = entryUri.toString();
        if (!value.endsWith("list.htm")) {
            throw new IllegalArgumentException("STATIC_PAGE_SUFFIX entryUri must end with list.htm");
        }
        return URI.create(value.substring(0, value.length() - "list.htm".length()) + "list" + page + ".htm");
    }

    private static Optional<URI> nextPageUri(
        RecruitmentSource source, URI pageUri, byte[] content, String selector
    ) {
        LinkedHashMap<URI, URI> distinct = new LinkedHashMap<>();
        var document = Jsoup.parse(new String(content, StandardCharsets.UTF_8), pageUri.toString());
        for (var element : document.select(selector)) {
            String href = element.attr("href").trim();
            if (href.isEmpty()) continue;
            URI resolved = canonical(pageUri.resolve(href));
            try {
                requireOfficialHttps(source, resolved);
            } catch (IllegalArgumentException unsafeLink) {
                throw new FetchFailedException(
                    "Historical listing next-page link is not HTTPS on an allowed official host", unsafeLink);
            }
            distinct.putIfAbsent(resolved, resolved);
        }
        if (distinct.size() > 1) {
            throw new FetchFailedException("Historical listing exposed ambiguous next-page links");
        }
        return distinct.values().stream().findFirst();
    }

    private static List<DiscoveredLink> canonicalDistinct(List<DiscoveredLink> values) {
        LinkedHashMap<URI, DiscoveredLink> distinct = new LinkedHashMap<>();
        for (DiscoveredLink value : values) {
            URI canonical = canonical(value.uri());
            distinct.putIfAbsent(canonical, new DiscoveredLink(canonical, value.title().trim()));
        }
        return List.copyOf(distinct.values());
    }

    private static URI canonical(URI value) {
        try {
            return new URI(value.getScheme(), value.getAuthority(), value.getPath(), value.getQuery(), null).normalize();
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid discovered URI: " + value, failure);
        }
    }

    private static Optional<Integer> recruitmentYear(DiscoveredLink link) {
        Optional<LocalDate> date = publishedDate(link);
        if (date.isPresent()) return Optional.of(date.orElseThrow().getYear());
        Matcher uri = YEAR.matcher(link.uri().getPath());
        if (uri.find()) return Optional.of(Integer.parseInt(uri.group(1)));
        Matcher title = YEAR.matcher(link.title());
        return title.find() ? Optional.of(Integer.parseInt(title.group(1))) : Optional.empty();
    }

    private static Optional<LocalDate> publishedDate(DiscoveredLink link) {
        Matcher matcher = URL_DATE.matcher(link.uri().getPath());
        if (!matcher.find()) return Optional.empty();
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static int positive(Map<String, Object> configuration, String key) {
        Object value = configuration.get(key);
        int parsed = value instanceof Number number ? number.intValue()
            : value == null ? -1 : Integer.parseInt(value.toString());
        if (parsed < 1) throw new IllegalArgumentException(key + " must be positive");
        return parsed;
    }

    private static String required(Map<String, Object> configuration, String key) {
        Object value = configuration.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return text;
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
