package com.careeros.infrastructure.extraction;

import com.careeros.application.ExtractionPorts.DocumentParser;
import com.careeros.application.ExtractionPorts.ParserDescriptor;
import com.careeros.domain.Evidence;
import com.careeros.domain.EvidenceFragment;
import com.careeros.domain.ParsedDocument;
import com.careeros.domain.SourceArtifact;
import com.careeros.domain.DomainEnums.LocatorType;
import com.careeros.domain.DomainEnums.ParserQuality;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

public final class PdfBoxDocumentParser implements DocumentParser {
    @Override public ParserDescriptor descriptor() { return new ParserDescriptor("pdfbox", "3.0.8"); }

    @Override public boolean supports(String mediaType) {
        return "application/pdf".equalsIgnoreCase(mediaType);
    }

    @Override
    public ParsedDocument parse(SourceArtifact artifact, InputStream input, Evidence evidence) throws IOException {
        byte[] bytes = input.readAllBytes();
        List<EvidenceFragment> fragments = new ArrayList<>();
        long effectiveCharacters = 0;
        long replacementCharacters = 0;
        int pageCount;
        try (var document = Loader.loadPDF(bytes)) {
            pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalize(stripper.getText(document));
                if (text.isBlank()) continue;
                long pageEffective = text.codePoints().filter(value -> !Character.isWhitespace(value)).count();
                long pageReplacement = text.codePoints().filter(value -> value == 0xfffd).count();
                effectiveCharacters += pageEffective;
                replacementCharacters += pageReplacement;
                Map<String, Object> locator = Map.of("page", page);
                String identity = evidence.id() + "|page=" + page + "|" + text;
                fragments.add(new EvidenceFragment(
                    UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                    evidence.id(), LocatorType.PDF, locator, text,
                    JsoupDocumentParser.sha256(text), evidence.capturedAt()));
            }
        }
        boolean nonblank = effectiveCharacters > 0;
        double averageCharacters = pageCount == 0 ? 0 : (double) effectiveCharacters / pageCount;
        double replacementRatio = effectiveCharacters == 0 ? 0 : (double) replacementCharacters / effectiveCharacters;
        ParserQuality quality = nonblank && (averageCharacters < 100 || replacementRatio > 0.02)
            ? ParserQuality.LOW_TEXT_QUALITY
            : ParserQuality.ACCEPTABLE;
        List<String> warnings = quality == ParserQuality.LOW_TEXT_QUALITY
            ? List.of("PDF text extraction quality is below the automatic verification threshold")
            : List.of();
        return new ParsedDocument(descriptor().name(), descriptor().version(), quality, fragments, warnings);
    }

    private static String normalize(String value) {
        return value.replace('\u00a0', ' ')
            .replace('\u3000', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("[\\t\\x0B\\f ]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }
}
