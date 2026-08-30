package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.AcquisitionHttpPorts.ListingQuery;
import com.careeros.domain.RecruitmentLifecycle;
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
class TongluOfficialLiveSmokeTest {
    @Test
    void officialSearchExposesTheCurrentRecruitmentLifecycleAsCanonicalGovernmentLinks() {
        var fetcher = new JavaHttpDocumentFetcher(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
            new MediaTypeDetector(), duration -> {
                try { Thread.sleep(duration.toMillis()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "CareerOS/0.3 (+official-source-live-smoke)", 3, 1);
        var definition = OfficialSourceCatalog.load().sources().stream()
            .filter(candidate -> candidate.code().equals("HZ_TONGLU_GOV"))
            .findFirst().orElseThrow();
        Instant now = Instant.now();
        var source = new RecruitmentSource(UUID.randomUUID(), definition.code(), definition.name(),
            URI.create(definition.officialRootUrl()),
            URI.create(definition.listingEntries().getFirst().get("entryUri").toString()),
            SourceType.OFFICIAL_GOVERNMENT, "杭州桐庐", CrawlMode.STATIC_HTML, true,
            "0 0 10 * * *", "Asia/Shanghai", Duration.ZERO, definition.configuration(),
            null, null, now, 0, now, now);

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(source, new ListingQuery(Set.of(2024, 2025, 2026, 2027), true));

        assertThat(result.traversalComplete()).isTrue();
        assertThat(result.links()).hasSizeGreaterThanOrEqualTo(6).allSatisfy(link -> {
            assertThat(link.recruitmentYear()).isEqualTo(2026);
            assertThat(link.link().uri().toString()).matches(
                "https://www\\.tonglu\\.gov\\.cn/col/col[0-9]+/art/2026/art_[a-f0-9]{32}\\.html");
            assertThat(link.link().publishedOn()).isNotNull();
        });
        assertThat(result.links().stream()
            .flatMap(link -> RecruitmentLifecycle.classify(link.link().title()).stream()))
            .isNotEmpty();
        assertThat(result.evidenceByEntry().values()).singleElement().satisfies(evidence ->
            assertThat(evidence.completenessRequired()).isFalse());
    }
}
