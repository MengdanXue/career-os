package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.DocumentFetcher;
import com.careeros.application.AcquisitionHttpPorts.FetchFailedException;
import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.FetchedDocument;
import com.careeros.application.AcquisitionHttpPorts.ListingEvidence;
import com.careeros.application.AcquisitionHttpPorts.ListingEntryEvidence;
import com.careeros.application.AcquisitionHttpPorts.ListingQuery;
import com.careeros.application.AcquisitionHttpPorts.ListingResult;
import com.careeros.application.AcquisitionHttpPorts.SourceDiscoverer;
import com.careeros.application.AcquisitionHttpPorts.SourceListingReader;
import com.careeros.application.AcquisitionHttpPorts.YearDiscoveredLink;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.RecruitmentLifecycle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper JSON = new ObjectMapper();
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
        if (source.configuration().containsKey("listingEntries")) {
            return readEntries(source, query, ListingEntryContract.from(source));
        }
        String mode = String.valueOf(source.configuration().get("historicalPaginationMode"));
        if (!query.historical()) {
            if (!"JCMS_PARAM_JSON".equals(mode)) {
                throw new IllegalArgumentException(
                    "Bounded incremental traversal currently requires JCMS_PARAM_JSON");
            }
            return readIncrementalJcms(source);
        }
        return switch (mode) {
            case "STATIC_PAGE_SUFFIX" -> readStaticSuffix(source, query);
            case "LINKED_PAGE" -> readLinkedPages(source, query);
            case "JCMS_PARAM_JSON" -> readJcms(source, query);
            case "FIXED_HTTPS_EVIDENCE" -> readFixedEvidence(source, query);
            default -> throw new IllegalArgumentException("Unsupported historicalPaginationMode: " + mode);
        };
    }

    private ListingResult readEntries(
        RecruitmentSource source, ListingQuery query, List<ListingEntryContract> entries
    ) {
        List<ListingEntryContract> ordered = entries.stream()
            .filter(entry -> applies(entry, query))
            .sorted(java.util.Comparator
                .comparingInt((ListingEntryContract entry) ->
                    entry.role() == ListingEntryContract.Role.LIFECYCLE ? 0 : 1))
            .toList();
        LinkedHashMap<URI, YearDiscoveredLink> links = new LinkedHashMap<>();
        Map<String, ListingEntryEvidence> byEntry = new LinkedHashMap<>();
        for (ListingEntryContract entry : ordered) {
            ListingResult result = readEntry(source, entry, scopedQuery(entry, query));
            result.links().forEach(link -> links.putIfAbsent(canonical(link.link().uri()),
                new YearDiscoveredLink(
                    new DiscoveredLink(canonical(link.link().uri()), link.link().title(),
                        entry.readContract(), link.link().publishedOn()),
                    link.recruitmentYear())));
            byEntry.put(entry.code(), new ListingEntryEvidence(
                entry.code(), entry.completenessRequired(), result.evidenceByYear()));
        }
        List<YearDiscoveredLink> orderedLinks = links.values().stream()
            .sorted(java.util.Comparator
                .comparing((YearDiscoveredLink link) ->
                    RecruitmentLifecycle.classify(link.link().title()).isEmpty())
                .thenComparing(link -> link.link().uri().toString()))
            .toList();
        return new ListingResult(orderedLinks, aggregateEvidence(query, entries, byEntry), byEntry);
    }

    private ListingResult readEntry(
        RecruitmentSource source, ListingEntryContract entry, ListingQuery query
    ) {
        return switch (entry.mode()) {
            case JCMS_PARAM_JSON -> readJcmsEntry(source, entry, query);
            case STATIC_SUFFIX_TEMPLATE -> readNumberedHtmlEntry(source, entry, query, page ->
                templatePageUri(entry, page));
            case LINKED_PAGE -> readLinkedEntry(source, entry, query);
            case QUERY_PAGE -> readNumberedHtmlEntry(source, entry, query, page ->
                queryPageUri(entry.entryUri(), required(entry.configuration(), "pageParameter"), page));
            case JSON_API -> readJsonEntry(source, entry, query);
            case EMBEDDED_DATA -> readEmbeddedEntry(source, entry, query);
            case CAMPAIGN_STATE -> readCampaignEntry(source, entry, query);
            case FIXED_EVIDENCE -> readFixedEntry(source, entry, query);
        };
    }

    private ListingResult readNumberedHtmlEntry(
        RecruitmentSource source,
        ListingEntryContract entry,
        ListingQuery query,
        java.util.function.IntFunction<URI> pageUri
    ) {
        int maxPages = pageLimit(entry.configuration(), query);
        int confirmations = nonnegative(entry.configuration(), "emptyPageConfirmationPages", 0);
        LinkedHashMap<URI, YearDiscoveredLink> accepted = new LinkedHashMap<>();
        LinkedHashMap<URI, DiscoveredLink> rawDistinct = new LinkedHashMap<>();
        Set<String> nonemptyFingerprints = new HashSet<>();
        int rawCount = 0;
        int emptyRun = 0;
        Integer reportedTotal = null;
        Integer reportedTotalPages = null;
        for (int page = 1; page <= maxPages; page++) {
            FetchedDocument fetched = fetch(source, entry, pageUri.apply(page));
            reportedTotal = consistentReported(entry, fetched.content(), "reportedTotalRegex",
                reportedTotal, "total");
            reportedTotalPages = consistentReported(entry, fetched.content(), "reportedTotalPagesRegex",
                reportedTotalPages, "total pages");
            Integer currentPage = reported(entry, fetched.content(), "reportedCurrentPageRegex");
            if (currentPage != null && currentPage != page) {
                throw new FetchFailedException("Listing entry " + entry.code() + " reported an unexpected current page");
            }
            List<DiscoveredLink> raw = canonicalDistinct(
                discoverAll(source, entry, fetched.finalUri(), fetched.content()));
            if (raw.isEmpty()) {
                emptyRun++;
                if (emptyRun > confirmations) {
                    return completed(query, List.copyOf(accepted.values()), page, rawCount,
                        "EMPTY_PAGE", "entry=" + entry.code() + "; terminal empty pages=" + emptyRun);
                }
                continue;
            }
            if (emptyRun > 0) {
                throw new FetchFailedException("Listing entry " + entry.code() + " exposed an empty middle page");
            }
            if (!nonemptyFingerprints.add(sha256(fetched.content()))) {
                throw new FetchFailedException("Listing entry " + entry.code() + " repeated a non-terminal page");
            }
            raw.forEach(link -> rawDistinct.putIfAbsent(link.uri(), link));
            rawCount = rawDistinct.size();
            addApplicableLinks(query, accepted, canonicalDistinct(
                discover(source, entry, fetched.finalUri(), fetched.content())));
            boolean terminal = reportedTotal != null && rawCount >= reportedTotal
                || reportedTotalPages != null && page >= reportedTotalPages;
            if (terminal) {
                if (reportedTotal != null && rawCount != reportedTotal) {
                    throw new FetchFailedException("Listing entry " + entry.code()
                        + " unique entry count did not match reported total");
                }
                String reason = reportedTotalPages != null && page >= reportedTotalPages
                    ? "REPORTED_LAST_PAGE_REACHED" : "REPORTED_TOTAL_REACHED";
                return completed(query, List.copyOf(accepted.values()), page, rawCount,
                    reason, "entry=" + entry.code() + "; official dynamic listing counters reconciled");
            }
        }
        if (!query.historical()) return new ListingResult(List.copyOf(accepted.values()), Map.of());
        throw new FetchFailedException("Listing entry " + entry.code() + " exceeded configured page limit");
    }

    private ListingResult readJcmsEntry(
        RecruitmentSource source, ListingEntryContract entry, ListingQuery query
    ) {
        int pageSize = positive(entry.configuration(), "historicalPageSize");
        int maxPages = pageLimit(entry.configuration(), query);
        LinkedHashMap<URI, DiscoveredLink> rawDistinct = new LinkedHashMap<>();
        LinkedHashMap<URI, YearDiscoveredLink> accepted = new LinkedHashMap<>();
        Set<String> pageFingerprints = new HashSet<>();
        Integer total = null;
        for (int page = 1; page <= maxPages; page++) {
            FetchedDocument fetched = fetch(source, entry, jcmsPageUri(entry, page, pageSize));
            int reported = listingTotal(fetched.content());
            if (total == null) total = reported;
            else if (!total.equals(reported)) {
                throw new FetchFailedException("Listing entry " + entry.code() + " total changed during traversal");
            }
            if (!pageFingerprints.add(sha256(fetched.content()))
                && (long) (page - 1) * pageSize < total) {
                throw new FetchFailedException("Listing entry " + entry.code() + " repeated a non-terminal page");
            }
            int before = rawDistinct.size();
            List<DiscoveredLink> raw = canonicalDistinct(
                discoverAll(source, entry, fetched.finalUri(), fetched.content()));
            raw.forEach(link -> rawDistinct.putIfAbsent(link.uri(), link));
            addApplicableLinks(query, accepted, canonicalDistinct(
                discover(source, entry, fetched.finalUri(), fetched.content())));
            if (page > 1 && rawDistinct.size() == before && before < total) {
                throw new FetchFailedException("Listing entry " + entry.code() + " page did not add any new entry");
            }
            if ((long) page * pageSize >= total) {
                if (rawDistinct.size() != total) {
                    throw new FetchFailedException("Listing entry " + entry.code()
                        + " unique entry count did not match reported total");
                }
                return completed(query, List.copyOf(accepted.values()), page, total,
                    "REPORTED_TOTAL_REACHED", "entry=" + entry.code() + "; official listing total=" + total);
            }
        }
        if (!query.historical()) return new ListingResult(List.copyOf(accepted.values()), Map.of());
        throw new FetchFailedException("Listing entry " + entry.code() + " exceeded configured page limit");
    }

    private ListingResult readJsonEntry(
        RecruitmentSource source, ListingEntryContract entry, ListingQuery query
    ) {
        int pageSize = positive(entry.configuration(), "historicalPageSize");
        int maxPages = pageLimit(entry.configuration(), query);
        String pageParameter = required(entry.configuration(), "pageParameter");
        String pageSizeParameter = required(entry.configuration(), "pageSizeParameter");
        LinkedHashMap<URI, DiscoveredLink> rawDistinct = new LinkedHashMap<>();
        LinkedHashMap<URI, YearDiscoveredLink> accepted = new LinkedHashMap<>();
        Set<String> fingerprints = new HashSet<>();
        Integer total = null;
        for (int page = 1; page <= maxPages; page++) {
            URI pageUri = queryPageUri(
                queryPageUri(entry.entryUri(), pageParameter, page), pageSizeParameter, pageSize);
            FetchedDocument fetched = fetch(source, entry, pageUri);
            JsonNode root = json(fetched.content(), "JSON_API entry " + entry.code());
            int reported = jsonPath(root, required(entry.configuration(), "jsonTotalPath")).asInt(-1);
            if (reported < 0) throw new FetchFailedException("JSON_API entry does not declare a valid total");
            if (total == null) total = reported;
            else if (!total.equals(reported)) {
                throw new FetchFailedException("JSON_API reported total changed during traversal");
            }
            if (!fingerprints.add(sha256(fetched.content())) && rawDistinct.size() < total) {
                throw new FetchFailedException("JSON_API repeated a non-terminal page");
            }
            List<DiscoveredLink> raw = jsonLinks(entry, fetched.finalUri(), root,
                required(entry.configuration(), "jsonItemsPath"));
            int before = rawDistinct.size();
            raw.forEach(link -> rawDistinct.putIfAbsent(link.uri(), link));
            addApplicableLinks(query, accepted, filterConfigured(entry, raw));
            if (raw.isEmpty() && rawDistinct.size() < total) {
                throw new FetchFailedException("JSON_API exposed an empty middle page");
            }
            if (page > 1 && before == rawDistinct.size() && rawDistinct.size() < total) {
                throw new FetchFailedException("JSON_API page did not add any new entry");
            }
            if ((long) page * pageSize >= total) {
                if (rawDistinct.size() != total) {
                    throw new FetchFailedException("JSON_API unique entry count did not match reported total");
                }
                return completed(query, List.copyOf(accepted.values()), page, total,
                    "REPORTED_TOTAL_REACHED", "entry=" + entry.code() + "; official JSON total=" + total);
            }
        }
        if (!query.historical()) return new ListingResult(List.copyOf(accepted.values()), Map.of());
        throw new FetchFailedException("JSON_API exceeded configured page limit");
    }

    private ListingResult readEmbeddedEntry(
        RecruitmentSource source, ListingEntryContract entry, ListingQuery query
    ) {
        FetchedDocument fetched = fetch(source, entry, entry.entryUri());
        var document = Jsoup.parse(new String(fetched.content(), StandardCharsets.UTF_8),
            fetched.finalUri().toString());
        String selector = required(entry.configuration(), "embeddedDataSelector");
        var elements = document.select(selector);
        if (elements.size() != 1) {
            throw new FetchFailedException("EMBEDDED_DATA selector must match exactly one element");
        }
        String payload = elements.getFirst().data();
        if (payload.isBlank()) payload = elements.getFirst().html();
        JsonNode root = json(payload.getBytes(StandardCharsets.UTF_8),
            "EMBEDDED_DATA entry " + entry.code());
        List<DiscoveredLink> raw = jsonLinks(entry, fetched.finalUri(), root,
            required(entry.configuration(), "embeddedItemsPath"));
        LinkedHashMap<URI, YearDiscoveredLink> accepted = new LinkedHashMap<>();
        addApplicableLinks(query, accepted, filterConfigured(entry, raw));
        return completed(query, List.copyOf(accepted.values()), 1, raw.size(),
            "EMBEDDED_DATA_PARSED", "entry=" + entry.code() + "; configured embedded dataset");
    }

    private ListingResult readLinkedEntry(
        RecruitmentSource source, ListingEntryContract entry, ListingQuery query
    ) {
        int maxPages = positive(entry.configuration(), query.historical()
            ? "historicalMaxPages" : "incrementalListingMaxPages");
        String nextPageSelector = required(entry.configuration(), "nextPageSelector");
        LinkedHashMap<URI, YearDiscoveredLink> accepted = new LinkedHashMap<>();
        Set<URI> visitedPages = new HashSet<>();
        Set<String> pageFingerprints = new HashSet<>();
        int rawCount = 0;
        URI current = entry.entryUri();
        for (int page = 1; page <= maxPages; page++) {
            current = canonical(current);
            if (!visitedPages.add(current)) {
                throw new FetchFailedException("Listing entry " + entry.code() + " next-page link formed a cycle");
            }
            FetchedDocument fetched = fetch(source, entry, current);
            if (!pageFingerprints.add(sha256(fetched.content()))) {
                throw new FetchFailedException("Listing entry " + entry.code() + " repeated a non-terminal page");
            }
            List<DiscoveredLink> raw = canonicalDistinct(
                discoverAll(source, entry, fetched.finalUri(), fetched.content()));
            List<DiscoveredLink> candidates = canonicalDistinct(
                discover(source, entry, fetched.finalUri(), fetched.content()));
            rawCount += raw.size();
            addApplicableLinks(query, accepted, candidates);
            Optional<URI> next = nextPageUri(entry, fetched.finalUri(), fetched.content(), nextPageSelector);
            if (next.isEmpty()) {
                return completed(query, List.copyOf(accepted.values()), page, rawCount,
                    "NO_NEXT_LINK", "entry=" + entry.code() + "; official next-link traversal pages=" + page);
            }
            if (page == maxPages) {
                if (!query.historical()) {
                    return new ListingResult(List.copyOf(accepted.values()), Map.of());
                }
                throw new FetchFailedException("Listing entry " + entry.code() + " exceeded configured page limit");
            }
            current = next.orElseThrow();
        }
        throw new FetchFailedException("Listing entry " + entry.code() + " exceeded configured page limit");
    }

    private ListingResult readCampaignEntry(
        RecruitmentSource source, ListingEntryContract entry, ListingQuery query
    ) {
        FetchedDocument fetched = fetch(source, entry, entry.entryUri());
        List<DiscoveredLink> candidates = canonicalDistinct(
            discover(source, entry, fetched.finalUri(), fetched.content()));
        LinkedHashMap<URI, YearDiscoveredLink> accepted = new LinkedHashMap<>();
        addApplicableLinks(query, accepted, candidates);
        Map<Integer, ListingEvidence> evidence = new LinkedHashMap<>();
        for (int year : query.recruitmentYears()) {
            long annual = accepted.values().stream().filter(link -> link.recruitmentYear() == year).count();
            evidence.put(year, new ListingEvidence(1, candidates.size(), Math.toIntExact(annual),
                Math.max(0, candidates.size() - Math.toIntExact(annual)), 0,
                null, null, false, "CAMPAIGN_STATE_ONLY", "current campaign state cannot prove history"));
        }
        return new ListingResult(List.copyOf(accepted.values()), evidence);
    }

    private ListingResult readFixedEntry(
        RecruitmentSource source, ListingEntryContract entry, ListingQuery query
    ) {
        Object configured = entry.configuration().get("historicalEvidenceByYear");
        if (!(configured instanceof Map<?, ?> evidence)) {
            throw new IllegalArgumentException("historicalEvidenceByYear is required for FIXED_EVIDENCE");
        }
        List<YearDiscoveredLink> links = new ArrayList<>();
        Map<Integer, ListingEvidence> byYear = new LinkedHashMap<>();
        for (int year : query.recruitmentYears()) {
            Object values = evidence.get(String.valueOf(year));
            if (values == null) values = evidence.get(year);
            List<?> uris = values instanceof List<?> list ? list : List.of();
            for (Object value : uris) {
                URI uri = URI.create(String.valueOf(value));
                requireOfficial(entry, uri);
                links.add(new YearDiscoveredLink(new DiscoveredLink(uri, year + "年官方招聘公告"), year));
            }
            byYear.put(year, new ListingEvidence(0, uris.size(), uris.size(), 0, 0,
                null, null, false, "FIXED_EVIDENCE_SET", "explicit official evidence; no archive traversal"));
        }
        return new ListingResult(links, byYear);
    }

    private static boolean applies(ListingEntryContract entry, ListingQuery query) {
        return query.recruitmentYears().isEmpty() || entry.recruitmentYears().isEmpty()
            || entry.recruitmentYears().stream().anyMatch(query.recruitmentYears()::contains);
    }

    private static ListingQuery scopedQuery(
        ListingEntryContract entry, ListingQuery query
    ) {
        if (!query.historical() || entry.recruitmentYears().isEmpty()) return query;
        Set<Integer> intersection = query.recruitmentYears().stream()
            .filter(entry.recruitmentYears()::contains)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return new ListingQuery(intersection, true);
    }

    private static void addApplicableLinks(
        ListingQuery query, LinkedHashMap<URI, YearDiscoveredLink> accepted,
        List<DiscoveredLink> candidates
    ) {
        for (DiscoveredLink link : candidates) {
            recruitmentYear(link)
                .filter(year -> query.recruitmentYears().isEmpty()
                    || query.recruitmentYears().contains(year))
                .ifPresent(year -> accepted.putIfAbsent(link.uri(), new YearDiscoveredLink(link, year)));
        }
    }

    private static Map<Integer, ListingEvidence> aggregateEvidence(
        ListingQuery query,
        List<ListingEntryContract> entries,
        Map<String, ListingEntryEvidence> byEntry
    ) {
        Map<Integer, ListingEvidence> aggregated = new LinkedHashMap<>();
        for (int year : query.recruitmentYears()) {
            List<ListingEntryEvidence> applicable = byEntry.values().stream()
                .filter(entry -> entry.evidenceByYear().containsKey(year))
                .toList();
            int pages = applicable.stream().mapToInt(entry -> entry.evidenceByYear().get(year).pageCount()).sum();
            int raw = applicable.stream().mapToInt(entry -> entry.evidenceByYear().get(year).rawCount()).sum();
            int accepted = applicable.stream().mapToInt(entry -> entry.evidenceByYear().get(year).acceptedCount()).sum();
            int filtered = applicable.stream().mapToInt(entry -> entry.evidenceByYear().get(year).filteredCount()).sum();
            int failed = applicable.stream().mapToInt(entry -> entry.evidenceByYear().get(year).failedCount()).sum();
            List<ListingEntryContract> required = entries.stream()
                .filter(ListingEntryContract::completenessRequired)
                .filter(entry -> entry.recruitmentYears().isEmpty()
                    || entry.recruitmentYears().contains(year))
                .toList();
            boolean complete = !required.isEmpty() && required.stream().allMatch(entry -> {
                ListingEntryEvidence evidence = byEntry.get(entry.code());
                return evidence != null && evidence.evidenceByYear().containsKey(year)
                    && evidence.evidenceByYear().get(year).traversalComplete();
            });
            LocalDate earliest = applicable.stream().map(entry -> entry.evidenceByYear().get(year).earliestPublishedOn())
                .filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
            LocalDate latest = applicable.stream().map(entry -> entry.evidenceByYear().get(year).latestPublishedOn())
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
            String stopReason = required.isEmpty()
                ? "NO_APPLICABLE_REQUIRED_ENTRY"
                : complete ? "ALL_REQUIRED_ENTRIES_COMPLETE" : "REQUIRED_ENTRY_INCOMPLETE";
            String basis = required.isEmpty()
                ? "no completeness-required listing entry applies to the requested year"
                : complete ? "all applicable completeness-required entries completed"
                    : "at least one applicable completeness-required entry is incomplete";
            aggregated.put(year, new ListingEvidence(pages, raw, accepted, filtered, failed,
                earliest, latest, complete, stopReason, basis));
        }
        return Map.copyOf(aggregated);
    }

    private ListingResult readIncrementalJcms(RecruitmentSource source) {
        int pageSize = positive(source.configuration(), "historicalPageSize");
        int maxPages = positive(source.configuration(), "incrementalListingMaxPages");
        LinkedHashMap<URI, YearDiscoveredLink> accepted = new LinkedHashMap<>();
        Set<String> pageFingerprints = new HashSet<>();
        Integer total = null;
        for (int page = 1; page <= maxPages; page++) {
            FetchedDocument fetched = fetch(source, jcmsPageUri(source, page, pageSize));
            int reported = listingTotal(fetched.content());
            if (total == null) total = reported;
            else if (!total.equals(reported)) {
                throw new FetchFailedException("Incremental listing total changed during traversal");
            }
            if (!pageFingerprints.add(sha256(fetched.content())) && (long) (page - 1) * pageSize < total) {
                throw new FetchFailedException("Incremental listing repeated a non-terminal page");
            }
            for (DiscoveredLink link : canonicalDistinct(
                discoverer.discover(source, fetched.finalUri(), fetched.content()))) {
                recruitmentYear(link)
                    .ifPresent(year -> accepted.putIfAbsent(link.uri(), new YearDiscoveredLink(link, year)));
            }
            if ((long) page * pageSize >= total) break;
        }
        return new ListingResult(List.copyOf(accepted.values()), Map.of());
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

    private FetchedDocument fetch(
        RecruitmentSource source, ListingEntryContract entry, URI uri
    ) {
        requireOfficial(entry, uri);
        FetchedDocument fetched = fetcher.fetch(new FetchRequest(
            uri, entry.readContract().exactHosts(), null, null,
            Duration.ofSeconds(20), MAX_LISTING_BYTES, source.id(),
            source.minimumRequestInterval(),
            com.careeros.application.AcquisitionHttpPorts.FetchMethod.GET,
            entry.readContract()));
        if (fetched.status() != 200 || fetched.content().length == 0) {
            throw new FetchFailedException("Listing entry did not return content: " + fetched.status());
        }
        return fetched;
    }

    private List<DiscoveredLink> discover(
        RecruitmentSource source, ListingEntryContract entry, URI pageUri, byte[] content
    ) {
        if (discoverer instanceof RoutingSourceDiscoverer routing) {
            return routing.discover(source, entry, pageUri, content);
        }
        if (discoverer instanceof StaticHtmlSourceDiscoverer staticHtml) {
            return staticHtml.discover(source, entry, pageUri, content);
        }
        return discoverer.discover(source, pageUri, content);
    }

    private List<DiscoveredLink> discoverAll(
        RecruitmentSource source, ListingEntryContract entry, URI pageUri, byte[] content
    ) {
        if (discoverer instanceof RoutingSourceDiscoverer routing) {
            return routing.discoverAll(source, entry, pageUri, content);
        }
        if (discoverer instanceof StaticHtmlSourceDiscoverer staticHtml) {
            return staticHtml.discoverAll(source, entry, pageUri, content);
        }
        return discoverer.discoverAll(source, pageUri, content);
    }

    private static void requireOfficial(ListingEntryContract entry, URI uri) {
        if (!entry.readContract().authorizesTarget(uri)) {
            throw new IllegalArgumentException("Listing URI must use its configured official read contract");
        }
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

    private static URI jcmsPageUri(ListingEntryContract entry, int page, int pageSize) {
        Object configured = entry.configuration().get("listingApiUri");
        URI base = configured == null ? entry.entryUri() : URI.create(configured.toString());
        requireOfficial(entry, base);
        String param = URLEncoder.encode(
            "{\"pageNo\":" + page + ",\"pageSize\":" + pageSize + "}", StandardCharsets.UTF_8);
        return URI.create(base + (base.getRawQuery() == null ? "?" : "&") + "paramJson=" + param);
    }

    private static URI templatePageUri(ListingEntryContract entry, int page) {
        String template = required(entry.configuration(), "pageUriTemplate");
        if (!template.contains("{page}")) {
            throw new IllegalArgumentException("pageUriTemplate must contain {page}");
        }
        URI result = URI.create(template.replace("{page}", Integer.toString(page)));
        requireOfficial(entry, result);
        return result;
    }

    private static URI queryPageUri(URI base, String parameter, int value) {
        if (!parameter.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("page query parameter is invalid");
        }
        String raw = base.getRawQuery();
        List<String> parts = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split("&")) {
                if (!part.isBlank() && !part.split("=", 2)[0].equals(parameter)) parts.add(part);
            }
        }
        parts.add(parameter + "=" + value);
        String path = base.getRawPath() == null || base.getRawPath().isEmpty() ? "/" : base.getRawPath();
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + path
            + "?" + String.join("&", parts));
    }

    private static JsonNode json(byte[] content, String description) {
        try {
            return JSON.readTree(content);
        } catch (Exception invalid) {
            throw new FetchFailedException(description + " returned malformed JSON", invalid);
        }
    }

    private static JsonNode jsonPath(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) throw new IllegalArgumentException("JSON path contains a blank segment");
            current = current.path(segment);
        }
        return current;
    }

    private static List<DiscoveredLink> jsonLinks(
        ListingEntryContract entry, URI pageUri, JsonNode root, String itemsPath
    ) {
        JsonNode items = jsonPath(root, itemsPath);
        if (!items.isArray()) throw new FetchFailedException("configured JSON items path is not an array");
        String urlField = required(entry.configuration(), "jsonUrlField");
        String titleField = required(entry.configuration(), "jsonTitleField");
        LinkedHashMap<URI, DiscoveredLink> distinct = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String rawUrl = jsonPath(item, urlField).asText().trim();
            String title = jsonPath(item, titleField).asText().trim();
            if (rawUrl.isEmpty() || title.isEmpty()) {
                throw new FetchFailedException("configured JSON item is missing its URL or title");
            }
            URI uri = canonical(pageUri.resolve(rawUrl));
            requireOfficial(entry, uri);
            distinct.putIfAbsent(uri, new DiscoveredLink(uri, title));
        }
        return List.copyOf(distinct.values());
    }

    private static List<DiscoveredLink> filterConfigured(
        ListingEntryContract entry, List<DiscoveredLink> links
    ) {
        Pattern article = Pattern.compile(required(entry.configuration(), "articleUrlRegex"));
        Pattern include = Pattern.compile(required(entry.configuration(), "titleIncludeRegex"));
        Pattern exclude = Pattern.compile(required(entry.configuration(), "titleExcludeRegex"));
        return links.stream()
            .filter(link -> article.matcher(link.uri().toString()).matches())
            .filter(link -> include.matcher(link.title()).find())
            .filter(link -> !exclude.matcher(link.title()).find())
            .toList();
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

    private static Optional<URI> nextPageUri(
        ListingEntryContract entry, URI pageUri, byte[] content, String selector
    ) {
        LinkedHashMap<URI, URI> distinct = new LinkedHashMap<>();
        var document = Jsoup.parse(new String(content, StandardCharsets.UTF_8), pageUri.toString());
        for (var element : document.select(selector)) {
            String href = element.attr("href").trim();
            if (href.isEmpty()) continue;
            URI resolved = canonical(pageUri.resolve(href));
            try {
                requireOfficial(entry, resolved);
            } catch (IllegalArgumentException unsafeLink) {
                throw new FetchFailedException(
                    "Listing entry next-page link is outside its official read contract", unsafeLink);
            }
            distinct.putIfAbsent(resolved, resolved);
        }
        if (distinct.size() > 1) {
            throw new FetchFailedException("Listing entry exposed ambiguous next-page links");
        }
        return distinct.values().stream().findFirst();
    }

    private static List<DiscoveredLink> canonicalDistinct(List<DiscoveredLink> values) {
        LinkedHashMap<URI, DiscoveredLink> distinct = new LinkedHashMap<>();
        for (DiscoveredLink value : values) {
            URI canonical = canonical(value.uri());
            distinct.putIfAbsent(canonical, new DiscoveredLink(canonical, value.title().trim(),
                value.readContract(), value.publishedOn()));
        }
        return List.copyOf(distinct.values());
    }

    private static URI canonical(URI value) {
        return CanonicalUri.normalize(value);
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
        if (link.publishedOn() != null) return Optional.of(link.publishedOn());
        Matcher matcher = URL_DATE.matcher(link.uri().getPath());
        if (!matcher.find()) return Optional.empty();
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Integer consistentReported(
        ListingEntryContract entry, byte[] content, String key, Integer previous, String description
    ) {
        Integer value = reported(entry, content, key);
        if (value == null) return previous;
        if (previous != null && !previous.equals(value)) {
            throw new FetchFailedException("Listing entry " + entry.code() + " reported "
                + description + " changed during traversal");
        }
        return value;
    }

    private static Integer reported(ListingEntryContract entry, byte[] content, String key) {
        Object configured = entry.configuration().get(key);
        if (!(configured instanceof String regex) || regex.isBlank()) return null;
        Matcher matcher = Pattern.compile(regex).matcher(new String(content, StandardCharsets.UTF_8));
        if (!matcher.find() || matcher.groupCount() < 1) {
            throw new FetchFailedException("Listing entry " + entry.code() + " did not expose " + key);
        }
        try {
            int value = Integer.parseInt(matcher.group(1));
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new FetchFailedException("Listing entry " + entry.code() + " exposed invalid " + key, invalid);
        }
    }

    private static int positive(Map<String, Object> configuration, String key) {
        Object value = configuration.get(key);
        int parsed = value instanceof Number number ? number.intValue()
            : value == null ? -1 : Integer.parseInt(value.toString());
        if (parsed < 1) throw new IllegalArgumentException(key + " must be positive");
        return parsed;
    }

    private static int pageLimit(Map<String, Object> configuration, ListingQuery query) {
        if (!query.historical() && configuration.containsKey("incrementalListingMaxPages")) {
            return positive(configuration, "incrementalListingMaxPages");
        }
        return positive(configuration, "historicalMaxPages");
    }

    private static int nonnegative(Map<String, Object> configuration, String key, int defaultValue) {
        Object value = configuration.get(key);
        if (value == null) return defaultValue;
        int parsed;
        try {
            parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be nonnegative", invalid);
        }
        if (parsed < 0) throw new IllegalArgumentException(key + " must be nonnegative");
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
