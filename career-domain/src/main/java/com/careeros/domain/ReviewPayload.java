package com.careeros.domain;

import java.util.Map;

public record ReviewPayload(
    Format format,
    RecruitmentExtractionProposal proposal,
    Map<String, Object> legacySummary
) {
    public enum Format { FULL_V1, LEGACY_SUMMARY_V0 }

    public ReviewPayload {
        if (format == null) throw new IllegalArgumentException("format is required");
        legacySummary = legacySummary == null ? Map.of() : Map.copyOf(legacySummary);
        if (format == Format.FULL_V1 && proposal == null) {
            throw new IllegalArgumentException("FULL_V1 review payload requires proposal");
        }
        if (format == Format.LEGACY_SUMMARY_V0 && legacySummary.isEmpty()) {
            throw new IllegalArgumentException("LEGACY_SUMMARY_V0 review payload requires summary");
        }
    }

    public static ReviewPayload full(RecruitmentExtractionProposal proposal) {
        return new ReviewPayload(Format.FULL_V1, proposal, Map.of());
    }

    public static ReviewPayload legacy(Map<String, Object> summary) {
        return new ReviewPayload(Format.LEGACY_SUMMARY_V0, null, summary);
    }
}
