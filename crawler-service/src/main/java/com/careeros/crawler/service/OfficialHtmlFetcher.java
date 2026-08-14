package com.careeros.crawler.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OfficialHtmlFetcher {
    private static final Pattern CHARSET = Pattern.compile("charset=([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public FetchResult fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .header("User-Agent", "CareerOS-RecruitmentCollector/0.1 (+read-only official-source scanner)")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        byte[] bytes = response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        Charset charset = charset(contentType);
        return new FetchResult(
                url, response.uri().toString(), response.statusCode(), OffsetDateTime.now(ZoneOffset.UTC),
                new String(bytes, charset), sha256(bytes)
        );
    }

    private static Charset charset(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (Exception ignored) {
                // Fall through to UTF-8. Both target sources currently publish UTF-8 HTML.
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record FetchResult(
            String requestedUrl,
            String effectiveUrl,
            int statusCode,
            OffsetDateTime fetchedAt,
            String html,
            String sha256
    ) {}
}
