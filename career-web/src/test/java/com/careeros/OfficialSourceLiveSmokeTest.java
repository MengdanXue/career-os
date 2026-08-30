package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.ListingQuery;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import com.careeros.infrastructure.acquisition.ConfigurableSourceListingReader;
import com.careeros.infrastructure.acquisition.JavaHttpDocumentFetcher;
import com.careeros.infrastructure.acquisition.ListingEntryContract;
import com.careeros.infrastructure.acquisition.MediaTypeDetector;
import com.careeros.infrastructure.acquisition.OfficialSourceCatalog;
import com.careeros.infrastructure.acquisition.StaticHtmlSourceDiscoverer;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@Tag("acquisition-live")
@EnabledIfSystemProperty(named = "career-os.acquisition.live-smoke-enabled", matches = "true")
class OfficialSourceLiveSmokeTest {
    private final JavaHttpDocumentFetcher fetcher = new JavaHttpDocumentFetcher(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build(),
        new MediaTypeDetector(), JavaHttpDocumentFetcher.Sleeper.threadSleep(),
        "CareerOS/0.3 (+live compatibility smoke)", 5, 0);
    private final StaticHtmlSourceDiscoverer discoverer = new StaticHtmlSourceDiscoverer();

    @Test
    void zhejiangOfficialListingStillMatchesConfiguredArticles() {
        assertCompatible(source(
            "ZJ_HRSS_INSTITUTION",
            "https://rlsbt.zj.gov.cn/",
            "https://rlsbt.zj.gov.cn/col/col1229743683/index.html",
            "https://rlsbt.zj.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=2758&tplSetId=kUBgoFENJiaYxr31jYEph&pageType=column&tagId=%E5%BD%93%E5%89%8D%E6%A0%8F%E7%9B%AE%E5%88%97%E8%A1%A8&editType=null&pageId=1229743683",
            "^https://rlsbt\\.zj\\.gov\\.cn(?:/col/col[0-9]+)?/art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$"));
    }

    @Test
    void hangzhouOfficialListingStillMatchesConfiguredArticles() {
        assertCompatible(source(
            "HZ_HRSS_INSTITUTION",
            "https://hrss.hangzhou.gov.cn/",
            "https://hrss.hangzhou.gov.cn/col/col1229782005/index.html",
            "https://hrss.hangzhou.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=3163&tplSetId=clJESumZainQpjpBI3Qsd&pageType=column&tagId=%E5%88%86%E9%A1%B5%E5%88%97%E8%A1%A8&editType=null&pageId=1229782005",
            "^https://hrss\\.hangzhou\\.gov\\.cn(?:/col/col[0-9]+)?/art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$"));
    }

    @Test
    void xihuOfficialListingStillMatchesConfiguredArticles() {
        assertCompatible(source(
            "HZ_XIHU_GOV",
            "https://www.hzxh.gov.cn/",
            "https://www.hzxh.gov.cn/col/col1368377/index.html",
            "https://www.hzxh.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=1838&tplSetId=wBnYzsSjnCAXcEg2xsahR&pageType=column&tagId=%E7%A7%BB%E5%8A%A8%E7%89%88%E6%A0%8F%E7%9B%AE%E5%88%97%E8%A1%A81&editType=null&pageId=1368377",
            "^https://www\\.hzxh\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$"));
    }

    @Test
    void auditedStaticCatalogListingsStillExposeOfficialRecruitmentLinks() {
        var sources = OfficialSourceCatalog.load().sources().stream()
            .filter(OfficialSourceCatalog.SourceDefinition::enabled)
            .filter(source -> source.strategy() == OfficialSourceCatalog.DiscoveryStrategy.STATIC_HTML)
            .filter(source -> source.listingEntries().isEmpty())
            .toList();
        assertThat(sources).isNotEmpty();
        sources.forEach(source -> {
            URI listing = URI.create(source.listingUrl());
            var fetched = fetcher.fetch(new FetchRequest(listing, Set.of(listing.getHost()),
                null, null, Duration.ofSeconds(20), 26_214_400));
            assertThat(fetched.status()).as(source.code()).isEqualTo(200);
            assertThat(discoverer.discover(source, fetched.content())).as(source.code()).isNotEmpty();
        });
    }

