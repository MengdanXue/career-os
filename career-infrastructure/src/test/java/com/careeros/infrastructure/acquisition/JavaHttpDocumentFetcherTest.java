package com.careeros.infrastructure.acquisition;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.AcquisitionHttpPorts.FetchRejectedException;
import com.careeros.application.AcquisitionHttpPorts.FetchMethod;
import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.HttpReadContract;
import com.careeros.application.AcquisitionHttpPorts.ResponseTooLargeException;
import com.careeros.application.AcquisitionHttpPorts.TransportPolicy;
import com.careeros.application.AcquisitionHttpPorts.TransportRisk;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.URI;
import java.net.Authenticator;
import java.net.CookieManager;
import java.net.PasswordAuthentication;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JavaHttpDocumentFetcherTest {
    private WireMockServer server;
    private final RecordingSleeper sleeper = new RecordingSleeper();
    private JavaHttpDocumentFetcher fetcher;

    @Test
    void constructorRejectsClientsThatCanFollowRedirectsOrCarryAmbientState() {
        List<HttpClient> unsafeClients = List.of(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
            HttpClient.newBuilder().cookieHandler(new CookieManager()).build(),
            HttpClient.newBuilder().authenticator(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication("user", "secret".toCharArray());
                }
            }).build()
        );

        for (HttpClient unsafe : unsafeClients) {
            assertThatThrownBy(() -> new JavaHttpDocumentFetcher(
                unsafe, new MediaTypeDetector(), sleeper, "CareerOS/0.3 (test)", 5, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stateless");
        }
    }

    @Test
    void auditedHttpGetMarksPlaintextRiskAndCarriesNoCredentialsOrBody() {
        server.stubFor(get("/public/data").willReturn(ok("official")));

        var result = fetcher.fetch(auditedRequest("/public/data", FetchMethod.GET));

        assertThat(result.transportRisk()).isEqualTo(TransportRisk.PLAINTEXT_OFFICIAL_HTTP);
        var sent = server.getAllServeEvents().getFirst().getRequest();
        assertThat(sent.getHeader("Authorization")).isNull();
        assertThat(sent.getHeader("Cookie")).isNull();
        assertThat(sent.getBody()).isEmpty();
    }

    @Test
    void auditedHttpHeadReturnsNoContentAndSendsNoBody() {
        server.stubFor(any(urlEqualTo("/public/meta")).willReturn(ok("must-not-be-consumed")));

        var result = fetcher.fetch(auditedRequest("/public/meta", FetchMethod.HEAD));

        assertThat(result.content()).isEmpty();
        assertThat(result.transportRisk()).isEqualTo(TransportRisk.PLAINTEXT_OFFICIAL_HTTP);
        var sent = server.getAllServeEvents().getFirst().getRequest();
        assertThat(sent.getMethod().getName()).isEqualTo("HEAD");
        assertThat(sent.getBody()).isEmpty();
    }

    @Test
    void auditedHttpAcceptsOnlyItsExactHostDefaultPortAndPathPrefix() {
        FetchRequest request = auditedOfficialRequest(URI.create("http://official.example/public/list"));

        JavaHttpDocumentFetcher.requireAllowed(request.uri(), request);
        JavaHttpDocumentFetcher.requireAllowed(
            URI.create("http://official.example:80/public/details?id=1"), request);

        assertThatThrownBy(() -> JavaHttpDocumentFetcher.requireAllowed(
            URI.create("http://other.example/public/list"), request))
            .isInstanceOf(FetchRejectedException.class)
            .hasMessageContaining("exact host");
        assertThatThrownBy(() -> JavaHttpDocumentFetcher.requireAllowed(
            URI.create("http://official.example/private/list"), request))
            .isInstanceOf(FetchRejectedException.class)
            .hasMessageContaining("path");
        assertThatThrownBy(() -> JavaHttpDocumentFetcher.requireAllowed(
            URI.create("http://official.example/publicity/list"), request))
            .isInstanceOf(FetchRejectedException.class)
            .hasMessageContaining("path");
        assertThatThrownBy(() -> JavaHttpDocumentFetcher.requireAllowed(
            URI.create("http://official.example:8080/public/list"), request))
            .isInstanceOf(FetchRejectedException.class)
            .hasMessageContaining("default port");
    }

    @Test
    void exactAuthorityContractReportsDefaultPortAsOutsideItsAuthority() {
        var contract = new HttpReadContract(
            TransportPolicy.AUDITED_HTTP_READ_ONLY,
            Set.of("124.160.72.42"),
            Set.of("124.160.72.42:8080"),
            Set.of("/apply/"));
        var request = new FetchRequest(
            URI.create("http://124.160.72.42:8080/apply/index.action"),
            Set.of("124.160.72.42"), null, null, Duration.ofSeconds(20), 1024,
            null, Duration.ZERO, FetchMethod.GET, contract);

        assertThatThrownBy(() -> JavaHttpDocumentFetcher.requireAllowed(
            URI.create("http://124.160.72.42/apply/index.action"), request))
            .isInstanceOf(FetchRejectedException.class)
            .hasMessageContaining("exact authority");
    }

    @Test
    void auditedHttpRejectsRedirectOutsideItsConfiguredPathBeforeSendingIt() {
        server.stubFor(get("/public/start").willReturn(temporaryRedirect("/private/secret")));

        assertThatThrownBy(() -> fetcher.fetch(auditedRequest("/public/start", FetchMethod.GET)))
            .isInstanceOf(FetchRejectedException.class)
            .hasMessageContaining("path");
        server.verify(0, getRequestedFor(urlEqualTo("/private/secret")));
    }

    @Test
    void auditedHttpRejectsUnauthorizedDiscoveredTargetsBeforeAnySend() {
        var contract = new HttpReadContract(TransportPolicy.AUDITED_HTTP_READ_ONLY,
            Set.of("localhost"), Set.of("/public"));
        List<URI> unauthorized = List.of(
            URI.create(server.baseUrl() + "/private/attachment.xlsx"),
            URI.create("http://127.0.0.1:" + server.port() + "/public/detail.html"));

        for (URI uri : unauthorized) {
            FetchRequest request = new FetchRequest(uri, Set.of("localhost"), null, null,
                Duration.ofSeconds(20), 1024, null, Duration.ZERO, FetchMethod.GET, contract);
            assertThatThrownBy(() -> fetcher.fetch(request))
                .as(uri.toString())
                .isInstanceOf(FetchRejectedException.class);
        }
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void auditedHttpRejectsEncodedPathTraversalAndDelimiterBypasses() {
        FetchRequest request = auditedOfficialRequest(URI.create("http://official.example/public/list"));

        for (String path : List.of(
            "/public/%2e%2e/private", "/public/%2E%2E/private", "/public%2fprivate",
            "/public%5Cprivate", "/public/%252e%252e/private"
        )) {
            assertThatThrownBy(() -> JavaHttpDocumentFetcher.requireAllowed(
                URI.create("http://official.example" + path), request))
                .as(path)
                .isInstanceOf(FetchRejectedException.class)
                .hasMessageContaining("encoded path");
        }
    }

    @Test
    void auditedHttpRejectsEncodedTraversalRedirectBeforeSendingIt() {
        server.stubFor(get("/public/start-encoded")
            .willReturn(temporaryRedirect("/public/%252e%252e/private")));

        assertThatThrownBy(() -> fetcher.fetch(auditedRequest("/public/start-encoded", FetchMethod.GET)))
            .isInstanceOf(FetchRejectedException.class)
            .hasMessageContaining("encoded path");
        assertThat(server.getAllServeEvents()).hasSize(1);
    }

    @BeforeEach void start() {
        server = new WireMockServer(0);
        server.start();
        configureFor("localhost", server.port());
        fetcher = new JavaHttpDocumentFetcher(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
            new MediaTypeDetector(), sleeper, "CareerOS/0.3 (test)", 5, 2);
    }

    @AfterEach void stop() { server.stop(); }

    @Test void sendsValidatorsAndReturnsNotModifiedWithoutContent() {
        server.stubFor(get("/notice").withHeader("If-None-Match", equalTo("etag-1"))
            .willReturn(aResponse().withStatus(304).withHeader("ETag", "etag-1")));

        var result = fetcher.fetch(request("/notice", "etag-1", "Wed, 12 Aug 2026 08:00:00 GMT", 1024));

        assertThat(result.notModified()).isTrue();
        assertThat(result.content()).isEmpty();
    }

    @Test void identifiesRequestsAsXmlHttpRequestsForOfficialJcmsUnitApi() {
        String path = "/api-gateway/jpaas-publish-server/front/page/build/unit";
        server.stubFor(get(path).withHeader("X-Requested-With", equalTo("XMLHttpRequest"))
            .willReturn(okJson("{\"success\":true}")));

        var result = fetcher.fetch(request(path, null, null, 1024));

        assertThat(result.status()).isEqualTo(200);
    }

    @Test void ordinaryHtmlRequestsAreNotMisidentifiedAsXmlHttpRequests() {
        server.stubFor(get("/notice").withHeader("X-Requested-With", absent())
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                .withBody("<html><body>招聘公告</body></html>")));

        var result = fetcher.fetch(request("/notice", null, null, 1024));

        assertThat(result.status()).isEqualTo(200);
    }

    @Test void postSendsTheConfiguredJsonBodyAndSafeStaticHeaders() {
        server.stubFor(post("/api/article/search")
            .withHeader("Content-Type", equalTo("application/json"))
            .withHeader("language", equalTo("1"))
            .withRequestBody(equalToJson("{\"search\":\"招聘\",\"current\":1}"))
            .willReturn(okJson("{\"data\":{\"total\":0}}")));
        var contract = new HttpReadContract(
            TransportPolicy.HTTPS_ONLY, Set.of("localhost"), Set.of());
        var request = new FetchRequest(
            URI.create(server.baseUrl() + "/api/article/search"), Set.of("localhost"),
            null, null, Duration.ofSeconds(20), 1024, null, Duration.ZERO,
            FetchMethod.POST, contract,
            Map.of("Content-Type", "application/json", "language", "1"),
            "{\"search\":\"招聘\",\"current\":1}");

        var result = fetcher.fetch(request);

        assertThat(result.status()).isEqualTo(200);
        server.verify(1, postRequestedFor(urlEqualTo("/api/article/search")));
    }

    @Test void extractsConfiguredOfficialHtmlFromAJsonDetailResponse() {
        server.stubFor(get("/api/section/articleInfo?articleId=1666")
            .willReturn(okJson("{\"data\":{\"contentHtml\":\"<article><h1>招聘公告</h1><p>完整正文</p></article>\"}}")));
        var contract = new HttpReadContract(
            TransportPolicy.HTTPS_ONLY, Set.of("localhost"), Set.of());
        var request = new FetchRequest(
            URI.create(server.baseUrl() + "/api/section/articleInfo?articleId=1666"),
            Set.of("localhost"), null, null, Duration.ofSeconds(20), 4096,
            null, Duration.ZERO, FetchMethod.GET, contract, java.util.Map.of(), null,
            "data.contentHtml");

        var result = fetcher.fetch(request);

        assertThat(result.mediaType()).isEqualTo(MediaTypeDetector.HTML);
        assertThat(new String(result.content(), StandardCharsets.UTF_8))
            .contains("招聘公告", "完整正文").doesNotContain("contentHtml");
    }

    @Test void rejectsRedirectToHostOutsideAllowlist() {
        server.stubFor(get("/notice").willReturn(temporaryRedirect("https://external.example/file.pdf")));

        assertThatThrownBy(() -> fetcher.fetch(request("/notice", null, null, 1024)))
            .isInstanceOf(FetchRejectedException.class)
            .hasMessageContaining("external.example");
    }

    @Test void rejectsSameHostHttpRedirectTargetsBeforeSendingThem() {
        var request = new FetchRequest(URI.create("https://official.example/notice"),
            Set.of("official.example"), null, null, Duration.ofSeconds(20), 1024);

        assertThatThrownBy(() -> JavaHttpDocumentFetcher.requireAllowed(
            URI.create("http://official.example/file.pdf"), request))
            .isInstanceOf(FetchRejectedException.class)
            .hasMessageContaining("HTTPS");
    }

    @Test void httpsOnlyLoopbackCompatibilityRejectsNonHttpSchemes() {
        FetchRequest request = request("/notice", null, null, 1024);

        for (String target : List.of("file://localhost/notice", "ftp://localhost/notice")) {
            assertThatThrownBy(() -> JavaHttpDocumentFetcher.requireAllowed(URI.create(target), request))
                .as(target)
                .isInstanceOf(FetchRejectedException.class)
                .hasMessageContaining("HTTPS");
        }
    }

    @Test void upgradesAllowlistedSameHostHttpRedirectWithoutSendingPlainHttp() {
        var request = new FetchRequest(URI.create("https://official.example/notice"),
            Set.of("official.example"), null, null, Duration.ofSeconds(20), 1024);

        URI target = JavaHttpDocumentFetcher.resolveRedirectTarget(request.uri(),
            "http://official.example/file.pdf?download=1", request);

        assertThat(target).isEqualTo(URI.create("https://official.example/file.pdf?download=1"));
        JavaHttpDocumentFetcher.requireAllowed(target, request);
    }

    @Test void doesNotUpgradeHttpRedirectOutsideSourceAllowlist() {
        var request = new FetchRequest(URI.create("https://official.example/notice"),
            Set.of("official.example"), null, null, Duration.ofSeconds(20), 1024);

        URI target = JavaHttpDocumentFetcher.resolveRedirectTarget(request.uri(),
            "http://external.example/file.pdf", request);

        assertThatThrownBy(() -> JavaHttpDocumentFetcher.requireAllowed(target, request))
            .isInstanceOf(FetchRejectedException.class)
            .hasMessageContaining("HTTPS");
    }

    @Test void rejectsNonDefaultPortForOfficialHosts() {
        var request = new FetchRequest(URI.create("https://official.example/notice"),
            Set.of("official.example"), null, null, Duration.ofSeconds(20), 1024);

        assertThatThrownBy(() -> JavaHttpDocumentFetcher.requireAllowed(
            URI.create("https://official.example:8443/file.pdf"), request))
            .isInstanceOf(FetchRejectedException.class)
            .hasMessageContaining("default port");
    }

    @Test void abortsWhenResponseExceedsConfiguredLimit() {
        server.stubFor(get("/large").willReturn(ok().withBody(new byte[1025])));

        assertThatThrownBy(() -> fetcher.fetch(request("/large", null, null, 1024)))
            .isInstanceOf(ResponseTooLargeException.class);
    }

    @Test void retriesRetryableStatusAndThenReturnsSuccessfulBody() {
        server.stubFor(get("/retry").inScenario("retry")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("second"));
        server.stubFor(get("/retry").inScenario("retry")
            .whenScenarioStateIs("second")
            .willReturn(ok().withHeader("Content-Type", "text/html; charset=UTF-8")
                .withBody("<html>招聘公告</html>")));

        var result = fetcher.fetch(request("/retry", null, null, 1024));

        assertThat(new String(result.content(), StandardCharsets.UTF_8)).contains("招聘公告");
        server.verify(2, getRequestedFor(urlEqualTo("/retry")));
        assertThat(sleeper.delays).isNotEmpty();
    }

    @Test void pacesEveryActualHttpAttemptIncludingRetries() {
        server.stubFor(get("/paced-retry").inScenario("paced-retry")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("second"));
        server.stubFor(get("/paced-retry").inScenario("paced-retry")
            .whenScenarioStateIs("second").willReturn(ok("done")));

        fetcher.fetch(pacedRequest("/paced-retry"));

        server.verify(2, getRequestedFor(urlEqualTo("/paced-retry")));
        assertThat(sleeper.delays).anySatisfy(delay ->
            assertThat(delay).isGreaterThanOrEqualTo(Duration.ofMillis(900)));
    }

    @Test void pacesRedirectRequestsAtTheActualSendBoundary() {
        server.stubFor(get("/paced-redirect").willReturn(temporaryRedirect("/paced-final")));
        server.stubFor(get("/paced-final").willReturn(ok("done")));

        fetcher.fetch(pacedRequest("/paced-redirect"));

        assertThat(sleeper.delays).singleElement().satisfies(delay ->
            assertThat(delay).isGreaterThanOrEqualTo(Duration.ofMillis(900)));
    }

    private FetchRequest request(String path, String etag, String lastModified, long maxBytes) {
        URI uri = URI.create(server.baseUrl() + path);
        return new FetchRequest(uri, Set.of("localhost"), etag, lastModified, Duration.ofSeconds(20), maxBytes);
    }

    private FetchRequest pacedRequest(String path) {
        URI uri = URI.create(server.baseUrl() + path);
        return new FetchRequest(uri, Set.of("localhost"), null, null, Duration.ofSeconds(20), 1024,
            UUID.fromString("00000000-0000-0000-0000-000000000001"), Duration.ofSeconds(1));
    }

    private FetchRequest auditedRequest(String path, FetchMethod method) {
        URI uri = URI.create(server.baseUrl() + path);
        return new FetchRequest(uri, Set.of("localhost"), null, null,
            Duration.ofSeconds(20), 1024, null, Duration.ZERO, method,
            new HttpReadContract(TransportPolicy.AUDITED_HTTP_READ_ONLY,
                Set.of("localhost"), Set.of("localhost:" + server.port()), Set.of("/public")));
    }

    private static FetchRequest auditedOfficialRequest(URI uri) {
        return new FetchRequest(uri, Set.of("official.example"), null, null,
            Duration.ofSeconds(20), 1024, null, Duration.ZERO, FetchMethod.GET,
            new HttpReadContract(TransportPolicy.AUDITED_HTTP_READ_ONLY,
                Set.of("official.example"), Set.of("/public")));
    }

    private static final class RecordingSleeper implements JavaHttpDocumentFetcher.Sleeper {
        private final ArrayList<Duration> delays = new ArrayList<>();
        @Override public void sleep(Duration duration) { delays.add(duration); }
    }
}
