package com.careeros.domain;

import com.careeros.domain.DomainEnums.ParserQuality;
import java.util.List;
import java.util.Objects;

public record ParsedDocument(
    String parserName,
    String parserVersion,
    ParserQuality quality,
    List<EvidenceFragment> fragments,
    List<String> warnings
) {
    public ParsedDocument {
        requireText(parserName, "parserName");
        requireText(parserVersion, "parserVersion");
        Objects.requireNonNull(quality, "quality");
        fragments = fragments == null ? List.of() : List.copyOf(fragments);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
