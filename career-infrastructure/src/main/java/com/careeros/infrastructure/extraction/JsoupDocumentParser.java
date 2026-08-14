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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

public final class JsoupDocumentParser implements DocumentParser {
    private static final String VISIBLE_ELEMENTS = "h1,h2,h3,h4,p,li,tr";
    private static final String REMOVED_ELEMENTS = "script,style,noscript,nav,footer,header,iframe";

    @Override public ParserDescriptor descriptor() { return new ParserDescriptor("jsoup", "1.22.2"); }

    @Override public boolean supports(String mediaType) {
        return "text/html".equalsIgnoreCase(mediaType) || "application/xhtml+xml".equalsIgnoreCase(mediaType);
    }

    @Override
    public ParsedDocument parse(SourceArtifact artifact, InputStream input, Evidence evidence) throws IOException {
        var document = Jsoup.parse(input, null, evidence.sourceUrl());
        document.select(REMOVED_ELEMENTS).remove();
        List<EvidenceFragment> fragments = new ArrayList<>();
        int index = 0;
        for (Element element : document.select(VISIBLE_ELEMENTS)) {
            String text = normalize(element.text());
            if (text.isBlank()) continue;
            Map<String, Object> locator = Map.of(
                "cssSelector", element.cssSelector(),
                "tag", element.tagName(),
                "index", index++);
            String identity = evidence.id() + "|" + locator.get("cssSelector") + "|" + text;
            fragments.add(new EvidenceFragment(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                evidence.id(), LocatorType.HTML, locator, text, sha256(text), evidence.capturedAt()));
        }
        return new ParsedDocument(descriptor().name(), descriptor().version(),
            ParserQuality.ACCEPTABLE, fragments, List.of());
    }

    private static String normalize(String value) {
        return value.replace('\u00a0', ' ').replace('\u3000', ' ').replaceAll("\\s+", " ").trim();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
