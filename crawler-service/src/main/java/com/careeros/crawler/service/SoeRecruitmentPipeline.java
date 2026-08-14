package com.careeros.crawler.service;

import com.careeros.crawler.domain.RecruitmentSourceScan;
import com.careeros.crawler.parser.SoeRecruitmentHtmlParser;
import com.careeros.crawler.parser.SoeRecruitmentHtmlParser.AnnouncementDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SoeRecruitmentPipeline {
    private final SoeRecruitmentHtmlParser parser = new SoeRecruitmentHtmlParser();
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public Result runLive(Path schemaPath, Path outputDirectory) throws IOException, InterruptedException {
        OfficialHtmlFetcher fetcher = new OfficialHtmlFetcher();
        OfficialHtmlFetcher.FetchResult capitalPage = fetcher.fetch(SoeRecruitmentHtmlParser.HZ_CAPITAL_CANONICAL);
        RecruitmentSourceScan capital = parser.parseHzCapital(
                capitalPage.html(), capitalPage.requestedUrl(), capitalPage.effectiveUrl(),
                capitalPage.fetchedAt(), capitalPage.sha256()
        );
        List<AnnouncementDetail> details = new ArrayList<>();
        for (RecruitmentSourceScan.Item item : capital.items()) {
            OfficialHtmlFetcher.FetchResult detail = fetcher.fetch(item.detailUrl());
            details.add(parser.parseHzCapitalDetail(detail.html(), detail.effectiveUrl()));
        }
        capital = parser.enrichHzCapitalDetails(capital, details);

        OfficialHtmlFetcher.FetchResult hzfiPage = fetcher.fetch(SoeRecruitmentHtmlParser.HZFI_CANONICAL);
        RecruitmentSourceScan hzfi = parser.parseHzfi(
                hzfiPage.html(), hzfiPage.requestedUrl(), hzfiPage.effectiveUrl(),
                hzfiPage.fetchedAt(), hzfiPage.sha256()
        );
        return write(capital, hzfi, schemaPath, outputDirectory);
    }

    public Result runSnapshots(
            Path s09Html, String s09Url, Path s10Html, String s10Url, Path schemaPath, Path outputDirectory
    ) throws IOException {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String s09 = Files.readString(s09Html);
        String s10 = Files.readString(s10Html);
        RecruitmentSourceScan capital = parser.parseHzCapital(s09, s09Url, s09Url, now, sha256(s09Html));
        RecruitmentSourceScan hzfi = parser.parseHzfi(s10, s10Url, s10Url, now, sha256(s10Html));
        return write(capital, hzfi, schemaPath, outputDirectory);
    }

    private Result write(
            RecruitmentSourceScan capital, RecruitmentSourceScan hzfi, Path schemaPath, Path outputDirectory
    ) throws IOException {
        JsonSchemaContractValidator validator = new JsonSchemaContractValidator(schemaPath);
        validator.validate(mapper.writeValueAsString(capital));
        validator.validate(mapper.writeValueAsString(hzfi));

        Files.createDirectories(outputDirectory);
        Path s09File = outputDirectory.resolve("s09-scan.json");
        Path s10File = outputDirectory.resolve("s10-scan.json");
        Path summaryFile = outputDirectory.resolve("summary.json");
        mapper.writeValue(s09File.toFile(), capital);
        mapper.writeValue(s10File.toFile(), hzfi);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scanned_sources", 2);
        summary.put("content_sources", List.of(capital, hzfi).stream()
                .filter(scan -> scan.state() == RecruitmentSourceScan.State.CONTENT_FOUND).count());
        summary.put("confirmed_empty_sources", List.of(capital, hzfi).stream()
                .filter(scan -> scan.state() == RecruitmentSourceScan.State.EMPTY_CONFIRMED).count());
        summary.put("announcements", capital.metrics().announcementCount());
        summary.put("announced_positions", capital.items().stream()
                .map(RecruitmentSourceScan.Item::announcedPositionCount).filter(value -> value != null)
                .mapToInt(Integer::intValue).sum());
        summary.put("announced_headcount", capital.items().stream()
                .map(RecruitmentSourceScan.Item::announcedHeadcount).filter(value -> value != null)
                .mapToInt(Integer::intValue).sum());
        summary.put("attachments", capital.metrics().attachmentCount());
        summary.put("hzfi_organization_sections", hzfi.metrics().organizationCount());
        summary.put("hzfi_explicit_empty_sections", hzfi.metrics().explicitEmptySectionCount());
        summary.put("schema_validated", true);
        mapper.writeValue(summaryFile.toFile(), summary);
        return new Result(capital, hzfi, s09File, s10File, summaryFile);
    }

    private static String sha256(Path path) throws IOException {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Result(
            RecruitmentSourceScan s09,
            RecruitmentSourceScan s10,
            Path s09File,
            Path s10File,
            Path summaryFile
    ) {}
}
