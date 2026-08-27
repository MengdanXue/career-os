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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigurableSourceListingReaderTest {
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
