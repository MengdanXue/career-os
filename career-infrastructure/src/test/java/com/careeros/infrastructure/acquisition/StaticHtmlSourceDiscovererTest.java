package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StaticHtmlSourceDiscovererTest {
    @Test
    void auditedEntryRejectsDiscoveredDetailsOnAnUncontractedPort() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", "audited");
        entry.put("entryUri", "http://legacy.example:8080/public/index.html");
        entry.put("mode", "LINKED_PAGE");
        entry.put("transportPolicy", "AUDITED_HTTP_READ_ONLY");
        entry.put("allowedHosts", List.of("legacy.example"));
        entry.put("exactAuthorities", List.of("legacy.example:8080"));
        entry.put("allowedPathPrefixes", List.of("/public/"));
        entry.put("articleUrlRegex", "^http://legacy\\.example:808[01]/public/art_[0-9]+\\.html$");
        entry.put("linkSelector", "a[href]");
        entry.put("titleIncludeRegex", "招聘");
        entry.put("titleExcludeRegex", "(?!)");
        RecruitmentSource source = sourceWithEntries(List.of(entry));
        ListingEntryContract contract = ListingEntryContract.from(source).getFirst();
        byte[] html = ("<a href='http://legacy.example:8080/public/art_1.html'>2026年招聘公告</a>"
            + "<a href='http://legacy.example:8081/public/art_2.html'>2026年招聘公告（伪端口）</a>")
            .getBytes(StandardCharsets.UTF_8);

        var links = new StaticHtmlSourceDiscoverer()
            .discoverAll(source, contract, contract.entryUri(), html);

        assertThat(links).singleElement().satisfies(link ->
            assertThat(link.uri()).hasToString(
                "http://legacy.example:8080/public/art_1.html"));
    }

    @Test
    void structuredItemsExtractTemplateUrisTitlesAndPublishedDates() {
        Map<String, Object> configured = new LinkedHashMap<>(multiEntrySource().configuration());
        Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>)
            ((List<?>) configured.get("listingEntries")).getFirst());
        entry.put("entryUri", "https://official.example/news");
        entry.put("articleUrlRegex", "^https://official\\.example/detail\\?id=[A-Z0-9@]+$");
        entry.put("listingItemSelector", "div.notice");
        entry.put("itemLinkSelector", ".header");
        entry.put("itemTitleSelector", ".title");
        entry.put("itemPublishedDateSelector", ".date");
        entry.put("itemUriAttribute", "data-id");
        entry.put("itemUriTemplate", "/detail?id={value}");
        RecruitmentSource source = new RecruitmentSource(multiEntrySource().id(), "STRUCTURED", "结构化列表",
            URI.create("https://official.example/"), URI.create("https://official.example/news"),
            SourceType.OFFICIAL_GOVERNMENT, "杭州", CrawlMode.STATIC_HTML, true,
            "0 10 8 * * *", "Asia/Shanghai", Duration.ofSeconds(1),
            Map.of("listingEntries", List.of(entry)), null, null, Instant.parse("2026-08-15T00:00:00Z"),
            0, Instant.parse("2026-08-15T00:00:00Z"), Instant.parse("2026-08-15T00:00:00Z"));
        ListingEntryContract contract = ListingEntryContract.from(source).getFirst();
        byte[] html = """
            <div class='notice'><div class='header' data-id='H@42'><span class='title'>2026年招聘公告</span></div><time class='date'>2026-08-24</time></div>
            """.getBytes(StandardCharsets.UTF_8);

        var links = new StaticHtmlSourceDiscoverer().discoverAll(source, contract, contract.entryUri(), html);

        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.uri()).hasToString("https://official.example/detail?id=H@42");
            assertThat(link.title()).isEqualTo("2026年招聘公告");
            assertThat(link.publishedOn()).isEqualTo(LocalDate.of(2026, 8, 24));
        });
    }

    @Test
    void entrySpecificDiscoveryUsesTheActiveEntryFiltersAndHosts() {
        RecruitmentSource source = multiEntrySource();
        ListingEntryContract lifecycle = ListingEntryContract.from(source).getLast();
        byte[] html = ("<a href='https://lifecycle.example/art/2026/8/20/art_1.html'>2026年面试通知</a>"
            + "<a href='https://official.example/art/2026/8/20/art_2.html'>2026年招聘公告</a>")
            .getBytes(StandardCharsets.UTF_8);

        var links = new StaticHtmlSourceDiscoverer().discover(
            source, lifecycle, lifecycle.entryUri(), html);

        assertThat(links).singleElement().satisfies(link ->
            assertThat(link.uri()).hasToString("https://lifecycle.example/art/2026/8/20/art_1.html"));
    }

    @Test
    void findsOnlyCanonicalOfficialRecruitmentArticles() {
        RecruitmentSource source = source();
        byte[] html = """
            <html><body>
              <a href="/art/2026/3/17/art_1229743683_58950000.html?utm_source=x">省属事业单位公开招聘公告</a>
              <a href="/art/2026/3/18/art_1229743683_58950001.html">拟聘人员公示</a>
              <a href="https://external.example/art/2026/3/17/art_x.html">公开招聘</a>
              <a href="/about.html">招聘政策介绍</a>
              <a href="/art/2026/3/17/art_1229743683_58950000.html">省属事业单位公开招聘公告</a>
            </body></html>
            """.getBytes(StandardCharsets.UTF_8);

        var links = new StaticHtmlSourceDiscoverer().discover(source, source.entryUri(), html);
        var rawLinks = new StaticHtmlSourceDiscoverer().discoverAll(source, source.entryUri(), html);

        assertThat(links).hasSize(1);
        assertThat(links.getFirst().uri()).hasToString(
            "https://rlsbt.zj.gov.cn/art/2026/3/17/art_1229743683_58950000.html");
        assertThat(links.getFirst().title()).isEqualTo("省属事业单位公开招聘公告");
        assertThat(rawLinks).extracting(link -> link.title())
            .containsExactly("省属事业单位公开招聘公告", "拟聘人员公示");
    }

    @Test
    void discoversCurrentJcmsLinksFromUnitApiJson() {
        byte[] response = """
            {"success":true,"data":{"html":"<li><a href=\"/col/col1229743683/art/2026/art_99f2702a91aa43edaab3ef5037d8d0ad.html\" title=\"浙江省省属事业单位2026年下半年集中公开招聘人员公告\">招聘公告</a></li>"}}
            """.getBytes(StandardCharsets.UTF_8);

        var links = new StaticHtmlSourceDiscoverer().discover(source(),
            URI.create("https://rlsbt.zj.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit"), response);

        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.uri()).hasToString(
                "https://rlsbt.zj.gov.cn/col/col1229743683/art/2026/art_99f2702a91aa43edaab3ef5037d8d0ad.html");
            assertThat(link.title()).contains("集中公开招聘");
        });
    }

    private static RecruitmentSource source() {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        return new RecruitmentSource(UUID.randomUUID(), "ZJ", "浙江人社",
            URI.create("https://rlsbt.zj.gov.cn/"),
            URI.create("https://rlsbt.zj.gov.cn/col/col1229743683/index.html"),
            SourceType.OFFICIAL_GOVERNMENT, "浙江", CrawlMode.STATIC_HTML, true,
            "0 10 8 * * *", "Asia/Shanghai", Duration.ofSeconds(1), Map.of(
                "articleUrlRegex", "^https://rlsbt\\.zj\\.gov\\.cn(?:/col/col[0-9]+)?/art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
                "linkSelector", "a[href]",
                "titleIncludeRegex", "招聘|招考|选聘|引进",
                "titleExcludeRegex", "拟聘|公示|成绩|体检|递补"),
            null, null, now, 0, now, now);
    }

    private static RecruitmentSource multiEntrySource() {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        Map<String, Object> primary = Map.ofEntries(
            Map.entry("code", "primary"), Map.entry("entryUri", "https://official.example/jobs"),
            Map.entry("mode", "LINKED_PAGE"), Map.entry("articleUrlRegex", "^https://official\\.example/art/.+$"),
            Map.entry("linkSelector", "a[href]"), Map.entry("titleIncludeRegex", "招聘"),
            Map.entry("titleExcludeRegex", "面试"));
        Map<String, Object> lifecycle = Map.ofEntries(
            Map.entry("code", "lifecycle"), Map.entry("entryUri", "https://lifecycle.example/notices"),
            Map.entry("role", "LIFECYCLE"), Map.entry("mode", "LINKED_PAGE"),
            Map.entry("allowedHosts", java.util.List.of("lifecycle.example")),
            Map.entry("articleUrlRegex", "^https://lifecycle\\.example/art/.+$"),
            Map.entry("linkSelector", "a[href]"), Map.entry("titleIncludeRegex", "面试"),
            Map.entry("titleExcludeRegex", "招聘公告"));
        return new RecruitmentSource(UUID.randomUUID(), "MULTI", "多入口",
            URI.create("https://official.example/"), URI.create("https://official.example/jobs"),
            SourceType.OFFICIAL_GOVERNMENT, "杭州", CrawlMode.STATIC_HTML, true,
            "0 10 8 * * *", "Asia/Shanghai", Duration.ofSeconds(1),
            Map.of("listingEntries", java.util.List.of(primary, lifecycle)),
            null, null, now, 0, now, now);
    }

    private static RecruitmentSource sourceWithEntries(List<Map<String, Object>> entries) {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        return new RecruitmentSource(UUID.randomUUID(), "AUDITED", "审计 HTTP 来源",
            URI.create("https://official.example/"), URI.create("https://official.example/jobs"),
            SourceType.OFFICIAL_GOVERNMENT, "杭州", CrawlMode.STATIC_HTML, true,
            "0 10 8 * * *", "Asia/Shanghai", Duration.ofSeconds(1),
            Map.of("listingEntries", entries), null, null, now, 0, now, now);
    }
}