    @Test
    void multiEntryCatalogSourcesStillExposeOfficialIncrementalLinks() {
        var sources = OfficialSourceCatalog.load().sources().stream()
            .filter(OfficialSourceCatalog.SourceDefinition::enabled)
            .filter(source -> !source.listingEntries().isEmpty())
            .toList();
        assertThat(sources).extracting(OfficialSourceCatalog.SourceDefinition::code)
            .contains("HZ_TCM_HOSPITAL", "HZ_XIXI_HOSPITAL", "HZ_DATA_GROUP", "HZ_FIRST_HOSPITAL");
        var reader = new ConfigurableSourceListingReader(fetcher, discoverer);
        sources.forEach(definition -> {
            RecruitmentSource source = multiEntrySource(definition);
            var result = reader.read(source, new ListingQuery(Set.of(), false));
            assertThat(result.links()).as(definition.code()).isNotEmpty();
            assertThat(result.evidenceByEntry()).as(definition.code())
                .hasSize(definition.listingEntries().size());
        });
    }

    @Test
    void multiEntryCatalogSourcesCanReachTheirConfiguredHistoricalTerminalPage() {
        var sources = OfficialSourceCatalog.load().sources().stream()
            .filter(source -> Set.of(
                "HZ_TCM_HOSPITAL", "HZ_XIXI_HOSPITAL", "HZ_DATA_GROUP",
                "HZ_FIRST_HOSPITAL").contains(source.code()))
            .toList();
        var reader = new ConfigurableSourceListingReader(fetcher, discoverer);
        sources.forEach(definition -> {
            var result = reader.read(multiEntrySource(definition),
                new ListingQuery(Set.of(2024, 2025, 2026, 2027), true));
            assertThat(result.links()).as(definition.code()).isNotEmpty();
            assertThat(result.evidenceByEntry()).as(definition.code())
                .hasSize(definition.listingEntries().size());
            assertThat(result.evidenceByEntry().values())
                .allSatisfy(entry -> assertThat(entry.evidenceByYear().values())
                    .allSatisfy(evidence -> assertThat(evidence.traversalComplete()).isTrue()));
        });
    }

    @Test
    void firstHospitalExactHttpSourceReconcilesItsLiveIncrementalListing() {
        var definition = multiEntryDefinition("HZ_FIRST_HOSPITAL");
        var result = new ConfigurableSourceListingReader(fetcher, discoverer)
            .read(multiEntrySource(definition), new ListingQuery(Set.of(), false));

        assertThat(result.links()).isNotEmpty();
        assertThat(result.links()).allSatisfy(link -> assertThat(link.link().uri())
            .hasHost("124.160.72.42").hasPort(8080));
    }

    @Test
    void firstHospitalExactHttpSourceReconcilesAllEightyEightNoticesAcrossThirteenPages() {
        var definition = multiEntryDefinition("HZ_FIRST_HOSPITAL");
        var result = new ConfigurableSourceListingReader(fetcher, discoverer)
            .read(multiEntrySource(definition), new ListingQuery(Set.of(2025, 2026, 2027), true));
        var evidence = result.evidenceByEntry().get("official-notices").evidenceByYear().get(2026);

        assertThat(evidence.pageCount()).isEqualTo(13);
        assertThat(evidence.rawCount()).isEqualTo(88);
        assertThat(evidence.traversalComplete()).isTrue();
        assertThat(evidence.stopReason()).isEqualTo("REPORTED_LAST_PAGE_REACHED");
    }

    @Test
    void fuyangOfficialSubtreesReconcileCountersWithoutHidingThe2024ArchiveGap() {
        var source = multiEntrySource(multiEntryDefinition("HZ_FUYANG_GOV"));
        var result = new ConfigurableSourceListingReader(fetcher, discoverer)
            .read(source, new ListingQuery(Set.of(2024, 2025, 2026, 2027), true));
        var general = result.evidenceByEntry().get("establishment").evidenceByYear().get(2026);
        var health = result.evidenceByEntry().get("health-establishment").evidenceByYear().get(2026);

        assertThat(general.pageCount()).isEqualTo(4);
        assertThat(general.rawCount()).isEqualTo(59);
        assertThat(general.stopReason()).isEqualTo("REPORTED_TOTAL_REACHED");
        assertThat(health.pageCount()).isEqualTo(12);
        // The official counter includes three WeChat rows outside the government read contract.
        assertThat(health.rawCount()).isEqualTo(174);
        assertThat(health.stopReason()).isEqualTo("REPORTED_TOTAL_REACHED");
        assertThat(result.evidenceByYear().get(2024).traversalComplete()).isFalse();
        assertThat(result.evidenceByYear().get(2024).stopReason())
            .isEqualTo("KNOWN_OFFICIAL_ARCHIVE_GAP");
        assertThat(result.evidenceByYear().get(2025).traversalComplete()).isTrue();
        assertThat(result.links()).isNotEmpty();
    }

