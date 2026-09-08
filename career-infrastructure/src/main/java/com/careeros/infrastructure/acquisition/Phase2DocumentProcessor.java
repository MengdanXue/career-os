package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquiredDocumentProcessor;
import com.careeros.application.AcquiredDocumentProcessor.ProcessingStatus;
import com.careeros.application.ExtractionPorts.SubmitExtractionCommand;
import com.careeros.application.ExtractionService;
import com.careeros.domain.RecruitmentLifecycle;
import com.careeros.infrastructure.persistence.OfficialExcelImportService;
import com.careeros.infrastructure.persistence.OfficialExcelImportService.ImportCommand;
import com.careeros.infrastructure.persistence.OfficialAnnouncementFactService;
import com.careeros.infrastructure.persistence.HospitalOfficialJobImportService;
import com.careeros.infrastructure.persistence.OfficialLifecycleDocumentService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class Phase2DocumentProcessor implements AcquiredDocumentProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(Phase2DocumentProcessor.class);
    public static final String PROCESSOR_VERSION = "official-fact-fusion-v16";
    static final int MAX_ARCHIVE_ENTRIES = 32;
    static final long MAX_ARCHIVE_ENTRY_BYTES = 10 * 1024 * 1024;
    static final long MAX_ARCHIVE_TOTAL_BYTES = 25 * 1024 * 1024;
    private static final Pattern ANNOUNCEMENT_YEAR = Pattern.compile("20\\d{2}年");
    private static final Pattern ORGANIZATION_SUFFIX = Pattern.compile(
        ".*(中心|医院|大学|学院|学校|中学|研究院|研究所|集团|公司|协会|图书馆|博物馆|艺术馆|乐团|运动队|厅|局|委员会|院|所|站|馆|社|室)$");
    private final ExtractionService extractions;
    private final OfficialExcelImportService workbooks;
    private final OfficialAnnouncementFactService announcementFacts;
    private final HospitalOfficialJobImportService hospitalJobs;
    private final OfficialLifecycleDocumentService lifecycleDocuments;
    private final MediaTypeDetector mediaTypes = new MediaTypeDetector();
    private final OfficialAnnouncementFactParser announcementParser = new OfficialAnnouncementFactParser();
    private final HospitalOfficialPageParser hospitalParser = new HospitalOfficialPageParser();

    public Phase2DocumentProcessor(
        ExtractionService extractions,
        OfficialExcelImportService workbooks,
        OfficialAnnouncementFactService announcementFacts
    ) {
        this(extractions, workbooks, announcementFacts, null, null);
    }

    public Phase2DocumentProcessor(
        ExtractionService extractions,
        OfficialExcelImportService workbooks,
        OfficialAnnouncementFactService announcementFacts,
        @org.springframework.lang.Nullable HospitalOfficialJobImportService hospitalJobs
    ) {
        this(extractions, workbooks, announcementFacts, hospitalJobs, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public Phase2DocumentProcessor(
        ExtractionService extractions,
        OfficialExcelImportService workbooks,
        OfficialAnnouncementFactService announcementFacts,
        @org.springframework.lang.Nullable HospitalOfficialJobImportService hospitalJobs,
        @org.springframework.lang.Nullable OfficialLifecycleDocumentService lifecycleDocuments
    ) {
        this.extractions = java.util.Objects.requireNonNull(extractions);
        this.workbooks = java.util.Objects.requireNonNull(workbooks);
        this.announcementFacts = java.util.Objects.requireNonNull(announcementFacts);
        this.hospitalJobs = hospitalJobs;
        this.lifecycleDocuments = lifecycleDocuments;
    }

    @Override
    public String version() {
        return PROCESSOR_VERSION;
    }

    @Override
    public ProcessingResult process(ProcessDocumentCommand command) {
        try {
            return switch (command.mediaType()) {
                case MediaTypeDetector.HTML, "application/xhtml+xml", MediaTypeDetector.PDF,
                    MediaTypeDetector.DOCX -> extract(command);
                case MediaTypeDetector.XLS, MediaTypeDetector.XLSX -> importWorkbook(command);
                case MediaTypeDetector.ZIP -> importArchive(command);
                case MediaTypeDetector.PNG, MediaTypeDetector.JPEG -> ProcessingResult.ocrRequired();
                default -> ProcessingResult.unsupported();
            };
        } catch (RuntimeException exception) {
            LOG.warn("Document processing failed uri={} mediaType={}",
                command.documentUri(), command.mediaType(), exception);
            return ProcessingResult.failed(exception.getClass().getSimpleName());
        } catch (Exception exception) {
            LOG.warn("Document processing failed uri={} mediaType={}",
                command.documentUri(), command.mediaType(), exception);
            return ProcessingResult.failed(exception.getClass().getSimpleName());
        }
    }

    private ProcessingResult extract(ProcessDocumentCommand command) {
        var result = extractions.submit(new SubmitExtractionCommand(
            command.content(), command.mediaType(), command.documentUri().toString(),
            command.announcementTitle(), command.capturedAt(), null, null, false));
        if (lifecycleDocuments != null) {
            var lifecycle = lifecycleDocuments.recordIfLifecycle(
                command.announcementTitle(), command.parentAnnouncementUri().toString(),
                command.recruitmentYear(), command.publishedOn(), result.run().evidenceId());
            if (lifecycle.isPresent()) {
                UUID matchedEventId = lifecycle.orElseThrow().matchedEventId();
                return matchedEventId == null
                    ? ProcessingResult.extracted(result.run().id())
                    : ProcessingResult.imported(matchedEventId, 0, 0, 0, 0);
            }
        }
        if (MediaTypeDetector.HTML.equals(command.mediaType()) || "application/xhtml+xml".equals(command.mediaType())) {
            var parsed = announcementParser.parse(
                new String(command.content(), StandardCharsets.UTF_8), command.parentAnnouncementUri().toString());
            var event = announcementFacts.upsert(
                command.announcementTitle(), command.parentAnnouncementUri().toString(), command.recruitmentYear(),
                command.eventType(), parsed, result.run().evidenceId());
            if (hospitalJobs != null && hospitalParser.supports(command.documentUri())) {
                var hospital = hospitalParser.parse(command.documentUri(), command.content());
                HospitalOfficialJobImportService.ImportResult imported = null;
                if (!hospital.jobs().isEmpty()) {
                    imported = hospitalJobs.importAnnouncement(
                        command.parentAnnouncementUri().toString(), hospital);
                }
                var issues = new java.util.ArrayList<>(hospital.issues().stream().map(issue -> new ProcessingIssue(
                    com.careeros.domain.acquisition.ArtifactImportFailure.FailureStage.ROW_PARSE_FAILED,
                    "网页岗位表", issue.rowNumber(), issue.errorCode(), issue.message())).toList());
                if (imported != null) {
                    imported.warnings().forEach(warning -> issues.add(new ProcessingIssue(
                        warning.row() == null
                            ? com.careeros.domain.acquisition.ArtifactImportFailure.FailureStage.NORMALIZATION_FAILED
                            : com.careeros.domain.acquisition.ArtifactImportFailure.FailureStage.ROW_PARSE_FAILED,
                        "网页岗位表", warning.row(), "ELIGIBILITY_NEEDS_REVIEW",
                        warning.field() + "：" + warning.rawValue() + "；" + warning.message())));
                }
                if (!issues.isEmpty()) {
                    return ProcessingResult.importedWithErrors(
                        imported == null ? event.id() : imported.recruitmentEventId(),
                        imported == null ? 0 : imported.inserted(),
                        imported == null ? 0 : imported.updated(),
                        imported == null ? 0 : imported.unchanged(),
                        imported == null ? 0 : imported.deactivated(), issues);
                }
                if (imported != null) {
                    return ProcessingResult.imported(imported.recruitmentEventId(), imported.inserted(),
                        imported.updated(), imported.unchanged(), imported.deactivated());
                }
            }
        }
        return ProcessingResult.extracted(result.run().id());
    }

    private ProcessingResult importWorkbook(ProcessDocumentCommand command) throws Exception {
        if (!RecruitmentLifecycle.classify(command.announcementTitle()).isEmpty()) {
            return ProcessingResult.ignored("LIFECYCLE_ATTACHMENT");
        }
        if (isNonJobWorkbook(command.documentUri())) {
            return ProcessingResult.ignored("NON_JOB_WORKBOOK");
        }
        if (isNonTargetWorkbook(command.documentUri())) {
            return ProcessingResult.ignored("NON_TARGET_WORKBOOK");
        }
        String parentOrganization = defaultOrganizationName(command.announcementTitle());
        String attachmentOrganization = defaultOrganizationName(fileName(command.documentUri()));
        if (differentOrganizations(parentOrganization, attachmentOrganization)) {
            return ProcessingResult.ignored("ATTACHMENT_PARENT_MISMATCH");
        }
        OfficialExcelImportService.ImportResult result;
        try {
            result = workbooks.importWorkbook(new ByteArrayInputStream(command.content()), new ImportCommand(
                command.announcementTitle(), command.parentAnnouncementUri().toString(), command.recruitmentYear(),
                command.publishedOn(), null, command.defaultLocation(), command.eventType(),
                parentOrganization, command.documentUri().toString()));
        } catch (OfficialExcelImportService.NonJobWorkbookException ignored) {
            return ProcessingResult.ignored("NON_JOB_WORKBOOK_SCHEMA");
        } catch (OfficialExcelImportService.NonTargetWorkbookException ignored) {
            return ProcessingResult.ignored("NON_TARGET_WORKBOOK_SCHEMA");
        }
        if (!result.errors().isEmpty() || !result.warnings().isEmpty()) {
            var issues = new java.util.ArrayList<>(result.errors().stream().map(error -> new ProcessingIssue(
                com.careeros.domain.acquisition.ArtifactImportFailure.FailureStage.ROW_PARSE_FAILED,
                error.sheet(), error.row(), "ROW_PARSE_FAILED", error.message())).toList());
            result.warnings().forEach(warning -> issues.add(new ProcessingIssue(
                com.careeros.domain.acquisition.ArtifactImportFailure.FailureStage.ROW_PARSE_FAILED,
                warning.sheet(), warning.row(), "ELIGIBILITY_NEEDS_REVIEW",
                warning.field() + "：" + warning.rawValue() + "；" + warning.message())));
            return ProcessingResult.importedWithErrors(result.recruitmentEventId(), result.inserted(), result.updated(),
                result.unchanged(), result.deactivated(), issues);
        }
        return ProcessingResult.imported(result.recruitmentEventId(), result.inserted(), result.updated(),
            result.unchanged(), result.deactivated());
    }

    private ProcessingResult importArchive(ProcessDocumentCommand command) {
        if (command.content().length < 22) return ProcessingResult.failed("ARCHIVE_MALFORMED");
        List<ProcessingResult> children = new java.util.ArrayList<>();
        long totalBytes = 0;
        int entryCount = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(command.content()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) continue;
                if (++entryCount > MAX_ARCHIVE_ENTRIES) return ProcessingResult.failed("ARCHIVE_ENTRY_COUNT_EXCEEDED");
                byte[] content = readEntry(zip);
                totalBytes += content.length;
                if (totalBytes > MAX_ARCHIVE_TOTAL_BYTES) return ProcessingResult.failed("ARCHIVE_TOTAL_SIZE_EXCEEDED");
                URI childUri = childUri(command.documentUri(), entry.getName());
                String mediaType = mediaTypes.detect(childUri, null, content);
                if (MediaTypeDetector.ZIP.equals(mediaType)) return ProcessingResult.failed("ARCHIVE_NESTED_UNSUPPORTED");
                if (!isSupportedArchiveChild(mediaType)) return ProcessingResult.failed("ARCHIVE_CHILD_UNSUPPORTED");
                children.add(process(new ProcessDocumentCommand(content, mediaType, childUri,
                    command.parentAnnouncementUri(), command.announcementTitle(), command.capturedAt(),
                    command.recruitmentYear(), command.publishedOn(), command.defaultLocation(), command.eventType())));
            }
        } catch (ArchiveLimitException exception) {
            return ProcessingResult.failed(exception.code);
        } catch (ZipException exception) {
            return ProcessingResult.failed("ARCHIVE_MALFORMED");
        } catch (IOException exception) {
            return ProcessingResult.failed("ARCHIVE_READ_FAILED");
        }
        if (children.isEmpty()) return ProcessingResult.failed("ARCHIVE_EMPTY");
        return mergeArchiveResults(children);
    }

    private static boolean isSupportedArchiveChild(String mediaType) {
        return MediaTypeDetector.HTML.equals(mediaType) || "application/xhtml+xml".equals(mediaType)
            || MediaTypeDetector.PDF.equals(mediaType) || MediaTypeDetector.DOCX.equals(mediaType)
            || MediaTypeDetector.XLS.equals(mediaType) || MediaTypeDetector.XLSX.equals(mediaType)
            || MediaTypeDetector.PNG.equals(mediaType) || MediaTypeDetector.JPEG.equals(mediaType);
    }

    private static byte[] readEntry(ZipInputStream zip) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) >= 0) {
            if (read == 0) continue;
            if (output.size() + (long) read > MAX_ARCHIVE_ENTRY_BYTES) {
                throw new ArchiveLimitException("ARCHIVE_ENTRY_SIZE_EXCEEDED");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static URI childUri(URI parent, String entryName) {
        return URI.create(parent + "#entry=" + URLEncoder.encode(entryName, StandardCharsets.UTF_8)
            .replace("+", "%20"));
    }

    private static ProcessingResult mergeArchiveResults(List<ProcessingResult> children) {
        boolean failed = children.stream().anyMatch(value -> value.status() == ProcessingStatus.FAILED
            || value.status() == ProcessingStatus.UNSUPPORTED);
        if (failed) return ProcessingResult.failed("ARCHIVE_CHILD_FAILED");
        UUID eventId = children.stream().map(ProcessingResult::recruitmentEventId)
            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        UUID extractionRunId = children.stream().map(ProcessingResult::extractionRunId)
            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        int inserted = children.stream().mapToInt(ProcessingResult::inserted).sum();
        int updated = children.stream().mapToInt(ProcessingResult::updated).sum();
        int unchanged = children.stream().mapToInt(ProcessingResult::unchanged).sum();
        int deactivated = children.stream().mapToInt(ProcessingResult::deactivated).sum();
        List<ProcessingIssue> issues = children.stream().flatMap(value -> value.issues().stream()).toList();
        boolean partial = children.stream().anyMatch(value -> value.status() == ProcessingStatus.PROCESSED_WITH_ERRORS
            || value.status() == ProcessingStatus.OCR_REQUIRED);
        if (partial) return new ProcessingResult(ProcessingStatus.PROCESSED_WITH_ERRORS, extractionRunId, eventId,
            inserted, updated, unchanged, deactivated, "ARCHIVE_CHILD_WARNINGS", issues);
        if (children.stream().allMatch(value -> value.status() == ProcessingStatus.IGNORED)) {
            return ProcessingResult.ignored("ARCHIVE_CHILDREN_IGNORED");
        }
        return new ProcessingResult(ProcessingStatus.PROCESSED, extractionRunId, eventId,
            inserted, updated, unchanged, deactivated, null, issues);
    }

    private static final class ArchiveLimitException extends IOException {
        private final String code;
        private ArchiveLimitException(String code) { this.code = code; }
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
        int marker = firstMarker(title, "公开招聘", "人才引进", "招聘");
        int end = year.find() && (marker < 0 || year.start() < marker) ? year.start() : marker;
        if (end <= 0) return null;
        String candidate = title.substring(0, end)
            .replaceFirst("^关于", "")
            .replaceAll("^[附件一二三四五六七八九十0-9.：:、\\-]+", "")
            .replaceAll("[（(]?招聘[）)]?$", "")
            .replaceFirst("关于$", "")
            .strip();
        int about = candidate.indexOf("关于");
        if (about > 0) {
            String principal = candidate.substring(0, about).strip();
            if (ORGANIZATION_SUFFIX.matcher(principal).matches()) candidate = principal;
        }
        int alternateName = Math.min(
            positiveOrLength(candidate.indexOf('（'), candidate.length()),
            positiveOrLength(candidate.indexOf('('), candidate.length()));
        candidate = candidate.substring(0, alternateName).strip();
        return candidate.length() >= 2 && candidate.length() <= 180
            && ORGANIZATION_SUFFIX.matcher(candidate).matches() ? candidate : null;
    }

    private static boolean differentOrganizations(String parent, String attachment) {
        return parent != null && attachment != null
            && !parent.contains(attachment) && !attachment.contains(parent);
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
