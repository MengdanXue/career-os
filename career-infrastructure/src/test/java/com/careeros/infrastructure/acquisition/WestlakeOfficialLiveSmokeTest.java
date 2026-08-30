package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.AcquisitionHttpPorts.ListingQuery;
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
class WestlakeOfficialLiveSmokeTest {
    @Test
    void officialEngineeringDatasetExposesRelevant2024Through2026RecruitmentDetails() {
        var fetcher = new JavaHttpDocumentFetcher(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
            new MediaTypeDetector(), duration -> {
                try { Thread.sleep(duration.toMillis()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "CareerOS/0.3 (+official-source-live-smoke)", 3, 1);
        var catalog = OfficialSourceCatalog.load();
        var definition = catalog.sources().stream()
            .filter(candidate -> candidate.code().equals("WESTLAKE_RESEARCH"))
            .findFirst().orElseThrow();
        Instant now = Instant.now();
        var source = new RecruitmentSource(UUID.randomUUID(), definition.code(), definition.name(),
            URI.create(definition.officialRootUrl()),
            URI.create(definition.listingEntries().getFirst().get("entryUri").toString()),
            SourceType.OFFICIAL_UNIVERSITY, "浙江杭州", CrawlMode.STATIC_HTML, true,
            "0 0 10 * * *", "Asia/Shanghai", Duration.ZERO, definition.configuration(),
            null, null, now, 0, now, now);

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(source, new ListingQuery(Set.of(2024, 2025, 2026, 2027), true));

        assertThat(result.traversalComplete()).isTrue();
        assertThat(result.links()).isNotEmpty().allSatisfy(link -> {
            assertThat(link.link().uri().toString()).matches(
                "https://engineering\\.westlake\\.edu\\.cn/Recuitment/[0-9]{6}/t[0-9]{8}_[0-9]+\\.shtml");
            assertThat(link.link().publishedOn()).isNotNull();
            assertThat(link.link().title()).doesNotContain("博士后", "教授", "教师", "行政助理");
        });
        assertThat(result.links()).extracting(link -> link.recruitmentYear())
            .contains(2024, 2025, 2026);
    }
}
