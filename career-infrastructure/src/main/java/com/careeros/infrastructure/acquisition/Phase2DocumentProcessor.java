package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquiredDocumentProcessor;
import com.careeros.application.ExtractionPorts.SubmitExtractionCommand;
import com.careeros.application.ExtractionService;
import com.careeros.infrastructure.persistence.OfficialExcelImportService;
import com.careeros.infrastructure.persistence.OfficialExcelImportService.ImportCommand;
import java.io.ByteArrayInputStream;
import org.springframework.stereotype.Component;

@Component
public final class Phase2DocumentProcessor implements AcquiredDocumentProcessor {
    private final ExtractionService extractions;
    private final OfficialExcelImportService workbooks;

    public Phase2DocumentProcessor(ExtractionService extractions, OfficialExcelImportService workbooks) {
        this.extractions = java.util.Objects.requireNonNull(extractions);
        this.workbooks = java.util.Objects.requireNonNull(workbooks);
    }

    @Override
    public ProcessingResult process(ProcessDocumentCommand command) {
        try {
            return switch (command.mediaType()) {
                case MediaTypeDetector.HTML, "application/xhtml+xml", MediaTypeDetector.PDF -> extract(command);
                case MediaTypeDetector.XLS, MediaTypeDetector.XLSX -> importWorkbook(command);
                default -> ProcessingResult.unsupported();
            };
        } catch (RuntimeException exception) {
            return ProcessingResult.failed(exception.getClass().getSimpleName());
        } catch (Exception exception) {
            return ProcessingResult.failed(exception.getClass().getSimpleName());
        }
    }

    private ProcessingResult extract(ProcessDocumentCommand command) {
        var result = extractions.submit(new SubmitExtractionCommand(
            command.content(), command.mediaType(), command.documentUri().toString(),
            command.announcementTitle(), command.capturedAt(), null, null, false));
        return ProcessingResult.extracted(result.run().id());
    }

    private ProcessingResult importWorkbook(ProcessDocumentCommand command) throws Exception {
        var result = workbooks.importWorkbook(new ByteArrayInputStream(command.content()), new ImportCommand(
            command.announcementTitle(), command.parentAnnouncementUri().toString(), command.recruitmentYear(),
            command.publishedOn(), command.publishedOn(), command.defaultLocation(), command.eventType()));
        return ProcessingResult.imported(result.recruitmentEventId(), result.inserted(), result.updated(),
            result.unchanged(), result.deactivated());
    }
}
