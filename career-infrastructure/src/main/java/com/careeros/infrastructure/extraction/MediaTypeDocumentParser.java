package com.careeros.infrastructure.extraction;

import com.careeros.application.ExtractionExceptions;
import com.careeros.application.ExtractionPorts.DocumentParser;
import com.careeros.application.ExtractionPorts.ParserDescriptor;
import com.careeros.domain.Evidence;
import com.careeros.domain.ParsedDocument;
import com.careeros.domain.SourceArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

public final class MediaTypeDocumentParser implements DocumentParser {
    private final List<DocumentParser> delegates;

    public MediaTypeDocumentParser(List<DocumentParser> delegates) {
        this.delegates = List.copyOf(Objects.requireNonNull(delegates, "delegates"));
        if (this.delegates.isEmpty()) throw new IllegalArgumentException("At least one parser is required");
    }

    @Override public ParserDescriptor descriptor() {
        return new ParserDescriptor("media-type-router", "1.0.0");
    }

    @Override public boolean supports(String mediaType) {
        return delegates.stream().anyMatch(delegate -> delegate.supports(mediaType));
    }

    @Override
    public ParsedDocument parse(SourceArtifact artifact, InputStream input, Evidence evidence) throws IOException {
        return delegates.stream()
            .filter(delegate -> delegate.supports(artifact.mediaType()))
            .findFirst()
            .orElseThrow(() -> new ExtractionExceptions.UnsupportedDocumentException(
                "Unsupported media type: " + artifact.mediaType()))
            .parse(artifact, input, evidence);
    }
}
