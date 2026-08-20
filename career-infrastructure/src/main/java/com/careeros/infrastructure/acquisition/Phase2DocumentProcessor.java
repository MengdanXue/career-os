package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquiredDocumentProcessor;
import com.careeros.application.ExtractionPorts.SubmitExtractionCommand;
import com.careeros.application.ExtractionService;
import com.careeros.infrastructure.persistence.OfficialExcelImportService;
import com.careeros.infrastructure.persistence.OfficialExcelImportService.ImportCommand;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class Phase2DocumentProcessor implements AcquiredDocumentProcessor {
    private static final Pattern ANNOUNCEMENT_YEAR = Pattern.compile("20\\d{2}年");
    private static final Pattern ORGANIZATION_SUFFIX = Pattern.compile(
        ".*(中心|医院|大学|学院|学校|研究院|研究所|集团|公司|协会|图书馆|博物馆|艺术馆|乐团|运动队)$");
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
        if (isNonJobWorkbook(command.documentUri())) {
            return ProcessingResult.ignored("NON_JOB_WORKBOOK");
        }
        if (isNonTargetWorkbook(command.documentUri())) {
            return ProcessingResult.ignored("NON_TARGET_WORKBOOK");
        }
        var result = workbooks.importWorkbook(new ByteArrayInputStream(command.content()), new ImportCommand(
            command.announcementTitle(), command.parentAnnouncementUri().toString(), command.recruitmentYear(),
            command.publishedOn(), command.publishedOn(), command.defaultLocation(), command.eventType(),
            defaultOrganizationName(command.announcementTitle()), command.documentUri().toString()));
        return ProcessingResult.imported(result.recruitmentEventId(), result.inserted(), result.updated(),
            result.unchanged(), result.deactivated());
    }

    private static boolean isNonJobWorkbook(java.net.URI uri) {
        String name = fileName(uri);
        return name.contains("报名表") || name.contains("应聘表") || name.contains("申请表");
    }

    private static boolean isNonTargetWorkbook(java.net.URI uri) {
        String name = fileName(uri);
        return name.contains("教学科研人员") || name.contains("教师岗招聘计划");
    }

    private static String fileName(java.net.URI uri) {
        String query = uri.getRawQuery();
        if (query == null) return uri.getPath();
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && "fileName".equalsIgnoreCase(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8))) {
                return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return uri.getPath();
    }

    static String defaultOrganizationName(String announcementTitle) {
        String title = announcementTitle == null ? "" : announcementTitle.strip()
            .replaceAll("^[《〈『「]", "")
            .replaceFirst("^20\\d{2}年", "");
        var year = ANNOUNCEMENT_YEAR.matcher(title);
        int end = year.find() ? year.start() : firstMarker(title, "公开招聘", "人才引进", "招聘");
        if (end <= 0) return null;
        String candidate = title.substring(0, end)
            .replaceFirst("^关于", "")
            .replaceAll("^[附件一二三四五六七八九十0-9.：:、\\-]+", "")
            .replaceAll("[（(]?招聘[）)]?$", "")
            .replaceFirst("关于$", "")
            .strip();
        int alternateName = Math.min(
            positiveOrLength(candidate.indexOf('（'), candidate.length()),
            positiveOrLength(candidate.indexOf('('), candidate.length()));
        candidate = candidate.substring(0, alternateName).strip();
        return candidate.length() >= 2 && candidate.length() <= 180
            && ORGANIZATION_SUFFIX.matcher(candidate).matches() ? candidate : null;
    }

    private static int firstMarker(String value, String... markers) {
        int result = -1;
        for (String marker : markers) {
            int index = value.indexOf(marker);
            if (index >= 0 && (result < 0 || index < result)) result = index;
        }
        return result;
    }

    private static int positiveOrLength(int value, int length) {
        return value < 0 ? length : value;
    }
}
