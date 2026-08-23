package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquisitionHttpPorts.DocumentFetcher;
import com.careeros.application.AcquisitionHttpPorts.FetchFailedException;
import com.careeros.application.AcquisitionHttpPorts.FetchRejectedException;
import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.FetchedDocument;
import com.careeros.application.AcquisitionHttpPorts.ResponseTooLargeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class JavaHttpDocumentFetcher implements DocumentFetcher {
    private final HttpClient client;
    private final MediaTypeDetector mediaTypes;
    private final Sleeper sleeper;
    private final String userAgent;
    private final int maxRedirects;
    private final int maxRetries;
    private final Clock clock;
    private final Map<String, RequestGate> requestGates = new ConcurrentHashMap<>();

    public JavaHttpDocumentFetcher(
        HttpClient client, MediaTypeDetector mediaTypes, Sleeper sleeper,
        String userAgent, int maxRedirects, int maxRetries
    ) {
        this(client, mediaTypes, sleeper, userAgent, maxRedirects, maxRetries, Clock.systemUTC());
    }

    JavaHttpDocumentFetcher(
        HttpClient client, MediaTypeDetector mediaTypes, Sleeper sleeper,
        String userAgent, int maxRedirects, int maxRetries, Clock clock
    ) {
        this.client=Objects.requireNonNull(client); this.mediaTypes=Objects.requireNonNull(mediaTypes);
        this.sleeper=Objects.requireNonNull(sleeper); this.userAgent=requireText(userAgent);
        this.clock=Objects.requireNonNull(clock);
        if (maxRedirects < 0 || maxRetries < 0) throw new IllegalArgumentException("limits cannot be negative");
        this.maxRedirects=maxRedirects; this.maxRetries=maxRetries;
    }

    @Override
    public FetchedDocument fetch(FetchRequest request) {
        requireAllowed(request.uri(), request);
        URI current = request.uri();
        int redirects = 0;
        int attempt = 0;
        while (true) {
            HttpResponse<InputStream> response;
            try {
                awaitRequestWindow(request);
                response = client.send(buildRequest(current, request), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException exception) {
                if (attempt++ < maxRetries) { sleeper.sleep(backoff(attempt)); continue; }
                throw new FetchFailedException("HTTP request failed for " + current, exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new FetchFailedException("HTTP request interrupted for " + current, exception);
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400 && status != 304) {
                close(response.body());
                if (redirects++ >= maxRedirects) throw new FetchRejectedException("Too many redirects for " + request.uri());
                String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new FetchRejectedException("Redirect has no Location header"));
                current = CanonicalUri.normalize(current.resolve(location));
                requireAllowed(current, request);
                continue;
            }
            if (status == 429 || status >= 500) {
                close(response.body());
                if (attempt++ < maxRetries) {
                    sleeper.sleep(retryDelay(response, attempt));
                    continue;
                }
                throw new FetchFailedException("Retryable HTTP status " + status + " for " + current);
            }
            if (status >= 400 && status != 404 && status != 410) {
                close(response.body());
                throw new FetchRejectedException("HTTP status " + status + " for " + current);
            }
            byte[] content = status == 304 || status == 404 || status == 410
                ? closeAndEmpty(response.body()) : readBounded(response.body(), request.maxBytes());
            String headerType = response.headers().firstValue("Content-Type").orElse(null);
            String mediaType = content.length == 0 ? null : mediaTypes.detect(current, headerType, content);
            return new FetchedDocument(current, status, mediaType, content,
                response.headers().firstValue("ETag").orElse(request.etag()),
                response.headers().firstValue("Last-Modified").orElse(request.lastModified()));
        }
    }

    private void awaitRequestWindow(FetchRequest request) {
        Duration interval = request.minimumRequestInterval();
        if (interval.isZero()) return;
        String key = request.sourceId() == null
            ? request.uri().getHost().toLowerCase(Locale.ROOT)
            : request.sourceId().toString();
        RequestGate gate = requestGates.computeIfAbsent(key, ignored -> new RequestGate());
        synchronized (gate) {
            Instant now = clock.instant();
            if (gate.nextAllowedAt != null && now.isBefore(gate.nextAllowedAt)) {
                sleeper.sleep(Duration.between(now, gate.nextAllowedAt));
                now = clock.instant();
                if (now.isBefore(gate.nextAllowedAt)) now = gate.nextAllowedAt;
            }
            gate.nextAllowedAt = now.plus(interval);
        }
    }

    private HttpRequest buildRequest(URI uri, FetchRequest request) {
        var builder = HttpRequest.newBuilder(uri).GET().timeout(request.requestTimeout())
            .header("User-Agent", userAgent)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "text/html,application/xhtml+xml,application/pdf,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,*/*;q=0.1");
        if (request.etag() != null && !request.etag().isBlank()) builder.header("If-None-Match", request.etag());
        if (request.lastModified() != null && !request.lastModified().isBlank()) builder.header("If-Modified-Since", request.lastModified());
        return builder.build();
    }

    private static void requireAllowed(URI uri, FetchRequest request) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!request.allowedHosts().contains(host)) {
            throw new FetchRejectedException("Host is outside source allowlist: " + host);
        }
    }

    private static byte[] readBounded(InputStream input, long maxBytes) {
        try (input; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                total += read;
                if (total > maxBytes) throw new ResponseTooLargeException("Response exceeds " + maxBytes + " bytes");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new FetchFailedException("Could not read HTTP response", exception);
        }
    }

    private static Duration retryDelay(HttpResponse<?> response, int attempt) {
        return response.headers().firstValue("Retry-After").flatMap(JavaHttpDocumentFetcher::seconds)
            .map(value -> Duration.ofSeconds(Math.min(value, 30))).orElse(backoff(attempt));
    }
    private static java.util.Optional<Long> seconds(String value) {
        try { return java.util.Optional.of(Long.parseLong(value.trim())); }
        catch (NumberFormatException ignored) { return java.util.Optional.empty(); }
    }
    private static Duration backoff(int attempt) { return Duration.ofMillis(Math.min(250L * attempt, 1000)); }
    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("userAgent is required");
        return value;
    }
    private static byte[] closeAndEmpty(InputStream input) { close(input); return new byte[0]; }
    private static void close(InputStream input) { try { input.close(); } catch (IOException ignored) {} }

    @FunctionalInterface public interface Sleeper {
        void sleep(Duration duration);
        static Sleeper threadSleep() {
            return duration -> {
                try { Thread.sleep(duration); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new FetchFailedException("Retry sleep interrupted", exception);
                }
            };
        }
    }

    private static final class RequestGate { Instant nextAllowedAt; }
}
