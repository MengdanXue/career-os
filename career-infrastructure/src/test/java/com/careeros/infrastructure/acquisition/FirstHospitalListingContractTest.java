package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.AcquisitionHttpPorts.DocumentFetcher;
import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.FetchedDocument;
import com.careeros.application.AcquisitionHttpPorts.ListingQuery;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FirstHospitalListingContractTest {
    private static final String AUTHORITY = "124.160.72.42:8080";
    private static final URI FIRST_PAGE = URI.create(
        "http://" + AUTHORITY + "/apply/getMore.action?pageNumber=1");

    @Test
    void officialNoticeContractExtractsPublishedDatesAndScopesEveryReadPath() {
        RecruitmentSource source = source();
        ListingEntryContract entry = ListingEntryContract.from(source).getFirst();
        byte[] page = listingPage(2, 1, List.of(
            item(1, "2026年上半年公开招聘工作人员公告", "2026-02-10"),
            item(2, "2026年上半年公开招聘笔试及后续安排通知", "2026-03-01")));

        var links = new StaticHtmlSourceDiscoverer()
            .discover(source, entry, FIRST_PAGE, page);

        assertThat(links).hasSize(2);
        assertThat(links).extracting(link -> link.publishedOn())
            .containsExactly(LocalDate.parse("2026-02-10"), LocalDate.parse("2026-03-01"));
        assertThat(entry.readContract().authorizesTarget(URI.create(
            "http://" + AUTHORITY + "/apply/getNotice.action?keycode=" + uuid(1)))).isTrue();
        assertThat(entry.readContract().authorizesTarget(URI.create(
            "http://" + AUTHORITY + "/apply/downloadAccessory.action?keycode=" + uuid(1)))).isTrue();
        assertThat(entry.readContract().authorizesTarget(URI.create(
            "http://" + AUTHORITY + "/file_zp/attached/image/2026-02-10/jobs.png"))).isTrue();
        assertThat(entry.readContract().authorizesTarget(URI.create(
            "http://" + AUTHORITY + "/apply/index.action"))).isFalse();
        assertThat(entry.readContract().authorizesTarget(URI.create(
            "http://124.160.72.42/apply/getMore.action?pageNumber=1"))).isFalse();
    }

    @Test
    void officialCountersStopTheHistoricalTraversalAtThirteenPagesBeforeTheRepeatedOverflowPage() {
        Map<URI, byte[]> pages = new LinkedHashMap<>();
        int sequence = 1;
        for (int page = 1; page <= 13; page++) {
            int pageSize = page == 13 ? 4 : 7;
            List<String> items = new ArrayList<>();
            for (int offset = 0; offset < pageSize; offset++) {
                String title = sequence % 2 == 0
                    ? "2026年上半年公开招聘工作人员公告"
                    : "2026年上半年公开招聘笔试及后续安排通知";
                items.add(item(sequence, title, "2026-03-01"));
                sequence++;
            }
            pages.put(page(page), listingPage(88, 13, items));
        }
        pages.put(page(14), pages.get(page(13)));
        MapFetcher fetcher = new MapFetcher(pages);

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(source(), new ListingQuery(Set.of(2026), true));

        assertThat(result.links()).hasSize(88);
        assertThat(result.evidenceByEntry().get("official-notices")
            .evidenceByYear().get(2026)).satisfies(evidence -> {
                assertThat(evidence.pageCount()).isEqualTo(13);
                assertThat(evidence.rawCount()).isEqualTo(88);
                assertThat(evidence.traversalComplete()).isTrue();
                assertThat(evidence.stopReason()).isEqualTo("REPORTED_LAST_PAGE_REACHED");
            });
        assertThat(fetcher.requests()).hasSize(13).doesNotContain(page(14));
    }

    private static RecruitmentSource source() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", "official-notices");
        entry.put("entryUri", FIRST_PAGE.toString());
        entry.put("role", "PRIMARY");
        entry.put("mode", "QUERY_PAGE");
        entry.put("recruitmentYears", List.of(2025, 2026));
        entry.put("completenessRequired", true);
        entry.put("transportPolicy", "AUDITED_HTTP_READ_ONLY");
        entry.put("allowedHosts", List.of("124.160.72.42"));
        entry.put("exactAuthorities", List.of(AUTHORITY));
        entry.put("allowedPathPrefixes", List.of(
            "/apply/getMore.action",
            "/apply/getNotice.action",
            "/apply/downloadAccessory.action",
            "/file_zp/attached/"));
        entry.put("historicalMaxPages", 30);
        entry.put("incrementalListingMaxPages", 2);
        entry.put("pageParameter", "pageNumber");
        entry.put("reportedTotalRegex", "共\\s*(\\d+)\\s*条");
        entry.put("reportedTotalPagesRegex", "共\\s*(\\d+)\\s*页");
        entry.put("listingItemSelector", "div.keleyi");
        entry.put("itemLinkSelector", "a[href*='getNotice.action?keycode=']");
        entry.put("itemPublishedDateSelector", "span:matchesOwn(^20\\d{2}-\\d{2}-\\d{2}$)");
        entry.put("linkSelector", "div.keleyi a[href*='getNotice.action?keycode=']");
        entry.put("articleUrlRegex", "^http://124\\.160\\.72\\.42:8080/apply/getNotice\\.action\\?keycode="
            + "[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$");
        entry.put("titleIncludeRegex",
            "招聘|招考|引进|报名|笔试|面试|考务|资格|复审|体检|考察|公示|录用|递补|成绩|后续|名单|通知");
        entry.put("titleExcludeRegex", "招聘会|技术支持|岗位培训");

        Instant now = Instant.parse("2026-08-30T12:00:00Z");
        return new RecruitmentSource(
            UUID.fromString("01992f09-0000-7000-8000-000000000412"),
            "HZ_FIRST_HOSPITAL_RECRUITMENT", "杭州市第一人民医院招聘系统",
            URI.create("https://www.hz-hospital.com/"),
            URI.create("https://www.hz-hospital.com/"),
            SourceType.OFFICIAL_ORGANIZATION, "杭州", CrawlMode.STATIC_HTML,
            true, "0 20 11 * * *", "Asia/Shanghai", Duration.ofMillis(1_500),
            Map.of(
                "adapterType", "STATIC_HTML",
                "imageEvidenceSelector", "img[src*='file_zp/attached/']",
                "listingEntries", List.of(entry)),
            null, null, now, 0, now, now);
    }

    private static URI page(int page) {
        return URI.create("http://" + AUTHORITY + "/apply/getMore.action?pageNumber=" + page);
    }

    private static String item(int sequence, String title, String date) {
        return "<div class='keleyi'><div><span><a href='../apply/getNotice.action?keycode="
            + uuid(sequence) + "'>" + title + "</a></span></div>"
            + "<div style='float:right'><span>发布时间：<span>" + date + "</span></span></div></div>";
    }

    private static String uuid(int sequence) {
        return new UUID(0L, sequence).toString();
    }

    private static byte[] listingPage(int total, int totalPages, List<String> items) {
        return ("<html><body><div>共" + total + "条/ 1-10</div>"
            + String.join("", items) + "<a class='col3'>共" + totalPages + "页</a></body></html>")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static final class MapFetcher implements DocumentFetcher {
        private final Map<URI, byte[]> pages;
        private final List<URI> requests = new ArrayList<>();

        private MapFetcher(Map<URI, byte[]> pages) {
            this.pages = Map.copyOf(pages);
        }

        @Override
        public FetchedDocument fetch(FetchRequest request) {
            requests.add(request.uri());
            byte[] content = pages.get(request.uri());
            if (content == null) throw new IllegalArgumentException("Unexpected URI " + request.uri());
            return new FetchedDocument(request.uri(), 200, "text/html", content, null, null);
        }

        private List<URI> requests() {
            return List.copyOf(requests);
        }
    }
}