    @Test
    void shangchengOfficialLifecycleColumnClosesAt112RowsAndPreservesThe2025Gap() {
        var source = multiEntrySource(multiEntryDefinition("HZ_SHANGCHENG_GOV"));
        var result = new ConfigurableSourceListingReader(fetcher, discoverer)
            .read(source, new ListingQuery(Set.of(2024, 2025, 2026, 2027), true));
        var entry = result.evidenceByEntry().get("establishment-and-lifecycle")
            .evidenceByYear().get(2026);

        assertThat(entry.pageCount()).isEqualTo(8);
        assertThat(entry.rawCount()).isEqualTo(112);
        assertThat(entry.stopReason()).isEqualTo("REPORTED_TOTAL_REACHED");
        assertThat(result.evidenceByYear().get(2024).traversalComplete()).isTrue();
        assertThat(result.evidenceByYear().get(2025).traversalComplete()).isFalse();
        assertThat(result.evidenceByYear().get(2025).stopReason())
            .isEqualTo("KNOWN_OFFICIAL_ARCHIVE_GAP");
        assertThat(result.evidenceByYear().get(2026).traversalComplete()).isTrue();
        assertThat(result.links()).isNotEmpty();
    }

    @Test
    void linanStructuredJcmsSearchClosesThe141RowPersonnelColumn() {
        var source = multiEntrySource(multiEntryDefinition("HZ_LINAN_GOV"));
        var result = new ConfigurableSourceListingReader(fetcher, discoverer)
            .read(source, new ListingQuery(Set.of(2024, 2025, 2026, 2027), true));
        var entry = result.evidenceByEntry().get("personnel-information")
            .evidenceByYear().get(2026);

        assertThat(entry.pageCount()).isEqualTo(10);
        assertThat(entry.rawCount()).isEqualTo(141);
        assertThat(entry.stopReason()).isEqualTo("REPORTED_TOTAL_REACHED");
        assertThat(result.evidenceByYear().get(2024).traversalComplete()).isTrue();
        assertThat(result.evidenceByYear().get(2025).traversalComplete()).isTrue();
        assertThat(result.evidenceByYear().get(2026).traversalComplete()).isTrue();
        assertThat(result.links()).isNotEmpty();
    }

    @Test
    void jiandeStructuredJcmsSearchCloses189RowsWithoutHidingThe2024Gap() {
        var source = multiEntrySource(multiEntryDefinition("HZ_JIANDE_GOV"));
        var result = new ConfigurableSourceListingReader(fetcher, discoverer)
            .read(source, new ListingQuery(Set.of(2024, 2025, 2026, 2027), true));
        var entry = result.evidenceByEntry().get("recruitment-records")
            .evidenceByYear().get(2026);

        assertThat(entry.pageCount()).isEqualTo(13);
        assertThat(entry.rawCount()).isEqualTo(189);
        assertThat(entry.stopReason()).isEqualTo("REPORTED_TOTAL_REACHED");
        assertThat(result.evidenceByYear().get(2024).traversalComplete()).isFalse();
        assertThat(result.evidenceByYear().get(2024).stopReason())
            .isEqualTo("KNOWN_OFFICIAL_ARCHIVE_GAP");
        assertThat(result.evidenceByYear().get(2025).traversalComplete()).isTrue();
        assertThat(result.evidenceByYear().get(2026).traversalComplete()).isTrue();
        assertThat(result.links()).isNotEmpty();
    }

