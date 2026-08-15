package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.careeros.application.AcquiredDocumentProcessor.ProcessDocumentCommand;
import com.careeros.application.AcquiredDocumentProcessor.ProcessingStatus;
import com.careeros.application.ExtractionPorts.ExtractionResult;
import com.careeros.application.ExtractionService;
import com.careeros.domain.DomainEnums.EventType;
import com.careeros.domain.ExtractionRun;
import com.careeros.infrastructure.persistence.OfficialExcelImportService;
import com.careeros.infrastructure.persistence.OfficialExcelImportService.ImportResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Phase2DocumentProcessorTest {
    private static final UUID RUN_ID = UUID.fromString("01992f09-0000-7000-8000-000000000701");
    private final ExtractionService extractions = mock(ExtractionService.class);
    private final OfficialExcelImportService workbooks = mock(OfficialExcelImportService.class);
    private final Phase2DocumentProcessor processor = new Phase2DocumentProcessor(extractions, workbooks);

    @Test
    void htmlUsesExtractionPipelineAndReturnsItsRunId() {
        ExtractionRun run = mock(ExtractionRun.class);
        when(run.id()).thenReturn(RUN_ID);
        when(extractions.submit(any())).thenReturn(new ExtractionResult(run, Optional.empty(), false));

        var result = processor.process(command("text/html", "<html>招聘</html>".getBytes(StandardCharsets.UTF_8)));

        assertThat(result.status()).isEqualTo(ProcessingStatus.PROCESSED);
        assertThat(result.extractionRunId()).isEqualTo(RUN_ID);
        assertThat(result.inserted()).isZero();
    }

    @Test
    void xlsxUsesOfficialWorkbookPipelineAndReturnsJobDeltas() throws Exception {
        UUID eventId = UUID.fromString("01992f09-0000-7000-8000-000000000702");
        when(workbooks.importWorkbook(any(), any())).thenReturn(
            new ImportResult(eventId, 2, 1, 3, 1, List.of()));

        var result = processor.process(command(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {'P','K',3,4}));

        assertThat(result.status()).isEqualTo(ProcessingStatus.PROCESSED);
        assertThat(result.recruitmentEventId()).isEqualTo(eventId);
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.deactivated()).isEqualTo(1);
    }

    @Test
    void unsupportedMediaReturnsFailureWithoutJobData() {
        var result = processor.process(command("application/zip", new byte[] {'P','K'}));

        assertThat(result.status()).isEqualTo(ProcessingStatus.UNSUPPORTED);
        assertThat(result.inserted()).isZero();
        assertThat(result.errorCode()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    private static ProcessDocumentCommand command(String mediaType, byte[] content) {
        return new ProcessDocumentCommand(content, mediaType,
            URI.create("https://rlsbt.zj.gov.cn/files/jobs.xlsx"),
            URI.create("https://rlsbt.zj.gov.cn/art/2026/3/17/notice.html"),
            "2026年事业单位公开招聘", Instant.parse("2026-08-15T00:00:00Z"),
            2026, LocalDate.of(2026, 3, 17), "浙江杭州", EventType.PUBLIC_INSTITUTION);
    }
}
