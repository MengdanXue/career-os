package com.careeros.crawler.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

public final class OfficialFileDownloader {
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public DownloadResult download(String url, Path destination) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "CareerOS-RecruitmentCollector/0.1 (+read-only official-source scanner)")
                .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        byte[] bytes = response.body();
        if (bytes.length == 0) throw new IOException("Downloaded an empty file from " + url);
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Files.write(destination, bytes);
        return new DownloadResult(url, response.uri().toString(), destination, bytes.length, sha256(bytes));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record DownloadResult(
            String requestedUrl,
            String effectiveUrl,
            Path file,
            int sizeBytes,
            String sha256
    ) {}
}