    @Test
    void xixiLiveListingPreservesLinksDatesAndIncrementalYearClassification() {
        var source = multiEntrySource(multiEntryDefinition("HZ_XIXI_HOSPITAL"));
        var entry = ListingEntryContract.from(source).stream()
            .filter(value -> value.code().equals("announcements"))
            .findFirst().orElseThrow();
        var fetched = fetcher.fetch(new FetchRequest(
            entry.entryUri(), entry.readContract().exactHosts(), null, null,
            Duration.ofSeconds(20), 26_214_400, source.id(), Duration.ZERO,
            com.careeros.application.AcquisitionHttpPorts.FetchMethod.GET,
            entry.readContract()));

        assertThat(fetched.status()).isEqualTo(200);
        String html = new String(fetched.content(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("ul-imgtxt1", "announcement_desc", "date date2");
        assertThat(html.stripLeading()).startsWith("<!DOCTYPE");
        var document = org.jsoup.Jsoup.parse(html, fetched.finalUri().toString());
        assertThat(document.select("ul").eachAttr("class"))
            .as("parsed ul classes from %s", fetched.finalUri())
            .contains("ul-imgtxt1");
        assertThat(document.select(".ul-imgtxt1 > li")).isNotEmpty();
        var anchor = document.select(".ul-imgtxt1 > li").getFirst().selectFirst("h3 a[href]");
        assertThat(anchor).isNotNull();
        URI resolved = fetched.finalUri().resolve(anchor.attr("href"));
        assertThat(entry.readContract().authorizesTarget(resolved)).isTrue();
        assertThat(resolved.toString()).matches(entry.articleUrlRegex());
        assertThat(discoverer.discoverAll(source, entry, fetched.finalUri(), fetched.content()))
            .isNotEmpty().allSatisfy(link -> assertThat(link.publishedOn()).isNotNull());
        assertThat(discoverer.discover(source, entry, fetched.finalUri(), fetched.content()))
            .isNotEmpty();
        assertThat(new ConfigurableSourceListingReader(fetcher, discoverer)
            .read(source, new ListingQuery(Set.of(), false)).links()).isNotEmpty();
    }

    private static OfficialSourceCatalog.SourceDefinition multiEntryDefinition(String code) {
        return OfficialSourceCatalog.load().sources().stream()
            .filter(source -> source.code().equals(code))
            .findFirst().orElseThrow();
    }

    private void assertCompatible(RecruitmentSource source) {
        URI listingApi = URI.create(source.configuration().get("listingApiUri").toString());
        var fetched = fetcher.fetch(new FetchRequest(listingApi, Set.of(source.baseUri().getHost()),
            null, null, Duration.ofSeconds(20), 26_214_400));
        assertThat(fetched.status())
            .as("%s entry page returned HTTP %s: %s", source.code(), fetched.status(), source.entryUri())
            .isEqualTo(200);
        assertThat(discoverer.discover(source, fetched.finalUri(), fetched.content()))
            .as("%s selector no longer finds recruitment articles at %s", source.code(), source.entryUri())
            .isNotEmpty();
    }

    private static RecruitmentSource source(
        String code, String base, String entry, String listingApi, String articleRegex
    ) {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        return new RecruitmentSource(UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            code, code, URI.create(base), URI.create(entry), SourceType.OFFICIAL_GOVERNMENT,
            "浙江杭州", CrawlMode.STATIC_HTML, true, "0 0 8 * * *", "Asia/Shanghai",
            Duration.ofSeconds(1), Map.of(
                "listingApiUri", listingApi,
                "articleUrlRegex", articleRegex,
                "linkSelector", "a[href]",
                "attachmentSelector", "a[href]",
                "titleIncludeRegex", "招聘|招考|选聘|引进",
                "titleExcludeRegex", "拟聘|公示|成绩|体检|递补",
                "maxListPages", 1), null, null, now, 0, now, now);
    }

    private static RecruitmentSource multiEntrySource(
        OfficialSourceCatalog.SourceDefinition definition
    ) {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        URI entry = URI.create(definition.officialRootUrl());
        return new RecruitmentSource(
            UUID.nameUUIDFromBytes(definition.code().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            definition.code(), definition.name(), URI.create(definition.officialRootUrl()), entry,
            SourceType.OFFICIAL_ORGANIZATION, "浙江杭州", CrawlMode.STATIC_HTML, true,
            "0 0 8 * * *", "Asia/Shanghai", Duration.ZERO, definition.configuration(),
            null, null, now, 0, now, now);
    }
}
