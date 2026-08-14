package com.careeros.crawler.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.time.OffsetDateTime;
import java.util.List;

public record RecruitmentSourceScan(
        String schemaVersion,
        String sourceId,
        String sourceName,
        String requestedUrl,
        String canonicalUrl,
        String effectiveUrl,
        OffsetDateTime scannedAt,
        State state,
        String pageTitle,
        String sourceSha256,
        Metrics metrics,
        List<Item> items,
        List<OrganizationSection> organizations,
        List<Evidence> evidence,
        List<String> warnings
) {
    public RecruitmentSourceScan {
        items = List.copyOf(items);
        organizations = List.copyOf(organizations);
        evidence = List.copyOf(evidence);
        warnings = List.copyOf(warnings);
    }

    public enum State {
        CONTENT_FOUND("content_found"),
        EMPTY_CONFIRMED("empty_confirmed"),
        EMPTY_INFERRED("empty_inferred"),
        FETCH_ERROR("fetch_error");

        private final String value;

        State(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    public record Metrics(
            int announcementCount,
            int organizationCount,
            int jobSectionCount,
            int explicitEmptySectionCount,
            int attachmentCount
    ) {}

    public record Item(
            String itemId,
            String itemType,
            String title,
            String publishedAt,
            String detailUrl,
            Integer announcedPositionCount,
            Integer announcedHeadcount,
            List<Attachment> attachments
    ) {
        public Item {
            attachments = List.copyOf(attachments);
        }
    }

    public record Attachment(String title, String url, String format) {}

    public record OrganizationSection(
            String name,
            String sectionId,
            State state,
            String address,
            String phone,
            String email
    ) {}

    public record Evidence(String kind, String selector, int observedCount, String message) {}
}
