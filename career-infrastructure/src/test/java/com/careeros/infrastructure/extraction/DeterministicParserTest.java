package com.careeros.infrastructure.extraction;

import static com.careeros.domain.DomainEnums.EvidenceType.OFFICIAL_NOTICE;
import static com.careeros.domain.DomainEnums.ParserQuality.ACCEPTABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.ExtractionExceptions;
import com.careeros.domain.Evidence;
import com.careeros.domain.EvidenceFragment;
import com.careeros.domain.ParsedDocument;
import com.careeros.domain.SourceArtifact;
import com.careeros.infrastructure.artifact.FileSystemArtifactStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeterministicParserTest {
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-14T10:00:00Z");

    @TempDir Path temporaryDirectory;

    @Test
    void artifactStoreUsesContentAddressAndDeduplicatesIdenticalBytes() throws Exception {
        FileSystemArtifactStore store = new FileSystemArtifactStore(temporaryDirectory);
        byte[] content = "<h1>招聘公告</h1>".getBytes(StandardCharsets.UTF_8);

        SourceArtifact first = store.put(content, "text/html", CAPTURED_AT);
        SourceArtifact second = store.put(content, "text/html", CAPTURED_AT.plusSeconds(60));

        assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(second.storageUri()).isEqualTo(first.storageUri());
        assertThat(Path.of(first.storageUri())).exists();
        assertThat(store.open(first).readAllBytes()).isEqualTo(content);
        assertThat(Files.walk(temporaryDirectory).filter(Files::isRegularFile)).hasSize(1);
    }

    @Test
    void htmlFragmentsRetainSelectorsAndDropScripts() throws Exception {
        ParsedDocument parsed = parseHtml(
            "<html><body><h1>招聘公告</h1><script>ignore()</script>"
                + "<table><tr><td>信息中心</td></tr></table></body></html>");

        assertThat(parsed.fragments()).extracting(EvidenceFragment::verbatimText)
            .contains("招聘公告", "信息中心");
        assertThat(parsed.fragments()).noneMatch(fragment -> fragment.verbatimText().contains("ignore"));
        assertThat(parsed.fragments()).allMatch(fragment -> fragment.locator().containsKey("cssSelector"));
    }

    @Test
    void realPolicyPdfIsSuccessfulAndPageAddressable() throws Exception {
        ParsedDocument parsed = parseFixture("08-zj-2025-applicant-guide.pdf", "application/pdf");

        assertThat(parsed.quality()).isEqualTo(ACCEPTABLE);
        assertThat(parsed.fragments()).isNotEmpty();
        assertThat(parsed.fragments()).allMatch(fragment -> fragment.locator().containsKey("page"));
        assertThat(parsed.fragments()).extracting(EvidenceFragment::verbatimText)
            .anyMatch(text -> text.contains("应聘") || text.contains("报名"));
    }

    @Test
    void historicalOfficialHtmlFixturesRemainParseable() throws Exception {
        ParsedDocument capital = parseFixture("09-hz-capital-recruiting.html", "text/html");
        ParsedDocument finance = parseFixture("10-hzfi-social-recruiting.html", "text/html");

        assertThat(capital.fragments()).isNotEmpty();
        assertThat(finance.fragments()).isNotEmpty();
        assertThat(capital.fragments()).noneMatch(fragment -> fragment.verbatimText().contains("function("));
        assertThat(finance.fragments()).noneMatch(fragment -> fragment.verbatimText().contains("function("));
    }

    @Test
    void mediaRouterRejectsUnsupportedDocuments() {
        MediaTypeDocumentParser router = new MediaTypeDocumentParser(
            List.of(new JsoupDocumentParser(), new PdfBoxDocumentParser()));

        assertThatThrownBy(() -> router.parse(
            artifact("application/zip", 1), new ByteArrayInputStream(new byte[] {1}),
            evidence(artifact("application/zip", 1))))
            .isInstanceOf(ExtractionExceptions.UnsupportedDocumentException.class)
            .hasMessageContaining("application/zip");
    }

    private ParsedDocument parseHtml(String html) throws Exception {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        SourceArtifact artifact = artifact("text/html", bytes.length);
        return new JsoupDocumentParser().parse(
            artifact, new ByteArrayInputStream(bytes), evidence(artifact));
    }

    private ParsedDocument parseFixture(String name, String mediaType) throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/fixtures/extraction/" + name)) {
            assertThat(input).as("fixture %s", name).isNotNull();
            bytes = input.readAllBytes();
        }
        SourceArtifact artifact = artifact(mediaType, bytes.length);
        MediaTypeDocumentParser router = new MediaTypeDocumentParser(
            List.of(new JsoupDocumentParser(), new PdfBoxDocumentParser()));
        return router.parse(artifact, new ByteArrayInputStream(bytes), evidence(artifact));
    }

    private static SourceArtifact artifact(String mediaType, long size) {
        return new SourceArtifact(
            UUID.randomUUID(), "fixture-sha256", mediaType, size,
            "memory://fixture", CAPTURED_AT);
    }

    private static Evidence evidence(SourceArtifact artifact) {
        return new Evidence(
            UUID.randomUUID(), artifact.id(), OFFICIAL_NOTICE,
            "https://example.gov.cn/notice", "招聘公告", null,
            artifact.sha256(), CAPTURED_AT);
    }
}
