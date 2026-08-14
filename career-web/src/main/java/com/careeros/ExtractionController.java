package com.careeros;

import static com.careeros.ExtractionApiModels.*;

import com.careeros.application.ExtractionExceptions;
import com.careeros.application.ExtractionPorts.SubmitExtractionCommand;
import com.careeros.application.ExtractionService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/extractions")
final class ExtractionController {
    private final ExtractionService service;
    private final long maxDocumentBytes;

    ExtractionController(
        ExtractionService service,
        @Value("${career-os.extraction.max-document-bytes:26214400}") long maxDocumentBytes
    ) {
        this.service = service;
        this.maxDocumentBytes = maxDocumentBytes;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ExtractionResponse> submit(
        @RequestPart("document") MultipartFile document,
        @RequestPart("metadata") ExtractionMetadataRequest metadata
    ) throws Exception {
        byte[] content = document.getBytes();
        if (content.length > maxDocumentBytes) {
            throw new ExtractionExceptions.DocumentTooLargeException(
                "Document exceeds " + maxDocumentBytes + " bytes");
        }
        String mediaType = document.getContentType();
        validateSignature(mediaType, content);
        var result = service.submit(new SubmitExtractionCommand(
            content, mediaType, metadata.sourceUrl(), metadata.sourceTitle(), metadata.capturedAt(),
            metadata.organizationId(), metadata.recruitmentEventId(), metadata.requireModel()));
        var response = ExtractionResponse.from(result);
        URI location = URI.create("/api/v1/extractions/" + response.id());
        return ResponseEntity.status(result.reused() ? HttpStatus.OK : HttpStatus.CREATED)
            .location(location)
            .body(response);
    }

    @GetMapping("/{id}")
    ExtractionResponse find(@PathVariable("id") java.util.UUID id) {
        return ExtractionResponse.from(service.find(id));
    }

    private static void validateSignature(String mediaType, byte[] content) {
        if ("application/pdf".equalsIgnoreCase(mediaType)) {
            if (!startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
                throw unsupported("PDF signature does not match its media type");
            }
            return;
        }
        if ("text/html".equalsIgnoreCase(mediaType)
            || "application/xhtml+xml".equalsIgnoreCase(mediaType)) {
            String prefix = new String(content, 0, Math.min(content.length, 256), StandardCharsets.UTF_8)
                .stripLeading().toLowerCase(Locale.ROOT);
            if (!prefix.startsWith("<")) {
                throw unsupported("HTML signature does not match its media type");
            }
            return;
        }
        throw unsupported("Unsupported media type: " + mediaType);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    private static ExtractionExceptions.UnsupportedDocumentException unsupported(String message) {
        return new ExtractionExceptions.UnsupportedDocumentException(message);
    }
}
