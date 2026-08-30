package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.AcquisitionHttpPorts.DocumentFetcher;
import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.FetchedDocument;
import com.careeros.application.AcquisitionHttpPorts.ListingQuery;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigurableSourceListingReaderTest {
    @Test
    void auditedLinkedEntryRejectsANextPageOnAnUncontractedPort() {
        Map<String, Object> entry = auditedEntry("LINKED_PAGE");
        entry.put("historicalMaxPages", 2);
        entry.put("nextPageSelector", "a.next[href]");
        URI first = URI.create("http://legacy.example:8080/public/index.html");
        URI wrongPort = URI.create("http://legacy.example:8081/public/index_2.html");
        var fetcher = new MapFetcher(Map.of(
            first, linkedPage(
                "http://legacy.example:8080/public/art_1.html", "2026年招聘公告",
                wrongPort.toString()),
            wrongPort, page(
                "http://legacy.example:8080/public/art_2.html", "2026年招聘公告（二）")
        ));

        assertThatThrownBy(() -> new ConfigurableSourceListingReader(
            fetcher, new StaticHtmlSourceDiscoverer()).read(
                singleEntrySource(entry), new ListingQuery(Set.of(2026), true)))
            .isInstanceOf(com.careeros.application.AcquisitionHttpPorts.FetchFailedException.class)
            .hasMessageContaining("read contract");
        assertThat(fetcher.requests()).containsExactly(first);
    }

    @Test
    void auditedFixedEvidenceRejectsADetailOnAnUncontractedPort() {
        Map<String, Object> entry = auditedEntry("FIXED_EVIDENCE");
        entry.put("historicalEvidenceByYear", Map.of("2026", List.of(
            "http://legacy.example:8081/public/art_1.html")));

        assertThatThrownBy(() -> new ConfigurableSourceListingReader(
            new MapFetcher(Map.of()), new StaticHtmlSourceDiscoverer()).read(
                singleEntrySource(entry), new ListingQuery(Set.of(2026), true)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("read contract");
    }

    @Test
    void auditedListingEntryAnnotatesItsDiscoveredDetailWithTheSameReadContract() {
        URI listing = URI.create("http://legacy.example/public/jobs/index.html");
        Map<String, Object> entry = baseEntry("legacy", "CAMPAIGN_STATE", listing.toString());
        entry.put("transportPolicy", "AUDITED_HTTP_READ_ONLY");
        entry.put("allowedHosts", List.of("legacy.example"));
        entry.put("allowedPathPrefixes", List.of("/public/jobs"));
        entry.put("articleUrlRegex", "^http://legacy\\.example/public/jobs/art_[0-9]+\\.html$");
        var fetcher = new MapFetcher(Map.of(
            listing, page("/public/jobs/art_1.html", "2026年招聘公告")));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(), false));

        assertThat(result.links()).singleElement().satisfies(link -> {
            assertThat(link.link().uri())
                .isEqualTo(URI.create("http://legacy.example/public/jobs/art_1.html"));
            assertThat(link.link().readContract()).isNotNull();
            assertThat(link.link().readContract().transportPolicy().name())
                .isEqualTo("AUDITED_HTTP_READ_ONLY");
            assertThat(link.link().readContract().allowedPathPrefixes())
                .containsExactly("/public/jobs");
        });
    }

    @Test
    void entryEvidenceIsScopedToTheIntersectionOfRequestedAndApplicableYears() {
        Map<String, Object> entry2024 = baseEntry(
            "archive-2024", "LINKED_PAGE", "https://official.example/2024/index.html");
        entry2024.put("recruitmentYears", List.of(2024));
        entry2024.put("historicalMaxPages", 2);
        entry2024.put("nextPageSelector", "a.next[href]");
        Map<String, Object> entry2026 = baseEntry(
            "current-2026", "LINKED_PAGE", "https://official.example/2026/index.html");
        entry2026.put("recruitmentYears", List.of(2026));
        entry2026.put("historicalMaxPages", 2);
        entry2026.put("nextPageSelector", "a.next[href]");
        var fetcher = new MapFetcher(Map.of(
            URI.create("https://official.example/2024/index.html"),
                page("/art/2024/8/20/art_1.html", "2024年招聘公告"),
            URI.create("https://official.example/2026/index.html"),
                page("/art/2026/8/20/art_2.html", "2026年招聘公告")
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(sourceWithEntries(List.of(entry2024, entry2026)),
                new ListingQuery(Set.of(2024, 2026), true));

        assertThat(result.evidenceByEntry().get("archive-2024").evidenceByYear())
            .containsOnlyKeys(2024);
        assertThat(result.evidenceByEntry().get("current-2026").evidenceByYear())
            .containsOnlyKeys(2026);
        assertThat(result.evidenceByYear().get(2024).traversalComplete()).isTrue();
        assertThat(result.evidenceByYear().get(2026).traversalComplete()).isTrue();
    }

    @Test
    void aYearWithoutAnApplicableRequiredEntryRemainsIncomplete() {
        Map<String, Object> entry = baseEntry(
            "archive-2024", "LINKED_PAGE", "https://official.example/2024/index.html");
        entry.put("recruitmentYears", List.of(2024));
        entry.put("historicalMaxPages", 2);
        entry.put("nextPageSelector", "a.next[href]");

        var result = new ConfigurableSourceListingReader(new MapFetcher(Map.of()),
            new StaticHtmlSourceDiscoverer()).read(sourceWithEntries(List.of(entry)),
                new ListingQuery(Set.of(2025), true));

        assertThat(result.evidenceByEntry()).isEmpty();
        assertThat(result.evidenceByYear().get(2025).traversalComplete()).isFalse();
        assertThat(result.evidenceByYear().get(2025).stopReason())
            .isEqualTo("NO_APPLICABLE_REQUIRED_ENTRY");
    }

    @Test
    void aKnownOfficialArchiveGapCannotBeReportedAsCompleteAfterIndexTraversal() {
        Map<String, Object> entry = baseEntry(
            "partial-archive", "LINKED_PAGE", "https://official.example/2024/index.html");
        entry.put("recruitmentYears", List.of(2024));
        entry.put("knownArchiveGapYears", List.of(2024));
        entry.put("historicalMaxPages", 2);
        entry.put("nextPageSelector", "a.next[href]");

        var result = new ConfigurableSourceListingReader(new MapFetcher(Map.of(
            URI.create("https://official.example/2024/index.html"),
            page("/art/2024/8/20/art_1.html", "2024年招聘公告"))),
            new StaticHtmlSourceDiscoverer()).read(sourceWithEntries(List.of(entry)),
                new ListingQuery(Set.of(2024), true));

        assertThat(result.evidenceByEntry().get("partial-archive")
            .evidenceByYear().get(2024).traversalComplete()).isTrue();
        assertThat(result.evidenceByYear().get(2024).traversalComplete()).isFalse();
        assertThat(result.evidenceByYear().get(2024).stopReason())
            .isEqualTo("KNOWN_OFFICIAL_ARCHIVE_GAP");
        assertThat(result.evidenceByYear().get(2024).completionBasis())
            .contains("official archive gap");
    }

    @Test
    void anOptionalCampaignEntryCannotProveAnnualCompletenessByItself() {
        Map<String, Object> entry = baseEntry(
            "campaign", "CAMPAIGN_STATE", "https://official.example/campaign");
        entry.put("completenessRequired", false);
        var fetcher = new MapFetcher(Map.of(
            URI.create("https://official.example/campaign"),
            page("/art/2026/8/20/art_1.html", "2026年招聘公告")
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(sourceWithEntries(List.of(entry)), new ListingQuery(Set.of(2026), true));

        assertThat(result.evidenceByYear().get(2026).traversalComplete()).isFalse();
        assertThat(result.evidenceByYear().get(2026).stopReason())
            .isEqualTo("NO_APPLICABLE_REQUIRED_ENTRY");
    }

    @Test
    void canonicalDedupNormalizesHostDefaultPortAndTrackingParametersAcrossEntries() {
        Map<String, Object> first = baseEntry(
            "first", "FIXED_EVIDENCE", "https://official.example/evidence-a");
        first.put("historicalEvidenceByYear", Map.of("2026", List.of(
            "https://OFFICIAL.EXAMPLE:443/art/2026/8/20/art_1.html?utm_source=archive")));
        Map<String, Object> second = baseEntry(
            "second", "FIXED_EVIDENCE", "https://official.example/evidence-b");
        second.put("historicalEvidenceByYear", Map.of("2026", List.of(
            "https://official.example/art/2026/8/20/art_1.html")));

        var result = new ConfigurableSourceListingReader(new MapFetcher(Map.of()),
            new StaticHtmlSourceDiscoverer()).read(sourceWithEntries(List.of(first, second)),
                new ListingQuery(Set.of(2026), true));

        assertThat(result.links()).singleElement().satisfies(link ->
            assertThat(link.link().uri()).hasToString(
                "https://official.example/art/2026/8/20/art_1.html"));
    }

    @Test
    void boundedIncrementalQueryReturnsDiscoveredLinksAtItsPageLimit() {
        Map<String, Object> entry = baseEntry(
            "query", "QUERY_PAGE", "https://official.example/notices");
        entry.put("historicalMaxPages", 9);
        entry.put("incrementalListingMaxPages", 2);
        entry.put("pageParameter", "page");
        var fetcher = new MapFetcher(Map.of(
            URI.create("https://official.example/notices?page=1"),
                page("/art/2026/8/20/art_1.html", "2026年招聘公告"),
            URI.create("https://official.example/notices?page=2"),
                page("/art/2026/8/21/art_2.html", "2026年招聘公告（二）")
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(), false));

        assertThat(result.links()).hasSize(2);
        assertThat(result.evidenceByYear()).isEmpty();
    }

    @Test
    void boundedIncrementalTemplateReturnsDiscoveredLinksAtItsPageLimit() {
        Map<String, Object> entry = baseEntry(
            "template", "STATIC_SUFFIX_TEMPLATE", "https://official.example/list1.htm");
        entry.put("pageUriTemplate", "https://official.example/list{page}.htm");
        entry.put("historicalMaxPages", 9);
        entry.put("incrementalListingMaxPages", 2);
        var fetcher = new MapFetcher(Map.of(
            URI.create("https://official.example/list1.htm"),
                page("/art/2026/8/20/art_1.html", "2026年招聘公告"),
            URI.create("https://official.example/list2.htm"),
                page("/art/2026/8/21/art_2.html", "2026年招聘公告（二）")
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(), false));

        assertThat(result.links()).hasSize(2);
        assertThat(result.evidenceByYear()).isEmpty();
    }

    @Test
    void boundedIncrementalLinkedTraversalReturnsDiscoveredLinksAtItsPageLimit() {
        Map<String, Object> entry = baseEntry(
            "linked", "LINKED_PAGE", "https://official.example/list1.html");
        entry.put("historicalMaxPages", 9);
        entry.put("incrementalListingMaxPages", 2);
        entry.put("nextPageSelector", "a.next[href]");
        var fetcher = new MapFetcher(Map.of(
            URI.create("https://official.example/list1.html"), linkedPage(
                "/art/2026/8/20/art_1.html", "2026年招聘公告", "/list2.html"),
            URI.create("https://official.example/list2.html"), linkedPage(
                "/art/2026/8/21/art_2.html", "2026年招聘公告（二）", "/list3.html")
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(), false));

        assertThat(result.links()).hasSize(2);
        assertThat(result.evidenceByYear()).isEmpty();
    }

    @Test
    void boundedIncrementalJsonApiReturnsDiscoveredLinksAtItsPageLimit() {
        Map<String, Object> entry = baseEntry(
            "api", "JSON_API", "https://official.example/api/notices");
        entry.put("historicalMaxPages", 9);
        entry.put("incrementalListingMaxPages", 2);
        entry.put("historicalPageSize", 1);
        entry.put("pageParameter", "current");
        entry.put("pageSizeParameter", "size");
        entry.put("jsonItemsPath", "data.records");
        entry.put("jsonTotalPath", "data.total");
        entry.put("jsonUrlField", "url");
        entry.put("jsonTitleField", "title");
        var fetcher = new MapFetcher(Map.of(
            URI.create("https://official.example/api/notices?current=1&size=1"),
                jsonApiPage(50, "/art/2026/8/20/art_1.html", "2026年招聘公告"),
            URI.create("https://official.example/api/notices?current=2&size=1"),
                jsonApiPage(50, "/art/2026/8/21/art_2.html", "2026年招聘公告（二）")
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(), false));

        assertThat(result.links()).hasSize(2);
        assertThat(result.evidenceByYear()).isEmpty();
    }

    @Test
    void staticTemplateUsesConfiguredUrisAndConfirmsTheTerminalEmptyPage() {
        Map<String, Object> entry = baseEntry(
            "template", "STATIC_SUFFIX_TEMPLATE", "https://official.example/News130a1.htm");
        entry.put("pageUriTemplate", "https://official.example/News130a{page}.htm");
        entry.put("historicalMaxPages", 5);
        entry.put("emptyPageConfirmationPages", 1);
        URI first = URI.create("https://official.example/News130a1.htm");
        URI second = URI.create("https://official.example/News130a2.htm");
        URI third = URI.create("https://official.example/News130a3.htm");
        var fetcher = new MapFetcher(Map.of(
            first, page("/art/2026/8/20/art_1.html", "2026年招聘公告"),
            second, "<html/>".getBytes(StandardCharsets.UTF_8),
            third, "<html/>".getBytes(StandardCharsets.UTF_8)
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(2026), true));

        assertThat(fetcher.requests()).containsExactly(first, second, third);
        assertThat(result.evidenceByYear().get(2026).traversalComplete()).isTrue();
    }

    @Test
    void staticTemplateRejectsAnEmptyMiddlePage() {
        Map<String, Object> entry = baseEntry(
            "template", "STATIC_SUFFIX_TEMPLATE", "https://official.example/News130a1.htm");
        entry.put("pageUriTemplate", "https://official.example/News130a{page}.htm");
        entry.put("historicalMaxPages", 5);
        entry.put("emptyPageConfirmationPages", 1);
        var fetcher = new MapFetcher(Map.of(
            URI.create("https://official.example/News130a1.htm"),
                page("/art/2026/8/20/art_1.html", "2026年招聘公告"),
            URI.create("https://official.example/News130a2.htm"), "<html/>".getBytes(StandardCharsets.UTF_8),
            URI.create("https://official.example/News130a3.htm"),
                page("/art/2025/8/20/art_2.html", "2025年招聘公告")
        ));

        assertThatThrownBy(() -> new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(2025, 2026), true)))
            .isInstanceOf(com.careeros.application.AcquisitionHttpPorts.FetchFailedException.class)
            .hasMessageContaining("empty middle page");
    }

    @Test
    void queryPaginationPreservesExistingParameters() {
        Map<String, Object> entry = baseEntry(
            "query", "QUERY_PAGE", "https://official.example/notices?category=jobs&lang=zh");
        entry.put("historicalMaxPages", 3);
        entry.put("pageParameter", "page");
        URI first = URI.create("https://official.example/notices?category=jobs&lang=zh&page=1");
        URI second = URI.create("https://official.example/notices?category=jobs&lang=zh&page=2");
        var fetcher = new MapFetcher(Map.of(
            first, page("/art/2026/8/20/art_1.html", "2026年招聘公告"),
            second, "<html/>".getBytes(StandardCharsets.UTF_8)
        ));

        new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(2026), true));

        assertThat(fetcher.requests()).containsExactly(first, second);
    }

    @Test
    void queryPaginationPreservesAlreadyEncodedParameterValues() {
        Map<String, Object> entry = baseEntry(
            "query", "QUERY_PAGE", "https://official.example/notices?category=%E6%8B%9B%E8%81%98");
        entry.put("historicalMaxPages", 2);
        entry.put("pageParameter", "page");
        URI first = URI.create("https://official.example/notices?category=%E6%8B%9B%E8%81%98&page=1");
        URI second = URI.create("https://official.example/notices?category=%E6%8B%9B%E8%81%98&page=2");
        var fetcher = new MapFetcher(Map.of(
            first, page("/art/2026/8/20/art_1.html", "2026年招聘公告"),
            second, "<html/>".getBytes(StandardCharsets.UTF_8)
        ));

        new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(2026), true));

        assertThat(fetcher.requests()).containsExactly(first, second);
    }

    @Test
    void queryPaginationRejectsRepeatedNonTerminalPages() {
        Map<String, Object> entry = baseEntry(
            "query", "QUERY_PAGE", "https://official.example/notices");
        entry.put("historicalMaxPages", 3);
        entry.put("pageParameter", "page");
        byte[] repeated = page("/art/2026/8/20/art_1.html", "2026年招聘公告");
        var fetcher = new MapFetcher(Map.of(
            URI.create("https://official.example/notices?page=1"), repeated,
            URI.create("https://official.example/notices?page=2"), repeated
        ));

        assertThatThrownBy(() -> new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(2026), true)))
            .isInstanceOf(com.careeros.application.AcquisitionHttpPorts.FetchFailedException.class)
            .hasMessageContaining("repeated a non-terminal page");
    }

    @Test
    void queryPaginationFailsAtTheConfiguredMaximumWithoutATerminalPage() {
        Map<String, Object> entry = baseEntry(
            "query", "QUERY_PAGE", "https://official.example/notices");
        entry.put("historicalMaxPages", 2);
        entry.put("pageParameter", "page");
        var fetcher = new MapFetcher(Map.of(
            URI.create("https://official.example/notices?page=1"),
                page("/art/2026/8/20/art_1.html", "2026年招聘公告"),
            URI.create("https://official.example/notices?page=2"),
                page("/art/2026/8/21/art_2.html", "2026年招聘公告（二）")
        ));

        assertThatThrownBy(() -> new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(2026), true)))
            .isInstanceOf(com.careeros.application.AcquisitionHttpPorts.FetchFailedException.class)
            .hasMessageContaining("exceeded configured page limit");
    }

    @Test
    void numberedHtmlStopsAtReportedTotalWithoutRequestingARepeatedSecondPage() {
        Map<String, Object> entry = baseEntry("data", "QUERY_PAGE", "https://official.example/notices");
        entry.put("historicalMaxPages", 5);
        entry.put("pageParameter", "page");
        entry.put("reportedTotalRegex", "recordNum=(\\d+)");
        URI first = URI.create("https://official.example/notices?page=1");
        byte[] html = ("<div>recordNum=1</div>" +
            "<a href='/art/2026/8/20/art_1.html'>2026年招聘公告</a>")
            .getBytes(StandardCharsets.UTF_8);
        var fetcher = new MapFetcher(Map.of(first, html));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(2026), true));

        assertThat(fetcher.requests()).containsExactly(first);
        assertThat(result.evidenceByEntry().get("data").evidenceByYear().get(2026).stopReason())
            .isEqualTo("REPORTED_TOTAL_REACHED");
    }

    @Test
    void numberedJcmsCanReconcileOfficialRowsWhileRejectingUncontractedExternalLinks() {
        Map<String, Object> entry = baseEntry(
            "health", "STATIC_SUFFIX_TEMPLATE", "https://official.example/health");
        entry.put("historicalMaxPages", 3);
        entry.put("pageUriTemplate", "https://official.example/api/unit?page={page}");
        entry.put("reportedTotalRegex", "count=\\\\\"(\\d+)\\\\\"");
        entry.put("reconcileReportedTotalByListingItems", true);
        entry.put("listingItemSelector", "ul.ajax-ul > li");
        entry.put("itemLinkSelector", "a[href]");
        URI first = URI.create("https://official.example/api/unit?page=1");
        byte[] response = ("{\"data\":{\"html\":\"<div count=\\\"2\\\"></div>"
            + "<ul class='ajax-ul'><li><a href='/art/2026/8/20/art_1.html'>2026年招聘公告</a></li>"
            + "<li><a href='https://external.example/post'>外部同步稿</a></li></ul>\"}}")
            .getBytes(StandardCharsets.UTF_8);
        var fetcher = new MapFetcher(Map.of(first, response));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(2026), true));

        assertThat(fetcher.requests()).containsExactly(first);
        assertThat(result.links()).hasSize(1);
        assertThat(result.evidenceByEntry().get("health").evidenceByYear().get(2026).rawCount())
            .isEqualTo(1);
        assertThat(result.evidenceByEntry().get("health").evidenceByYear().get(2026).stopReason())
            .isEqualTo("REPORTED_TOTAL_REACHED");
    }

    @Test
    void numberedHtmlRejectsTerminalReportedTotalMismatch() {
        Map<String, Object> entry = baseEntry("data", "QUERY_PAGE", "https://official.example/notices");
        entry.put("historicalMaxPages", 5);
        entry.put("pageParameter", "page");
        entry.put("reportedTotalRegex", "recordNum=(\\d+)");
        entry.put("reportedTotalPagesRegex", "pages=(\\d+)");
        URI first = URI.create("https://official.example/notices?page=1");
        byte[] html = ("<div>recordNum=2 pages=1</div>" +
            "<a href='/art/2026/8/20/art_1.html'>2026年招聘公告</a>")
            .getBytes(StandardCharsets.UTF_8);
        var fetcher = new MapFetcher(Map.of(first, html));

        assertThatThrownBy(() -> new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(2026), true)))
            .isInstanceOf(com.careeros.application.AcquisitionHttpPorts.FetchFailedException.class)
            .hasMessageContaining("reported total");
    }

    @Test
    void numberedHtmlStopsAtReportedLastPageAndReconcilesUniqueCount() {
        Map<String, Object> entry = baseEntry("xixi", "QUERY_PAGE", "https://official.example/notices");
        entry.put("historicalMaxPages", 9);
        entry.put("pageParameter", "page");
        entry.put("reportedTotalRegex", "total=(\\d+)");
        entry.put("reportedTotalPagesRegex", "pages=(\\d+)");
        entry.put("reportedCurrentPageRegex", "current=(\\d+)");
        URI first = URI.create("https://official.example/notices?page=1");
        URI second = URI.create("https://official.example/notices?page=2");
        var fetcher = new MapFetcher(Map.of(
            first, ("total=2 pages=2 current=1<a href='/art/2026/8/20/art_1.html'>2026年招聘公告</a>").getBytes(StandardCharsets.UTF_8),
            second, ("total=2 pages=2 current=2<a href='/art/2026/8/21/art_2.html'>2026年招聘公告（二）</a>").getBytes(StandardCharsets.UTF_8)));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(2026), true));

        assertThat(fetcher.requests()).containsExactly(first, second);
        assertThat(result.links()).hasSize(2);
        assertThat(result.evidenceByEntry().get("xixi").evidenceByYear().get(2026).stopReason())
            .isEqualTo("REPORTED_LAST_PAGE_REACHED");
    }

    @Test
    void multiEntryJcmsRejectsAReportedTotalThatDoesNotMatchUniqueEntries() {
        Map<String, Object> entry = baseEntry(
            "jcms", "JCMS_PARAM_JSON", "https://official.example/column/index.html");
        entry.put("listingApiUri", "https://official.example/api/list?channel=jobs");
        entry.put("historicalPageSize", 2);
        entry.put("historicalMaxPages", 3);
        RecruitmentSource source = singleEntrySource(entry);
        URI first = URI.create("https://official.example/api/list?channel=jobs&paramJson="
            + URLEncoder.encode("{\"pageNo\":1,\"pageSize\":2}", StandardCharsets.UTF_8));
        var fetcher = new MapFetcher(Map.of(
            first, jcmsPage(2, "/art/2026/8/20/art_1.html", "2026年招聘公告")
        ));

        assertThatThrownBy(() -> new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(source, new ListingQuery(Set.of(2026), true)))
            .isInstanceOf(com.careeros.application.AcquisitionHttpPorts.FetchFailedException.class)
            .hasMessageContaining("reported total");
    }

    @Test
    void multiEntryJcmsSerializesStructuredSearchAsANestedJsonStringAcrossPages() throws Exception {
        Map<String, Object> entry = baseEntry(
            "jcms", "JCMS_PARAM_JSON", "https://official.example/column/index.html");
        entry.put("listingApiUri", "https://official.example/api/list?channel=jobs");
        entry.put("historicalPageSize", 1);
        entry.put("historicalMaxPages", 2);
        entry.put("jcmsSearch", new LinkedHashMap<>(Map.of(
            "xxgkId", "F001",
            "xxgkType", "",
            "className", "人事\"信息\\archive")));
        var requests = new java.util.ArrayList<URI>();
        DocumentFetcher fetcher = request -> {
            requests.add(request.uri());
            int page = requests.size();
            return new FetchedDocument(request.uri(), 200, "application/json",
                jcmsPage(2, "/art/2026/8/2" + page + "/art_" + page + ".html",
                    "2026年招聘公告" + page), null, null);
        };

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(singleEntrySource(entry), new ListingQuery(Set.of(2026), true));

        assertThat(result.links()).hasSize(2);
        assertThat(requests).hasSize(2);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int index = 0; index < requests.size(); index++) {
            String encoded = java.util.Arrays.stream(requests.get(index).getRawQuery().split("&"))
                .filter(value -> value.startsWith("paramJson="))
                .findFirst().orElseThrow().substring("paramJson=".length());
            var outer = mapper.readTree(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
            assertThat(outer.path("pageNo").asInt()).isEqualTo(index + 1);
            assertThat(outer.path("pageSize").asInt()).isEqualTo(1);
            assertThat(outer.path("search").isTextual()).isTrue();
            var search = mapper.readTree(outer.path("search").asText());
            assertThat(search.path("xxgkId").asText()).isEqualTo("F001");
            assertThat(search.path("xxgkType").asText()).isEmpty();
            assertThat(search.path("className").asText()).isEqualTo("人事\"信息\\archive");
        }
    }

    @Test
    void multiEntryJcmsRejectsANonObjectStructuredSearch() {
        Map<String, Object> entry = baseEntry(
            "jcms", "JCMS_PARAM_JSON", "https://official.example/column/index.html");
        entry.put("listingApiUri", "https://official.example/api/list");
        entry.put("historicalPageSize", 15);
        entry.put("historicalMaxPages", 2);
        entry.put("jcmsSearch", "xxgkId=F001");

        assertThatThrownBy(() -> new ConfigurableSourceListingReader(
            new MapFetcher(Map.of()), new StaticHtmlSourceDiscoverer()).read(
                singleEntrySource(entry), new ListingQuery(Set.of(2026), true)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jcmsSearch", "object");
    }

    @Test
    void jsonApiExtractsDeclaredItemsAndReconcilesTheTotal() {
        Map<String, Object> entry = baseEntry(
            "api", "JSON_API", "https://official.example/api/notices?category=jobs");
        entry.put("historicalMaxPages", 3);
        entry.put("historicalPageSize", 10);
        entry.put("pageParameter", "current");
        entry.put("pageSizeParameter", "size");
        entry.put("jsonItemsPath", "data.records");
        entry.put("jsonTotalPath", "data.total");
        entry.put("jsonUrlField", "url");
        entry.put("jsonTitleField", "title");
        URI first = URI.create("https://official.example/api/notices?category=jobs&current=1&size=10");
        byte[] json = "{\"data\":{\"total\":1,\"records\":[{\"url\":\"/art/2026/8/20/art_1.html\",\"title\":\"2026年招聘公告\"}]}}"
            .getBytes(StandardCharsets.UTF_8);

        var result = new ConfigurableSourceListingReader(new MapFetcher(Map.of(first, json)),
            new StaticHtmlSourceDiscoverer()).read(singleEntrySource(entry),
                new ListingQuery(Set.of(2026), true));

        assertThat(result.links()).singleElement().satisfies(link ->
            assertThat(link.link().uri()).hasToString("https://official.example/art/2026/8/20/art_1.html"));
        assertThat(result.evidenceByEntry().get("api").evidenceByYear().get(2026).stopReason())
            .isEqualTo("REPORTED_TOTAL_REACHED");
    }

    @Test
    void jsonApiRejectsADeclaredTotalMismatch() {
        Map<String, Object> entry = baseEntry(
            "api", "JSON_API", "https://official.example/api/notices");
        entry.put("historicalMaxPages", 2);
        entry.put("historicalPageSize", 10);
        entry.put("pageParameter", "current");
        entry.put("pageSizeParameter", "size");
        entry.put("jsonItemsPath", "data.records");
        entry.put("jsonTotalPath", "data.total");
        entry.put("jsonUrlField", "url");
        entry.put("jsonTitleField", "title");
        URI first = URI.create("https://official.example/api/notices?current=1&size=10");
        byte[] json = "{\"data\":{\"total\":2,\"records\":[{\"url\":\"/art/2026/8/20/art_1.html\",\"title\":\"2026年招聘公告\"}]}}"
            .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ConfigurableSourceListingReader(new MapFetcher(Map.of(first, json)),
            new StaticHtmlSourceDiscoverer()).read(singleEntrySource(entry),
                new ListingQuery(Set.of(2026), true)))
            .isInstanceOf(com.careeros.application.AcquisitionHttpPorts.FetchFailedException.class)
            .hasMessageContaining("reported total");
    }

    @Test
    void embeddedDataExtractsConfiguredOfficialPositions() {
        Map<String, Object> entry = baseEntry(
            "embedded", "EMBEDDED_DATA", "https://official.example/positions");
        entry.put("embeddedDataSelector", "script#positions");
        entry.put("embeddedItemsPath", "positions");
        entry.put("jsonUrlField", "url");
        entry.put("jsonTitleField", "title");
        byte[] html = ("<script id='positions' type='application/json'>"
            + "{\"positions\":[{\"url\":\"/art/2026/8/20/art_1.html\",\"title\":\"2026年招聘工程师\"}]}"
            + "</script>").getBytes(StandardCharsets.UTF_8);

        var result = new ConfigurableSourceListingReader(
            new MapFetcher(Map.of(URI.create("https://official.example/positions"), html)),
            new StaticHtmlSourceDiscoverer()).read(singleEntrySource(entry),
                new ListingQuery(Set.of(2026), true));

        assertThat(result.links()).singleElement().satisfies(link ->
            assertThat(link.link().title()).isEqualTo("2026年招聘工程师"));
    }

    @Test
    void multiEntryTraversalDeduplicatesCanonicalLinksAndOrdersLifecycleFirst() {
        URI primary = URI.create("https://official.example/jobs/index.html");
        URI lifecycle = URI.create("https://official.example/lifecycle/index.html");
        var fetcher = new MapFetcher(Map.of(
            primary, page("/art/2026/8/20/art_1.html?utm_source=primary", "2026年公开招聘公告"),
            lifecycle, ("<a href='/art/2026/8/20/art_1.html'>2026年招聘面试通知</a>"
                + "<a href='/art/2026/8/21/art_2.html'>2026年招聘资格复审通知</a>")
                .getBytes(StandardCharsets.UTF_8)
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(multiEntrySource(true), new ListingQuery(Set.of(2026), true));

        assertThat(result.links()).extracting(link -> link.link().uri().toString())
            .containsExactly(
                "https://official.example/art/2026/8/20/art_1.html",
                "https://official.example/art/2026/8/21/art_2.html");
        assertThat(result.links().getFirst().link().title()).contains("面试");
        assertThat(result.evidenceByEntry()).containsOnlyKeys("primary", "lifecycle");
    }

    @Test
    void incompleteRequiredEntryKeepsAggregatedYearIncomplete() {
        URI primary = URI.create("https://official.example/jobs/index.html");
        URI lifecycle = URI.create("https://official.example/lifecycle/index.html");
        var fetcher = new MapFetcher(Map.of(
            primary, page("/art/2026/8/20/art_1.html", "2026年公开招聘公告"),
            lifecycle, page("/art/2026/8/21/art_2.html", "2026年招聘面试通知")
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(multiEntrySource(true), new ListingQuery(Set.of(2026), true));

        assertThat(result.evidenceByEntry().get("primary").evidenceByYear().get(2026).traversalComplete())
            .isTrue();
        assertThat(result.evidenceByEntry().get("lifecycle").evidenceByYear().get(2026).traversalComplete())
            .isFalse();
        assertThat(result.evidenceByYear().get(2026).traversalComplete()).isFalse();
    }

    @Test
    void incompleteOptionalEntryDoesNotBlockAggregatedYearCompletion() {
        URI primary = URI.create("https://official.example/jobs/index.html");
        URI lifecycle = URI.create("https://official.example/lifecycle/index.html");
        var fetcher = new MapFetcher(Map.of(
            primary, page("/art/2026/8/20/art_1.html", "2026年公开招聘公告"),
            lifecycle, page("/art/2026/8/21/art_2.html", "2026年招聘面试通知")
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(multiEntrySource(false), new ListingQuery(Set.of(2026), true));

        assertThat(result.evidenceByYear().get(2026).traversalComplete()).isTrue();
    }

    @Test
    void staticSuffixTraversalStopsOnlyAtAnUnambiguousEmptyPage() {
        URI first = URI.create("https://renshi.hdu.edu.cn/rczp/list.htm");
        var fetcher = new MapFetcher(Map.of(
            first, page("/2026/0313/c13762a290230/page.htm", "2026年公开招聘工作人员公告"),
            URI.create("https://renshi.hdu.edu.cn/rczp/list2.htm"),
                page("/2024/0319/c13762a270001/page.htm", "2024年公开招聘工作人员公告"),
            URI.create("https://renshi.hdu.edu.cn/rczp/list3.htm"),
                page("/2023/0319/c13762a260001/page.htm", "2023年公开招聘工作人员公告"),
            URI.create("https://renshi.hdu.edu.cn/rczp/list4.htm"), "<html><body></body></html>".getBytes(StandardCharsets.UTF_8)
        ));
        var reader = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer());

        var result = reader.read(source(), new ListingQuery(Set.of(2024, 2025, 2026), true));

        assertThat(result.links()).extracting(link -> link.recruitmentYear())
            .containsExactly(2026, 2024);
        assertThat(result.evidenceByYear().get(2024).stopReason())
            .isEqualTo("EMPTY_PAGE");
        assertThat(fetcher.requests()).containsExactly(
            first,
            URI.create("https://renshi.hdu.edu.cn/rczp/list2.htm"),
            URI.create("https://renshi.hdu.edu.cn/rczp/list3.htm"),
            URI.create("https://renshi.hdu.edu.cn/rczp/list4.htm"));
    }

    @Test
    void pinnedOldAnnouncementDoesNotHideNewerEntriesOnFollowingPages() {
        URI first = URI.create("https://renshi.hdu.edu.cn/rczp/list.htm");
        var fetcher = new MapFetcher(Map.of(
            first, page("/2023/0101/c13762a250001/page.htm", "置顶：2023年招聘公告"),
            URI.create("https://renshi.hdu.edu.cn/rczp/list2.htm"),
                page("/2025/0319/c13762a280001/page.htm", "2025年公开招聘工作人员公告"),
            URI.create("https://renshi.hdu.edu.cn/rczp/list3.htm"),
                "<html><body></body></html>".getBytes(StandardCharsets.UTF_8)
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(source(), new ListingQuery(Set.of(2024, 2025, 2026), true));

        assertThat(result.links()).extracting(link -> link.recruitmentYear()).containsExactly(2025);
        assertThat(result.evidenceByYear().get(2025).traversalComplete()).isTrue();
        assertThat(fetcher.requests()).hasSize(3);
    }

    @Test
    void repeatedNonTerminalStaticPageIsAContractFailure() {
        URI first = URI.create("https://renshi.hdu.edu.cn/rczp/list.htm");
        byte[] repeated = page("/2026/0313/c13762a290230/page.htm", "2026年公开招聘工作人员公告");
        var fetcher = new MapFetcher(Map.of(
            first, repeated,
            URI.create("https://renshi.hdu.edu.cn/rczp/list2.htm"), repeated
        ));
        var reader = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer());

        assertThatThrownBy(() -> reader.read(source(), new ListingQuery(Set.of(2024, 2025, 2026), true)))
            .isInstanceOf(com.careeros.application.AcquisitionHttpPorts.FetchFailedException.class)
            .hasMessageContaining("repeated a non-terminal page");
    }

    @Test
    void linkedPageTraversalFollowsTheOfficialNextLinkUntilItDisappears() {
        URI first = URI.create("https://www.gongshu.gov.cn/col/col1229113747/index.html");
        URI second = URI.create("https://www.gongshu.gov.cn/col/col1229113747/index_2.html");
        var fetcher = new MapFetcher(Map.of(
            first, linkedPage(
                "/art/2026/8/20/art_1229113747_999001.html", "2026年事业单位公开招聘公告",
                "/col/col1229113747/index_2.html"),
            second, ("<html><body>"
                + "<a href=\"/art/2026/8/20/art_1229113747_999001.html\">2026年事业单位公开招聘公告</a>"
                + "<a href=\"/art/2024/6/10/art_1229113747_888001.html\">2024年事业单位公开招聘公告</a>"
                + "</body></html>").getBytes(StandardCharsets.UTF_8)
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(linkedSource(), new ListingQuery(Set.of(2024, 2025, 2026), true));

        assertThat(result.links()).extracting(link -> link.recruitmentYear())
            .containsExactly(2026, 2024);
        assertThat(result.evidenceByYear().get(2024).stopReason())
            .isEqualTo("NO_NEXT_LINK");
        assertThat(result.evidenceByYear().get(2024).traversalComplete()).isTrue();
        assertThat(fetcher.requests()).containsExactly(first, second);
    }

    @Test
    void linkedPageTraversalRejectsANextLinkOutsideTheOfficialHostAllowlist() {
        URI first = URI.create("https://www.gongshu.gov.cn/col/col1229113747/index.html");
        var fetcher = new MapFetcher(Map.of(
            first, linkedPage(
                "/art/2026/8/20/art_1229113747_999001.html", "2026年事业单位公开招聘公告",
                "https://tracker.example/index_2.html")
        ));

        assertThatThrownBy(() -> new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(linkedSource(), new ListingQuery(Set.of(2024, 2025, 2026), true)))
            .isInstanceOf(com.careeros.application.AcquisitionHttpPorts.FetchFailedException.class)
            .hasMessageContaining("allowed official host");
    }

    @Test
    void linkedPageTraversalRejectsPaginationCycles() {
        URI first = URI.create("https://www.gongshu.gov.cn/col/col1229113747/index.html");
        URI second = URI.create("https://www.gongshu.gov.cn/col/col1229113747/index_2.html");
        var fetcher = new MapFetcher(Map.of(
            first, linkedPage(
                "/art/2026/8/20/art_1229113747_999001.html", "2026年事业单位公开招聘公告",
                "/col/col1229113747/index_2.html"),
            second, linkedPage(
                "/art/2024/6/10/art_1229113747_888001.html", "2024年事业单位公开招聘公告",
                "/col/col1229113747/index.html")
        ));

        assertThatThrownBy(() -> new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(linkedSource(), new ListingQuery(Set.of(2024, 2025, 2026), true)))
            .isInstanceOf(com.careeros.application.AcquisitionHttpPorts.FetchFailedException.class)
            .hasMessageContaining("cycle");
    }

    @Test
    void boundedIncrementalJcmsTraversalFindsRecruitmentBeyondTheCurrentGeneralNoticePage() {
        RecruitmentSource source = qiantangSource();
        URI first = jcmsUri(source, 1, 20);
        URI second = jcmsUri(source, 2, 20);
        var fetcher = new MapFetcher(Map.of(
            first, jcmsPage(100,
                "/col/col1657687/art/2026/art_a111.html", "关于规划道路项目的公示"),
            second, jcmsPage(100,
                "/col/col1657687/art/2026/art_b222.html", "2026年钱塘区事业单位公开招聘工作人员公告")
        ));

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(source, new ListingQuery(Set.of(), false));

        assertThat(result.links()).singleElement().satisfies(link -> {
            assertThat(link.recruitmentYear()).isEqualTo(2026);
            assertThat(link.link().uri()).hasToString(
                "https://www.qiantang.gov.cn/col/col1657687/art/2026/art_b222.html");
        });
        assertThat(fetcher.requests()).containsExactly(first, second);
    }

    private static RecruitmentSource source() {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        return new RecruitmentSource(
            UUID.fromString("01992f09-0000-7000-8000-000000000303"),
            "HDU_RECRUITMENT", "杭州电子科技大学招聘",
            URI.create("https://renshi.hdu.edu.cn/"),
            URI.create("https://renshi.hdu.edu.cn/rczp/list.htm"),
            SourceType.OFFICIAL_UNIVERSITY, "杭州", CrawlMode.STATIC_HTML,
            true, "0 30 8 * * *", "Asia/Shanghai", Duration.ofMillis(1_500),
            Map.of(
                "adapterType", "STATIC_HTML",
                "historicalPaginationMode", "STATIC_PAGE_SUFFIX",
                "historicalMaxPages", 20,
                "articleUrlRegex", "^https://renshi\\.hdu\\.edu\\.cn/[0-9]{4}/[0-9]{4}/c[0-9]+a[0-9]+/page\\.htm$",
                "linkSelector", "a[href]",
                "titleIncludeRegex", "招聘|招考|选聘|引进",
                "titleExcludeRegex", "拟聘|公示|成绩|体检|递补"
            ), null, null, now, 0, now, now);
    }

    private static RecruitmentSource linkedSource() {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        return new RecruitmentSource(
            UUID.fromString("01992f09-0000-7000-8000-000000000401"),
            "HZ_GONGSHU_GOV", "杭州市拱墅区政府招聘",
            URI.create("https://www.gongshu.gov.cn/"),
            URI.create("https://www.gongshu.gov.cn/col/col1229113747/index.html"),
            SourceType.OFFICIAL_GOVERNMENT, "杭州", CrawlMode.STATIC_HTML,
            true, "0 30 8 * * *", "Asia/Shanghai", Duration.ofMillis(1_500),
            Map.of(
                "adapterType", "STATIC_HTML",
                "historicalPaginationMode", "LINKED_PAGE",
                "historicalMaxPages", 20,
                "nextPageSelector", "a.next[href]",
                "articleUrlRegex", "^https://www\\.gongshu\\.gov\\.cn/art/[0-9]{4}/[0-9]{1,2}/[0-9]{1,2}/art_[0-9]+_[0-9]+\\.html$",
                "linkSelector", "a[href]",
                "titleIncludeRegex", "招聘|招考|选聘|引进",
                "titleExcludeRegex", "拟聘|公示|成绩|体检|递补"
            ), null, null, now, 0, now, now);
    }

    private static RecruitmentSource qiantangSource() {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        return new RecruitmentSource(
            UUID.fromString("01992f09-0000-7000-8000-000000000403"),
            "HZ_QIANTANG_GOV", "杭州市钱塘区政府招聘",
            URI.create("https://www.qiantang.gov.cn/"),
            URI.create("https://www.qiantang.gov.cn/col/col1657687/index.html"),
            SourceType.OFFICIAL_GOVERNMENT, "杭州钱塘", CrawlMode.STATIC_HTML,
            true, "0 20 9 * * *", "Asia/Shanghai", Duration.ofMillis(1_500),
            Map.ofEntries(
                Map.entry("adapterType", "JCMS_LISTING"),
                Map.entry("historicalPaginationMode", "JCMS_PARAM_JSON"),
                Map.entry("listingApiUri", "https://www.qiantang.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=3176&tplSetId=0ZBvCXCKwwIstYiI5LRg9&pageType=column&tagId=%E5%88%97%E8%A1%A8%E9%A1%B5&editType=null&pageId=1657687"),
                Map.entry("historicalPageSize", 20),
                Map.entry("historicalMaxPages", 200),
                Map.entry("incrementalListingMaxPages", 2),
                Map.entry("articleUrlRegex", "^https://www\\.qiantang\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$"),
                Map.entry("linkSelector", "a[href]"),
                Map.entry("titleIncludeRegex", "招聘|招考|选聘|引进|雇员"),
                Map.entry("titleExcludeRegex", "招聘会|培训|讲座")
            ), null, null, now, 0, now, now);
    }

    private static RecruitmentSource multiEntrySource(boolean lifecycleRequired) {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("code", "primary");
        primary.put("entryUri", "https://official.example/jobs/index.html");
        primary.put("role", "PRIMARY");
        primary.put("mode", "LINKED_PAGE");
        primary.put("recruitmentYears", List.of(2026));
        primary.put("completenessRequired", true);
        primary.put("historicalMaxPages", 3);
        primary.put("nextPageSelector", "a.next[href]");
        primary.put("articleUrlRegex", "^https://official\\.example/art/[0-9]{4}/[0-9]+/[0-9]+/art_[0-9]+\\.html$");
        primary.put("linkSelector", "a[href]");
        primary.put("titleIncludeRegex", "招聘");
        primary.put("titleExcludeRegex", "面试|复审");

        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("code", "lifecycle");
        lifecycle.put("entryUri", "https://official.example/lifecycle/index.html");
        lifecycle.put("role", "LIFECYCLE");
        lifecycle.put("mode", "CAMPAIGN_STATE");
        lifecycle.put("recruitmentYears", List.of(2026));
        lifecycle.put("completenessRequired", lifecycleRequired);
        lifecycle.put("articleUrlRegex", primary.get("articleUrlRegex"));
        lifecycle.put("linkSelector", "a[href]");
        lifecycle.put("titleIncludeRegex", "面试|复审");
        lifecycle.put("titleExcludeRegex", "(?!)");

        return new RecruitmentSource(
            UUID.fromString("01992f09-0000-7000-8000-000000000499"),
            "MULTI", "多入口官方来源",
            URI.create("https://official.example/"),
            URI.create("https://official.example/jobs/index.html"),
            SourceType.OFFICIAL_GOVERNMENT, "杭州", CrawlMode.STATIC_HTML,
            true, "0 30 8 * * *", "Asia/Shanghai", Duration.ofMillis(1_500),
            Map.of("adapterType", "STATIC_HTML", "listingEntries", List.of(primary, lifecycle)),
            null, null, now, 0, now, now);
    }

    private static Map<String, Object> baseEntry(String code, String mode, String entryUri) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", code);
        entry.put("entryUri", entryUri);
        entry.put("mode", mode);
        entry.put("role", "PRIMARY");
        entry.put("recruitmentYears", List.of(2024, 2025, 2026));
        entry.put("completenessRequired", true);
        entry.put("adapterType", "STATIC_HTML");
        entry.put("articleUrlRegex", "^https://official\\.example/art/[0-9]{4}/[0-9]+/[0-9]+/art_[0-9]+\\.html$");
        entry.put("linkSelector", "a[href]");
        entry.put("titleIncludeRegex", "招聘");
        entry.put("titleExcludeRegex", "(?!)");
        return entry;
    }

    private static Map<String, Object> auditedEntry(String mode) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", "audited");
        entry.put("entryUri", "http://legacy.example:8080/public/index.html");
        entry.put("mode", mode);
        entry.put("role", "PRIMARY");
        entry.put("recruitmentYears", List.of(2026));
        entry.put("completenessRequired", true);
        entry.put("adapterType", "STATIC_HTML");
        entry.put("transportPolicy", "AUDITED_HTTP_READ_ONLY");
        entry.put("allowedHosts", List.of("legacy.example"));
        entry.put("exactAuthorities", List.of("legacy.example:8080"));
        entry.put("allowedPathPrefixes", List.of("/public/"));
        entry.put("articleUrlRegex", "^http://legacy\\.example:8080/public/art_[0-9]+\\.html$");
        entry.put("linkSelector", "a[href]");
        entry.put("titleIncludeRegex", "招聘");
        entry.put("titleExcludeRegex", "(?!)");
        return entry;
    }

    private static RecruitmentSource singleEntrySource(Map<String, Object> entry) {
        return sourceWithEntries(List.of(entry));
    }

    private static RecruitmentSource sourceWithEntries(List<Map<String, Object>> entries) {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        return new RecruitmentSource(
            UUID.fromString("01992f09-0000-7000-8000-000000000498"),
            "SINGLE_ENTRY", "单入口官方来源",
            URI.create("https://official.example/"),
            URI.create("https://official.example/index.html"),
            SourceType.OFFICIAL_GOVERNMENT, "杭州", CrawlMode.STATIC_HTML,
            true, "0 30 8 * * *", "Asia/Shanghai", Duration.ofMillis(1_500),
            Map.of("adapterType", "STATIC_HTML", "listingEntries", entries),
            null, null, now, 0, now, now);
    }

    private static URI jcmsUri(RecruitmentSource source, int page, int pageSize) {
        String base = source.configuration().get("listingApiUri").toString();
        String param = URLEncoder.encode(
            "{\"pageNo\":" + page + ",\"pageSize\":" + pageSize + "}", StandardCharsets.UTF_8);
        return URI.create(base + "&paramJson=" + param);
    }

    private static byte[] jcmsPage(int count, String href, String title) {
        String escaped = ("<div count=\"" + count + "\"><a href='" + href + "'>" + title + "</a></div>")
            .replace("\\", "\\\\").replace("\"", "\\\"");
        return ("{\"success\":true,\"data\":{\"html\":\"" + escaped
            + "\"}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] page(String href, String title) {
        return ("<html><body><a href=\"" + href + "\">" + title + "</a></body></html>")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] linkedPage(String href, String title, String nextHref) {
        String next = nextHref == null ? "" : "<a class=\"next\" href=\"" + nextHref + "\">下一页</a>";
        return ("<html><body><a href=\"" + href + "\">" + title + "</a>" + next + "</body></html>")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jsonApiPage(int total, String url, String title) {
        return ("{\"data\":{\"total\":" + total + ",\"records\":[{\"url\":\""
            + url + "\",\"title\":\"" + title + "\"}]}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static final class MapFetcher implements DocumentFetcher {
        private final Map<URI, byte[]> pages;
        private final java.util.List<URI> requests = new java.util.ArrayList<>();

        private MapFetcher(Map<URI, byte[]> pages) {
            this.pages = new LinkedHashMap<>(pages);
        }

        @Override
        public FetchedDocument fetch(FetchRequest request) {
            requests.add(request.uri());
            byte[] content = pages.get(request.uri());
            if (content == null) throw new IllegalArgumentException("Unexpected URI " + request.uri());
            return new FetchedDocument(request.uri(), 200, "text/html", content, null, null);
        }

        java.util.List<URI> requests() {
            return java.util.List.copyOf(requests);
        }
    }
}
