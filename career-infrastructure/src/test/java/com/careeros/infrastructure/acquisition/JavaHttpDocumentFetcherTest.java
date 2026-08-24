package com.careeros.infrastructure.acquisition;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.AcquisitionHttpPorts.FetchRejectedException;
import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.ResponseTooLargeException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JavaHttpDocumentFetcherTest {
    private WireMockServer server;
    private final RecordingSleeper sleeper = new RecordingSleeper();
    private JavaHttpDocumentFetcher fetcher;

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
        server.stubFor(get("/unit").withHeader("X-Requested-With", equalTo("XMLHttpRequest"))
            .willReturn(okJson("{\"success\":true}")));

        var result = fetcher.fetch(request("/unit", null, null, 1024));

        assertThat(result.status()).isEqualTo(200);
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

    private static final class RecordingSleeper implements JavaHttpDocumentFetcher.Sleeper {
        private final ArrayList<Duration> delays = new ArrayList<>();
        @Override public void sleep(Duration duration) { delays.add(duration); }
    }
}
