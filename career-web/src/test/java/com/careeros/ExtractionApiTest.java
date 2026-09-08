package com.careeros;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.ExtractionExceptions;
import com.careeros.application.ExtractionPorts.ExtractionResult;
import com.careeros.application.ExtractionService;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ExtractionController.class)
@TestPropertySource(properties = "career-os.extraction.max-document-bytes=1024")
@Import(SecurityConfiguration.class)
@WithMockUser(username = "reviewer-a", roles = "REVIEWER")
class ExtractionApiTest {
    @Autowired MockMvc mvc;
    @MockBean ExtractionService service;

    @Test
    void firstUploadReturnsCreatedAndLocation() throws Exception {
        when(service.submit(any())).thenReturn(new ExtractionResult(
            ApiTestFixtures.run(), Optional.of(ApiTestFixtures.REVIEW_ID), false));

        mvc.perform(multipart("/api/v1/extractions")
                .file(html())
                .file(metadata()))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/extractions/" + ApiTestFixtures.RUN_ID))
            .andExpect(jsonPath("$.id").value(ApiTestFixtures.RUN_ID.toString()))
            .andExpect(jsonPath("$.reused").value(false))
            .andExpect(jsonPath("$.reviewId").value(ApiTestFixtures.REVIEW_ID.toString()));
    }

    @Test
    void duplicateUploadReturnsOkAndReused() throws Exception {
        when(service.submit(any())).thenReturn(new ExtractionResult(
            ApiTestFixtures.run(), Optional.of(ApiTestFixtures.REVIEW_ID), true));

        mvc.perform(multipart("/api/v1/extractions").file(html()).file(metadata()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reused").value(true));
    }

    @Test
    void unsupportedMediaAndOversizedFilesUseSpecificProblemStatuses() throws Exception {
        var text = new MockMultipartFile("document", "notice.txt", "text/plain", "plain".getBytes());
        mvc.perform(multipart("/api/v1/extractions").file(text).file(metadata()))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT"));

        var oversized = new MockMultipartFile("document", "notice.html", "text/html", new byte[1025]);
        mvc.perform(multipart("/api/v1/extractions").file(oversized).file(metadata()))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("DOCUMENT_TOO_LARGE"));
    }

    @Test
    void extractionCanBeReadAndMissingIdReturnsProblemDetail() throws Exception {
        when(service.find(ApiTestFixtures.RUN_ID)).thenReturn(ApiTestFixtures.persisted());
        mvc.perform(get("/api/v1/extractions/{id}", ApiTestFixtures.RUN_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));

        var missing = java.util.UUID.randomUUID();
        when(service.find(missing)).thenThrow(new ExtractionExceptions.ExtractionNotFoundException("missing"));
        mvc.perform(get("/api/v1/extractions/{id}", missing))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("EXTRACTION_NOT_FOUND"));
    }

    private static MockMultipartFile html() {
        return new MockMultipartFile(
            "document", "notice.html", "text/html",
            "<html><body><h1>公开招聘公告</h1></body></html>".getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile metadata() {
        return new MockMultipartFile(
            "metadata", "metadata.json", "application/json", """
                {"sourceUrl":"https://example.gov.cn/notice/1","sourceTitle":"公开招聘公告",
                 "capturedAt":"2026-08-14T14:00:00Z","requireModel":false}
                """.getBytes(StandardCharsets.UTF_8));
    }
}
