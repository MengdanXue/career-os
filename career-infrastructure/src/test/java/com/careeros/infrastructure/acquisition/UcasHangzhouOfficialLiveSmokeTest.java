package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.AcquisitionHttpPorts.ListingQuery;
import com.careeros.application.AcquisitionHttpPorts.TransportPolicy;
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
class UcasHangzhouOfficialLiveSmokeTest {
    @Test
    void auditedOfficialHttpArchiveExposesRecruitmentAndLifecycleEvidence() {
        var fetcher = new JavaHttpDocumentFetcher(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
            new MediaTypeDetector(), duration -> {
                try { Thread.sleep(duration.toMillis()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "CareerOS/0.3 (+official-source-live-smoke)", 3, 1);
        var definition = OfficialSourceCatalog.load().sources().stream()
            .filter(candidate -> candidate.code().equals("UCAS_HANGZHOU"))
            .findFirst().orElseThrow();
        Instant now = Instant.now();
        var source = new RecruitmentSource(UUID.randomUUID(), definition.code(), definition.name(),
            URI.create(definition.officialRootUrl()),
            URI.create(definition.officialRootUrl()),
            SourceType.OFFICIAL_ORGANIZATION, "浙江杭州", CrawlMode.STATIC_HTML, true,
            "0 0 10 * * *", "Asia/Shanghai", Duration.ZERO, definition.configuration(),
            null, null, now, 0, now, now);

        var result = new ConfigurableSourceListingReader(fetcher, new StaticHtmlSourceDiscoverer())
            .read(source, new ListingQuery(Set.of(2024, 2025, 2026, 2027), true));

        assertThat(result.links()).isNotEmpty().allSatisfy(link -> {
            assertThat(link.link().uri().toString())
                .matches("http://hias\\.ucas\\.ac\\.cn/info/105[78]/[0-9]+\\.htm");
            assertThat(link.link().publishedOn()).isNotNull();
            assertThat(link.link().readContract().transportPolicy())
                .isEqualTo(TransportPolicy.AUDITED_HTTP_READ_ONLY);
        });
        assertThat(result.links()).extracting(link -> link.recruitmentYear())
            .contains(2024, 2025, 2026);
        assertThat(result.evidenceByEntry().values())
            .allSatisfy(evidence -> assertThat(evidence.completenessRequired()).isFalse());
    }
}
