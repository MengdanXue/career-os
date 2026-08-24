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

    private static byte[] page(String href, String title) {
        return ("<html><body><a href=\"" + href + "\">" + title + "</a></body></html>")
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
