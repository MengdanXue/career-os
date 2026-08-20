package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.careeros.application.JobUpsertService;
import com.careeros.application.OfficialJobAdmissionService;
import com.careeros.application.JobUpsertService.JobUpsertBatch;
import com.careeros.application.JobUpsertService.JobUpsertResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OfficialExcelImportServiceTest {
    @Test
    void evidenceBackedAnnouncementEventIsNotTreatedAsLegacyWorkbookProvenance() throws Exception {
        String announcementUrl = "https://hrss.hangzhou.gov.cn/art/2026/notice.html";
        String workbookUrl = "https://hrss.hangzhou.gov.cn/files/plan-a.xlsx";
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        var announcementEvent = new JpaModels.RecruitmentEventEntity();
        announcementEvent.id = UUID.randomUUID();
        announcementEvent.sourceUrl = announcementUrl;
        announcementEvent.evidenceIds = new java.util.ArrayList<>(List.of(UUID.randomUUID()));
        when(events.findFirstBySourceUrl(announcementUrl)).thenReturn(Optional.of(announcementEvent));
        when(events.findFirstBySourceUrl(workbookUrl)).thenReturn(Optional.empty());
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizations.findFirstByName(any())).thenReturn(Optional.empty());
        when(organizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(upserts.stableKey(any())).thenAnswer(invocation ->
            ((JobUpsertService.NormalizedJob) invocation.getArgument(0)).title());
        when(upserts.upsert(any())).thenReturn(new JobUpsertResult(1, 0, 0, 0, List.of(UUID.randomUUID())));
        var service = new OfficialExcelImportService(events, organizations, upserts, admissions);

        service.importWorkbook(new ByteArrayInputStream(workbook()),
            new OfficialExcelImportService.ImportCommand(
                "杭州科技职业技术学院2026年人才引进（招聘）公告", announcementUrl, 2026,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), "杭州",
                EventType.PUBLIC_INSTITUTION, "杭州科技职业技术学院", workbookUrl));

        ArgumentCaptor<JobUpsertBatch> batch = ArgumentCaptor.forClass(JobUpsertBatch.class);
        org.mockito.Mockito.verify(upserts).upsert(batch.capture());
        assertThat(batch.getValue().legacyRecruitmentEventId()).isNull();
    }

    @Test
    void importsSingleOrganizationSheetWithAlternateOfficialHeaders() throws Exception {
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        when(events.findFirstBySourceUrl(any())).thenReturn(Optional.empty());
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizations.findFirstByName(any())).thenReturn(Optional.empty());
        when(organizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(upserts.stableKey(any())).thenAnswer(invocation ->
            ((JobUpsertService.NormalizedJob) invocation.getArgument(0)).title());
        when(upserts.upsert(any())).thenReturn(new JobUpsertResult(1, 0, 0, 0, List.of(UUID.randomUUID())));
        OfficialExcelImportService service = new OfficialExcelImportService(events, organizations, upserts, admissions);

        service.importWorkbook(new ByteArrayInputStream(workbook()),
            new OfficialExcelImportService.ImportCommand(
                "杭州科技职业技术学院2026年人才引进（招聘）公告",
                "https://hrss.hangzhou.gov.cn/art/2026/notice.html", 2026,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1),
                "杭州", EventType.PUBLIC_INSTITUTION, "杭州科技职业技术学院",
                "https://hrss.hangzhou.gov.cn/files/plan-a.xlsx"));

        ArgumentCaptor<JobUpsertBatch> batch = ArgumentCaptor.forClass(JobUpsertBatch.class);
        org.mockito.Mockito.verify(upserts).upsert(batch.capture());
        org.mockito.Mockito.verify(admissions).classify(any(), any());
        org.mockito.Mockito.verify(events).findFirstBySourceUrl(
            "https://hrss.hangzhou.gov.cn/files/plan-a.xlsx");
        var job = batch.getValue().jobs().getFirst();
        assertThat(job.sourceUrl()).isEqualTo("https://hrss.hangzhou.gov.cn/art/2026/notice.html");
        assertThat(job.stableSourceUrl()).isEqualTo("https://hrss.hangzhou.gov.cn/files/plan-a.xlsx");
        assertThat(job.legacyStableSourceUrl()).isEqualTo("https://hrss.hangzhou.gov.cn/art/2026/notice.html");
        assertThat(batch.getValue().legacyRecruitmentEventId()).isNull();
        assertThat(job.organizationName()).isEqualTo("杭州科技职业技术学院");
        assertThat(job.title()).isEqualTo("信息管理");
        assertThat(job.headcount()).isEqualTo(2);
        assertThat(job.minimumEducation()).isEqualTo(EducationLevel.MASTER);
        assertThat(job.exactMajors()).contains("计算机科学与技术", "软件工程", "控制工程");
        assertThat(job.exactMajors()).noneMatch(value -> value.contains("（限"));
        assertThat(job.minimumExperienceYears()).isEqualTo(3);
        assertThat(job.requiredProfessionalTitles()).contains("中级");
        assertThat(job.jobFamily()).isEqualTo(JobFamily.INFORMATION_SYSTEMS);
        assertThat(job.duties()).contains("3年以上信息系统工作经历");
    }

    private static byte[] workbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("岗位计划");
            var header = sheet.createRow(0);
            List<String> headers = List.of(
                "岗位编号", "用人学院（部门）", "岗位名称", "计划数", "学历要求",
                "学科/专业要求", "职称要求", "其他条件");
            for (int index = 0; index < headers.size(); index++) header.createCell(index).setCellValue(headers.get(index));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("2026X-6");
            row.createCell(1).setCellValue("网络信息中心");
            row.createCell(2).setCellValue("信息管理");
            row.createCell(3).setCellValue(2);
            row.createCell(4).setCellValue("硕士研究生");
            row.createCell(5).setCellValue(
                "计算机科学与技术类（限计算机科学与技术、软件工程方向）；电子信息类（限控制工程方向）");
            row.createCell(6).setCellValue("中级");
            row.createCell(7).setCellValue("3年以上信息系统工作经历");
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
