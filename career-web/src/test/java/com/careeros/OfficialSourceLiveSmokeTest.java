package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.ListingQuery;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import com.careeros.infrastructure.acquisition.ConfigurableSourceListingReader;
import com.careeros.infrastructure.acquisition.JavaHttpDocumentFetcher;
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
            .contains("HZ_TCM_HOSPITAL", "HZ_XIXI_HOSPITAL", "HZ_DATA_GROUP");
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
                "HZ_TCM_HOSPITAL", "HZ_XIXI_HOSPITAL", "HZ_DATA_GROUP").contains(source.code()))
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
        URI entry = URI.create(definition.listingEntries().getFirst().get("entryUri").toString());
        return new RecruitmentSource(
            UUID.nameUUIDFromBytes(definition.code().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            definition.code(), definition.name(), URI.create(definition.officialRootUrl()), entry,
            SourceType.OFFICIAL_ORGANIZATION, "浙江杭州", CrawlMode.STATIC_HTML, true,
            "0 0 8 * * *", "Asia/Shanghai", Duration.ZERO, definition.configuration(),
            null, null, now, 0, now, now);
    }
}
