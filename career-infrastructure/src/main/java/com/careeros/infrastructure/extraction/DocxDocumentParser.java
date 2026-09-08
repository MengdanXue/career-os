package com.careeros.infrastructure.extraction;

import com.careeros.application.ExtractionPorts.DocumentParser;
import com.careeros.application.ExtractionPorts.ParserDescriptor;
import com.careeros.infrastructure.acquisition.MediaTypeDetector;
import com.careeros.domain.DomainEnums.LocatorType;
import com.careeros.domain.DomainEnums.ParserQuality;
import com.careeros.domain.Evidence;
import com.careeros.domain.EvidenceFragment;
import com.careeros.domain.ParsedDocument;
import com.careeros.domain.SourceArtifact;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Extracts visible paragraphs and table cells from an Office Open XML document. */
public final class DocxDocumentParser implements DocumentParser {
    private static final String MEDIA_TYPE = MediaTypeDetector.DOCX;

    @Override public ParserDescriptor descriptor() { return new ParserDescriptor("poi-docx", "5.4.1"); }

    @Override public boolean supports(String mediaType) { return MEDIA_TYPE.equalsIgnoreCase(mediaType); }

    @Override
    public ParsedDocument parse(SourceArtifact artifact, InputStream input, Evidence evidence) throws IOException {
        byte[] bytes = input.readAllBytes();
        List<EvidenceFragment> fragments = new ArrayList<>();
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            int paragraphIndex = 0;
            for (var paragraph : document.getParagraphs()) {
                String text = normalize(paragraph.getText());
                if (!text.isBlank()) {
                    fragments.add(fragment(evidence, text, Map.of("paragraph", paragraphIndex)));
                }
                paragraphIndex++;
            }
            int tableIndex = 0;
            for (var table : document.getTables()) {
                for (int rowIndex = 0; rowIndex < table.getNumberOfRows(); rowIndex++) {
                    var row = table.getRow(rowIndex);
                    for (int cellIndex = 0; cellIndex < row.getTableCells().size(); cellIndex++) {
                        String text = normalize(row.getCell(cellIndex).getText());
                        if (!text.isBlank()) {
                            fragments.add(fragment(evidence, text,
                                Map.of("table", tableIndex, "row", rowIndex, "cell", cellIndex)));
                        }
                    }
                }
                tableIndex++;
            }
        } catch (IOException | RuntimeException exception) {
            throw new IOException("DOCX_MALFORMED", exception);
        }
        if (fragments.isEmpty()) {
            return new ParsedDocument(descriptor().name(), descriptor().version(),
                ParserQuality.LOW_TEXT_QUALITY, List.of(),
                List.of("DOCX contains no extractable text; manual review is required"));
        }
        return new ParsedDocument(descriptor().name(), descriptor().version(),
            ParserQuality.ACCEPTABLE, fragments, List.of());
    }

    private static EvidenceFragment fragment(Evidence evidence, String text, Map<String, Object> locator) {
        String identity = evidence.id() + "|" + locator + "|" + text;
        return new EvidenceFragment(
            UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)), evidence.id(), LocatorType.DOCX,
            locator, text, JsoupDocumentParser.sha256(text), evidence.capturedAt());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').replace('\u3000', ' ')
            .replaceAll("\\s+", " ").trim();
    }
}
