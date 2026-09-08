package com.careeros.infrastructure.extraction;

import static com.careeros.domain.DomainEnums.EvidenceType.OFFICIAL_ATTACHMENT;
import static com.careeros.domain.DomainEnums.LocatorType.DOCX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.domain.Evidence;
import com.careeros.domain.EvidenceFragment;
import com.careeros.domain.SourceArtifact;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocxDocumentParserTest {
    private static final Instant CAPTURED_AT = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void extractsParagraphsAndTablesWithStableDocxLocators() throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("2026年公开招聘公告");
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("岗位名称");
            table.getRow(0).getCell(1).setText("信息工作人员");
            document.write(output);
            bytes = output.toByteArray();
        }

        var artifact = artifact(bytes.length);
        var evidence = evidence(artifact);
        var parsed = new DocxDocumentParser().parse(artifact, new ByteArrayInputStream(bytes), evidence);

        assertThat(parsed.fragments()).extracting(EvidenceFragment::verbatimText)
            .contains("2026年公开招聘公告", "岗位名称", "信息工作人员");
        assertThat(parsed.fragments()).allMatch(fragment -> fragment.locatorType() == DOCX);
        assertThat(parsed.fragments()).allMatch(fragment -> fragment.locator().containsKey("paragraph")
            || fragment.locator().containsKey("table"));
    }

    @Test
    void malformedDocxFailsExplicitlyInsteadOfReturningZeroFragments() {
        var artifact = artifact(4);
        assertThatThrownBy(() -> new DocxDocumentParser().parse(
            artifact, new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), evidence(artifact)))
            .isInstanceOfAny(java.io.IOException.class, RuntimeException.class);
    }

    private static SourceArtifact artifact(long size) {
        return new SourceArtifact(UUID.randomUUID(), "a".repeat(64),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", size,
            "memory://docx", CAPTURED_AT);
    }

    private static Evidence evidence(SourceArtifact artifact) {
        return new Evidence(UUID.randomUUID(), artifact.id(), OFFICIAL_ATTACHMENT,
            "https://example.gov.cn/files/jobs.docx", "岗位附件", null,
            artifact.sha256(), CAPTURED_AT);
    }
}
