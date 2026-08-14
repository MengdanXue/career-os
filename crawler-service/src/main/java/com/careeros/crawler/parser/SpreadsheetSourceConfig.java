package com.careeros.crawler.parser;

public record SpreadsheetSourceConfig(
        String sampleId,
        String announcementTitle,
        String announcementUrl,
        String attachmentUrl,
        String publishedAt,
        String defaultEmployer,
        String organizationType,
        String employmentType
) {}
