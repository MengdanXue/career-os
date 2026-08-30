package com.careeros.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.FetchMethod;
import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.FetchedDocument;
import com.careeros.application.AcquisitionHttpPorts.HttpReadContract;
import com.careeros.application.AcquisitionHttpPorts.ListingEntryEvidence;
import com.careeros.application.AcquisitionHttpPorts.ListingEvidence;
import com.careeros.application.AcquisitionHttpPorts.ListingResult;
import com.careeros.application.AcquisitionHttpPorts.TransportPolicy;
import com.careeros.application.AcquisitionHttpPorts.TransportRisk;
import com.careeros.application.AcquisitionHttpPorts.YearDiscoveredLink;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AcquisitionHttpPortsTest {
    @Test
    void postFetchRequestAcceptsOnlySmallSafeHeadersAndBody() {
        var contract = new AcquisitionHttpPorts.HttpReadContract(
            AcquisitionHttpPorts.TransportPolicy.HTTPS_ONLY,
            java.util.Set.of("official.example"), java.util.Set.of());

        var request = new AcquisitionHttpPorts.FetchRequest(
            java.net.URI.create("https://official.example/api/search"),
            java.util.Set.of("official.example"), null, null,
            java.time.Duration.ofSeconds(10), 1024, null, java.time.Duration.ZERO,
            AcquisitionHttpPorts.FetchMethod.POST, contract,
            java.util.Map.of("Content-Type", "application/json", "language", "1"),
            "{\"search\":\"招聘\"}");

        assertThat(request.method()).isEqualTo(AcquisitionHttpPorts.FetchMethod.POST);
        assertThat(request.headers()).containsEntry("content-type", "application/json")
            .containsEntry("language", "1");
        assertThat(request.body()).contains("招聘");
    }
    @Test
    void discoveredLinkCarriesAnOptionalExactTransportContract() {
        var audited = new HttpReadContract(
            TransportPolicy.AUDITED_HTTP_READ_ONLY,
            Set.of("official.example"),
            Set.of("/public/jobs"));

        var legacy = new DiscoveredLink(
            URI.create("https://official.example/jobs/notice"), "Legacy notice");
        var contracted = new DiscoveredLink(
            URI.create("http://official.example/public/jobs/notice"), "Audited notice", audited);

        assertThat(legacy.readContract()).isNull();
        assertThat(contracted.readContract()).isEqualTo(audited);
        assertThat(audited.authorizesTarget(contracted.uri())).isTrue();
        assertThat(audited.authorizesTarget(
            URI.create("http://other.example/public/jobs/notice"))).isFalse();
        assertThat(audited.authorizesTarget(
            URI.create("http://official.example/private/notice"))).isFalse();
        assertThat(audited.authorizesTarget(
            URI.create("http://official.example/public/jobs/%252e%252e/private"))).isFalse();
    }

    @Test
    void legacyAuditedContractKeepsRejectingNonDefaultPorts() {
        var contract = new HttpReadContract(
            TransportPolicy.AUDITED_HTTP_READ_ONLY,
            Set.of("124.160.72.42"),
            Set.of("/apply/"));

        assertThat(contract.exactAuthorities()).isEmpty();
        assertThat(contract.authorizesTarget(
            URI.create("http://124.160.72.42:8080/apply/index.action"))).isFalse();
    }

    @Test
    void auditedContractAuthorizesOnlyTheConfiguredExactAuthority() {
        var contract = new HttpReadContract(
            TransportPolicy.AUDITED_HTTP_READ_ONLY,
            Set.of("124.160.72.42"),
            Set.of("124.160.72.42:8080"),
            Set.of("/apply/"));

        assertThat(contract.authorizesTarget(
            URI.create("http://124.160.72.42:8080/apply/index.action"))).isTrue();
        assertThat(contract.authorizesTarget(
            URI.create("http://124.160.72.42:8081/apply/index.action"))).isFalse();
        assertThat(contract.authorizesTarget(
            URI.create("http://124.160.72.42/apply/index.action"))).isFalse();
        assertThat(contract.authorizesTarget(
            URI.create("http://124.160.72.42:80/apply/index.action"))).isFalse();
        assertThat(contract.authorizesTarget(
            URI.create("http://124.160.72.43:8080/apply/index.action"))).isFalse();
    }

    @Test
    void exactAuthoritiesMustBeBareHostPortValuesForAnExactHost() {
        for (String invalid : List.of(
            "http://124.160.72.42:8080",
            "user@124.160.72.42:8080",
            "124.160.72.42:8080/apply",
            "124.160.72.42:8080?x=1",
            "124.160.72.42:8080#fragment",
            "124.160.72.42",
            "124.160.72.42:not-a-port"
        )) {
            assertThatThrownBy(() -> new HttpReadContract(
                TransportPolicy.AUDITED_HTTP_READ_ONLY,
                Set.of("124.160.72.42"),
                Set.of(invalid),
                Set.of("/apply/")))
                .as(invalid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact authorities");
        }

        assertThatThrownBy(() -> new HttpReadContract(
            TransportPolicy.AUDITED_HTTP_READ_ONLY,
            Set.of("official.example"),
            Set.of("other.example:8080"),
            Set.of("/apply/")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exact host");
    }

    @Test
    void httpsOnlyContractsRejectExactAuthorities() {
        assertThatThrownBy(() -> new HttpReadContract(
            TransportPolicy.HTTPS_ONLY,
            Set.of("official.example"),
            Set.of("official.example:8443"),
            Set.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HTTPS_ONLY");
    }

    @Test
    void legacyFetchRequestConstructorsDefaultToSafeHttpsGet() {
        var request = new FetchRequest(
            URI.create("https://official.example/notices"), Set.of("OFFICIAL.EXAMPLE"),
            null, null, Duration.ofSeconds(20), 1024);

        assertThat(request.method()).isEqualTo(FetchMethod.GET);
        assertThat(request.readContract().transportPolicy()).isEqualTo(TransportPolicy.HTTPS_ONLY);
        assertThat(request.readContract().exactHosts()).containsExactly("official.example");
        assertThat(request.readContract().allowedPathPrefixes()).isEmpty();
    }

    @Test
    void explicitFetchRequestCarriesAuditedHeadContract() {
        var contract = new HttpReadContract(
            TransportPolicy.AUDITED_HTTP_READ_ONLY,
            Set.of("official.example"),
            Set.of("/public/jobs/"));

        var request = new FetchRequest(
            URI.create("http://official.example/public/jobs/index.html"), Set.of("official.example"),
            null, null, Duration.ofSeconds(20), 1024, null, Duration.ZERO,
            FetchMethod.HEAD, contract);

        assertThat(request.method()).isEqualTo(FetchMethod.HEAD);
        assertThat(request.readContract()).isEqualTo(contract);
    }

    @Test
    void legacyHttpsFetchRequestPreservesIpv6HostCompatibility() {
        var request = new FetchRequest(
            URI.create("https://[2001:db8::1]/notices"), Set.of("2001:DB8::1"),
            null, null, Duration.ofSeconds(20), 1024);

        assertThat(request.readContract().transportPolicy()).isEqualTo(TransportPolicy.HTTPS_ONLY);
        assertThat(request.readContract().exactHosts()).containsExactly("2001:db8::1");
    }

    @Test
    void legacyFetchedDocumentConstructorDefaultsToNoTransportRisk() {
        var fetched = new FetchedDocument(
            URI.create("https://official.example/notice"), 200, "text/html",
            new byte[] {1, 2}, null, null);

        assertThat(fetched.transportRisk()).isEqualTo(TransportRisk.NONE);
    }

    @Test
    void explicitFetchedDocumentPreservesPlaintextRisk() {
        var fetched = new FetchedDocument(
            URI.create("http://official.example/public/notice"), 200, "text/html",
            new byte[] {1, 2}, null, null, TransportRisk.PLAINTEXT_OFFICIAL_HTTP);

        assertThat(fetched.transportRisk()).isEqualTo(TransportRisk.PLAINTEXT_OFFICIAL_HTTP);
    }

    @Test
    void legacyListingResultConstructorDefaultsToNoPerEntryEvidence() {
        var link = new YearDiscoveredLink(
            new DiscoveredLink(URI.create("https://official.example/notice"), "2026 recruitment"), 2026);
        var annual = new ListingEvidence(1, 1, 1, 0, 0, null, null,
            true, "LAST_PAGE", "official last page");

        var result = new ListingResult(List.of(link), Map.of(2026, annual));

        assertThat(result.evidenceByEntry()).isEmpty();
    }

    @Test
    void listingResultCarriesTypedPerEntryEvidence() {
        var annual = new ListingEvidence(1, 1, 1, 0, 0, null, null,
            true, "LAST_PAGE", "official last page");
        var entryEvidence = new ListingEntryEvidence(
            "primary", true, Map.of(2026, annual));

        var result = new ListingResult(
            List.of(), Map.of(2026, annual), Map.of("primary", entryEvidence));

        assertThat(result.evidenceByEntry()).containsEntry("primary", entryEvidence);
        assertThat(result.evidenceByEntry().get("primary").completenessRequired()).isTrue();
        assertThat(result.evidenceByEntry().get("primary").evidenceByYear())
            .containsEntry(2026, annual);
    }

    @Test
    void listingResultRejectsAnEvidenceMapKeyThatDoesNotMatchTheEntryCode() {
        var annual = new ListingEvidence(1, 1, 1, 0, 0, null, null,
            true, "LAST_PAGE", "official last page");
        var entryEvidence = new ListingEntryEvidence(
            "primary", true, Map.of(2026, annual));

        assertThatThrownBy(() -> new ListingResult(
            List.of(), Map.of(2026, annual), Map.of("lifecycle", entryEvidence)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entry code");
    }
}
