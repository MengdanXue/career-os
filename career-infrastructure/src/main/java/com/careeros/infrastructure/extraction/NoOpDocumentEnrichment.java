package com.careeros.infrastructure.extraction;

import com.careeros.application.ExtractionPorts.DocumentEnrichmentPort;
import com.careeros.domain.ParsedDocument;
import com.careeros.domain.SourceArtifact;
import java.util.Objects;

public final class NoOpDocumentEnrichment implements DocumentEnrichmentPort {
    @Override
    public ParsedDocument enrich(SourceArtifact artifact, ParsedDocument parsed) {
        Objects.requireNonNull(artifact, "artifact");
        return Objects.requireNonNull(parsed, "parsed");
    }
}
