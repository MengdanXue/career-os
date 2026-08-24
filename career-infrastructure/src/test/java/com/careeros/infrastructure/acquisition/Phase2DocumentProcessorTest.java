package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.careeros.application.AcquiredDocumentProcessor.ProcessDocumentCommand;
import com.careeros.application.AcquiredDocumentProcessor.ProcessingStatus;
import com.careeros.application.ExtractionPorts.ExtractionResult;
import com.careeros.application.ExtractionService;
import com.careeros.domain.DomainEnums.EventType;
import com.careeros.domain.ExtractionRun;
import com.careeros.infrastructure.persistence.OfficialExcelImportService;
import com.careeros.infrastructure.persistence.OfficialExcelImportService.ImportResult;
import com.careeros.infrastructure.persistence.OfficialAnnouncementFactService;
import org.mockito.ArgumentCaptor;
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
    private final OfficialAnnouncementFactService announcementFacts = mock(OfficialAnnouncementFactService.class);
    private final Phase2DocumentProcessor processor = new Phase2DocumentProcessor(extractions, workbooks, announcementFacts);

    @Test
    void htmlUsesExtractionPipelineAndReturnsItsRunId() {
        ExtractionRun run = mock(ExtractionRun.class);
        when(run.id()).thenReturn(RUN_ID);
        UUID evidenceId = UUID.randomUUID();
        when(run.evidenceId()).thenReturn(evidenceId);
        when(extractions.submit(any())).thenReturn(new ExtractionResult(run, Optional.empty(), false));

        var result = processor.process(command("text/html", """
            <html><head><meta name="PubDate" content="2026-03-17 17:12"></head><body>
            报名时间：2026年3月19日9：00－3月25日16：00。经公示无异议的，签订聘用合同。
            </body></html>
            """.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.status()).isEqualTo(ProcessingStatus.PROCESSED);
        assertThat(result.extractionRunId()).isEqualTo(RUN_ID);
        assertThat(result.inserted()).isZero();
        verify(announcementFacts).upsert(
            org.mockito.ArgumentMatchers.eq("2026年事业单位公开招聘"),
            org.mockito.ArgumentMatchers.eq("https://rlsbt.zj.gov.cn/art/2026/3/17/notice.html"),
            org.mockito.ArgumentMatchers.eq(2026), org.mockito.ArgumentMatchers.eq(EventType.PUBLIC_INSTITUTION),
            any(), org.mockito.ArgumentMatchers.eq(evidenceId));
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
    void workbookRowErrorsAreReportedAsProcessedWithErrors() throws Exception {
        UUID eventId = UUID.fromString("01992f09-0000-7000-8000-000000000704");
        when(workbooks.importWorkbook(any(), any())).thenReturn(new ImportResult(
            eventId, 1, 0, 0, 0,
            List.of(new OfficialExcelImportService.RowError("岗位计划", 3, "招聘单位为空"))));

        var result = processor.process(command(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {'P','K',3,4}));

        assertThat(result.status()).isEqualTo(ProcessingStatus.PROCESSED_WITH_ERRORS);
        assertThat(result.successful()).isTrue();
        assertThat(result.errorCode()).isEqualTo("ROW_ERRORS:1");
    }

    @Test
    void processorVersionChangesWhenWorkbookInterpretationChanges() {
        assertThat(processor.version()).isEqualTo("official-fact-fusion-v8");
    }

    @Test
    void singleOrganizationAnnouncementSuppliesWorkbookOrganizationContext() throws Exception {
        UUID eventId = UUID.fromString("01992f09-0000-7000-8000-000000000703");
        when(workbooks.importWorkbook(any(), any())).thenReturn(
            new ImportResult(eventId, 1, 0, 0, 0, List.of()));

        processor.process(command(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            new byte[] {'P','K',3,4}, URI.create("https://hrss.hangzhou.gov.cn/files/plan.xlsx"),
            "杭州青少年活动中心2026年公开招聘工作人员公告"));

        ArgumentCaptor<OfficialExcelImportService.ImportCommand> command =
            ArgumentCaptor.forClass(OfficialExcelImportService.ImportCommand.class);
        verify(workbooks).importWorkbook(any(), command.capture());
        assertThat(command.getValue().announcementTitle())
            .isEqualTo("杭州青少年活动中心2026年公开招聘工作人员公告");
        assertThat(command.getValue().defaultOrganizationName()).isEqualTo("杭州青少年活动中心");
        assertThat(command.getValue().sourceUrl())
            .isEqualTo("https://rlsbt.zj.gov.cn/art/2026/3/17/notice.html");
        assertThat(command.getValue().workbookSourceUrl())
            .isEqualTo("https://hrss.hangzhou.gov.cn/files/plan.xlsx");
    }

    @Test
    void unsupportedMediaReturnsFailureWithoutJobData() {
        var result = processor.process(command("application/zip", new byte[] {'P','K'}));

        assertThat(result.status()).isEqualTo(ProcessingStatus.UNSUPPORTED);
        assertThat(result.inserted()).isZero();
        assertThat(result.errorCode()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void applicationFormWorkbookIsIgnoredWithoutCallingTheJobImporter() throws Exception {
        var result = processor.process(command(
            "application/vnd.ms-excel", new byte[] {(byte)0xD0,(byte)0xCF,0x11,(byte)0xE0},
            URI.create("https://rlsbt.zj.gov.cn/document/download?fileName=%E6%8A%A5%E5%90%8D%E8%A1%A8.xls")));

        assertThat(result.successful()).isTrue();
        assertThat(result.errorCode()).isEqualTo("NON_JOB_WORKBOOK");
        verify(workbooks, never()).importWorkbook(any(), any());
    }

    @Test
    void workbookWhoseSchemaIsAnApplicantRosterIsIgnored() throws Exception {
        when(workbooks.importWorkbook(any(), any())).thenThrow(
            new OfficialExcelImportService.NonJobWorkbookException("应聘信息汇总表不是岗位计划表"));

        var result = processor.process(command(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            new byte[] {'P','K',3,4},
            URI.create("https://hrss.hangzhou.gov.cn/files/opaque-id.xlsx")));

        assertThat(result.successful()).isTrue();
        assertThat(result.errorCode()).isEqualTo("NON_JOB_WORKBOOK_SCHEMA");
    }

    @Test
    void workbookWhoseContentIsOnlyTeachingResearchJobsIsIgnored() throws Exception {
        when(workbooks.importWorkbook(any(), any())).thenThrow(
            new OfficialExcelImportService.NonTargetWorkbookException("教学科研人员计划不在候选人范围内"));

        var result = processor.process(command(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            new byte[] {'P','K',3,4},
            URI.create("https://hrss.hangzhou.gov.cn/files/opaque-teaching-plan.xlsx")));

        assertThat(result.successful()).isTrue();
        assertThat(result.errorCode()).isEqualTo("NON_TARGET_WORKBOOK_SCHEMA");
    }

    @Test
    void teachingResearchPlanIsIgnoredBecauseItIsOutsideTheCandidateScope() throws Exception {
        var result = processor.process(command(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {'P','K',3,4},
            URI.create("https://hrss.hangzhou.gov.cn/download?fileName=%E6%95%99%E5%AD%A6%E7%A7%91%E7%A0%94%E4%BA%BA%E5%91%98%E6%8B%9B%E8%81%98%E8%AE%A1%E5%88%92.xlsx")));

        assertThat(result.successful()).isTrue();
        assertThat(result.errorCode()).isEqualTo("NON_TARGET_WORKBOOK");
        verify(workbooks, never()).importWorkbook(any(), any());
    }

    @Test
    void attachmentExplicitlyNamingAnotherOrganizationIsIgnored() throws Exception {
        assertThat(Phase2DocumentProcessor.defaultOrganizationName(
            "浙江省文化广电和旅游厅关于部分直属事业单位2026年公开招聘人员的公告"))
            .isEqualTo("浙江省文化广电和旅游厅");
        assertThat(Phase2DocumentProcessor.defaultOrganizationName(
            "附件1：浙江师范大学附属中学公开招聘计划表（2026).xls"))
            .isEqualTo("浙江师范大学附属中学");
        var result = processor.process(command(
            "application/vnd.ms-excel", new byte[] {(byte)0xD0,(byte)0xCF,0x11,(byte)0xE0},
            URI.create("https://rlsbt.zj.gov.cn/download?fileName=%E9%99%84%E4%BB%B61%EF%BC%9A%E6%B5%99%E6%B1%9F%E5%B8%88%E8%8C%83%E5%A4%A7%E5%AD%A6%E9%99%84%E5%B1%9E%E4%B8%AD%E5%AD%A6%E5%85%AC%E5%BC%80%E6%8B%9B%E8%81%98%E8%AE%A1%E5%88%92%E8%A1%A8.xls"),
            "浙江省文化广电和旅游厅关于部分直属事业单位2026年公开招聘人员的公告"));

        assertThat(result.successful()).isTrue();
        assertThat(result.errorCode()).isEqualTo("ATTACHMENT_PARENT_MISMATCH");
        verify(workbooks, never()).importWorkbook(any(), any());
    }

    @Test
    void organizationContextHandlesQuotedTitlesWithAnAlternateNameInParentheses() {
        assertThat(Phase2DocumentProcessor.defaultOrganizationName(
            "《杭州市北京航空航天大学国际创新研究院（北京航空航天大学国际创新学院）2026年公开招聘工作人员计划表》"))
            .isEqualTo("杭州市北京航空航天大学国际创新研究院");
    }

    @Test
    void organizationContextHandlesLeadingYearAndTrailingAbout() {
        assertThat(Phase2DocumentProcessor.defaultOrganizationName(
            "2026年杭州市西溪医院公开招聘工作人员公告"))
            .isEqualTo("杭州市西溪医院");
        assertThat(Phase2DocumentProcessor.defaultOrganizationName(
            "杭州市西溪医院关于2026年公开招聘工作人员公告"))
            .isEqualTo("杭州市西溪医院");
    }

    @Test
    void organizationContextRecognizesSecondarySchoolNames() {
        assertThat(Phase2DocumentProcessor.defaultOrganizationName(
            "浙江师范大学附属中学2026年公开招聘工作人员公告"))
            .isEqualTo("浙江师范大学附属中学");
    }

    @Test
    void organizationContextRecognizesAStandaloneInstituteName() {
        assertThat(Phase2DocumentProcessor.defaultOrganizationName(
            "浙江省教育考试院2024年公开招聘人员公告"))
            .isEqualTo("浙江省教育考试院");
    }

    private static ProcessDocumentCommand command(String mediaType, byte[] content) {
        return command(mediaType, content, URI.create("https://rlsbt.zj.gov.cn/files/jobs.xlsx"));
    }

    private static ProcessDocumentCommand command(String mediaType, byte[] content, URI documentUri) {
        return command(mediaType, content, documentUri, "2026年事业单位公开招聘");
    }

    private static ProcessDocumentCommand command(
        String mediaType, byte[] content, URI documentUri, String announcementTitle
    ) {
        return new ProcessDocumentCommand(content, mediaType,
            documentUri,
            URI.create("https://rlsbt.zj.gov.cn/art/2026/3/17/notice.html"),
            announcementTitle, Instant.parse("2026-08-15T00:00:00Z"),
            2026, LocalDate.of(2026, 3, 17), "浙江杭州", EventType.PUBLIC_INSTITUTION);
    }
}
