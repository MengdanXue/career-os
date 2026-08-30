package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.AcquisitionHttpPorts.ListingQuery;
import com.careeros.application.AcquisitionHttpPorts.FetchMethod;
import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CAREER_OS_LIVE_SMOKE", matches = "true")
class GovernmentSoeOfficialLiveSmokeTest {
    @Test
    void capitalAndMetroOfficialApisReturnCurrentCanonicalRecruitmentLinks() {
        var fetcher = new JavaHttpDocumentFetcher(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
            new MediaTypeDetector(), duration -> {
                try { Thread.sleep(duration.toMillis()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "CareerOS/0.3 (+official-source-live-smoke)", 3, 1);
        var reader = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer());
        var catalog = OfficialSourceCatalog.load();

        var capitalSource = source(catalog, "HZ_CAPITAL_GROUP");
        var metroSource = source(catalog, "HZ_METRO_GROUP");
        var capital = reader.read(capitalSource,
            new ListingQuery(Set.of(2024, 2025, 2026, 2027), true));
        var metro = reader.read(metroSource,
            new ListingQuery(Set.of(2024, 2025, 2026, 2027), true));

        assertThat(capital.links()).isNotEmpty().allSatisfy(link -> {
            assertThat(link.link().uri().toString()).matches(
                "https://www\\.hzzbco\\.com/newDet_[0-9]+_8");
            assertThat(link.link().publishedOn()).isNotNull();
        });
        assertThat(metro.links()).isNotEmpty().allSatisfy(link -> {
            assertThat(link.link().uri().toString()).matches(
                "https://www\\.hzmetro\\.com/newsCenter/newsContent\\?id=[0-9]+");
            assertThat(link.link().publishedOn()).isNotNull();
        });
        var detail = metro.links().getFirst().link();
        var fetchedDetail = fetcher.fetch(new FetchRequest(
            detail.fetchUri(), detail.readContract().exactHosts(), null, null,
            Duration.ofSeconds(20), 5_242_880, null, Duration.ZERO, FetchMethod.GET,
            detail.readContract(), java.util.Map.of(), null, detail.responseBodyJsonPath()));
        assertThat(fetchedDetail.mediaType()).isEqualTo(MediaTypeDetector.HTML);
        assertThat(new String(fetchedDetail.content(), java.nio.charset.StandardCharsets.UTF_8))
            .contains("招聘");
        var imageDetail = metro.links().stream()
            .map(link -> link.link())
            .filter(link -> link.uri().toString().matches(".*id=162[23]$"))
            .findFirst().orElseThrow();
        var fetchedImageDetail = fetcher.fetch(new FetchRequest(
            imageDetail.fetchUri(), imageDetail.readContract().exactHosts(), null, null,
            Duration.ofSeconds(20), 5_242_880, null, Duration.ZERO, FetchMethod.GET,
            imageDetail.readContract(), java.util.Map.of(), null,
            imageDetail.responseBodyJsonPath()));
        assertThat(new HtmlAttachmentDiscoverer().discover(
            metroSource, imageDetail, fetchedImageDetail.content())).isNotEmpty()
            .allSatisfy(asset -> assertThat(asset.uri().getHost()).contains("hangzhou7.zos.ctyun.cn"));
        assertThat(capital.evidenceByEntry().get("current-recruitment-and-lifecycle")
            .evidenceByYear()).containsKeys(2024, 2025, 2026, 2027);
        assertThat(metro.evidenceByEntry().get("current-recruitment-and-lifecycle")
            .evidenceByYear()).containsKeys(2024, 2025, 2026, 2027);
    }

    private RecruitmentSource source(OfficialSourceCatalog catalog, String code) {
        var definition = catalog.sources().stream().filter(candidate -> candidate.code().equals(code))
            .findFirst().orElseThrow();
        URI entry = URI.create(definition.listingEntries().getFirst().get("entryUri").toString());
        Instant now = Instant.now();
        return new RecruitmentSource(UUID.randomUUID(), definition.code(), definition.name(),
            URI.create(definition.officialRootUrl()), entry, SourceType.OFFICIAL_ORGANIZATION,
            "浙江杭州", CrawlMode.STATIC_HTML, true, "0 0 10 * * *", "Asia/Shanghai",
            Duration.ZERO, definition.configuration(), null, null, now, 0, now, now);
    }
}
