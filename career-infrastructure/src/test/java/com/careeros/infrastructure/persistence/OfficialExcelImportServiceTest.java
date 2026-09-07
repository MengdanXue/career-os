package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.careeros.application.JobUpsertService;
import com.careeros.application.OfficialJobAdmissionService;
import com.careeros.application.JobUpsertService.JobUpsertBatch;
import com.careeros.application.JobUpsertService.JobUpsertResult;
import com.careeros.domain.GraduateEligibilityRule;
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("ambiguousHardConditions")
    void ambiguousHardConditionsKeepTheJobAndExposeLocatedWarnings(String age, String experience) throws Exception {
        var events = mock(RecruitmentEventJpaRepository.class);
        var organizations = mock(OrganizationJpaRepository.class);
        var upserts = mock(JobUpsertService.class);
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(upserts.stableKey(any())).thenReturn("job-1");
        when(upserts.upsert(any())).thenReturn(new JobUpsertResult(1, 0, 0, 0, List.of(UUID.randomUUID())));
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("岗位表");
            var header = sheet.createRow(0);
            var row = sheet.createRow(1);
            var headers = List.of("单位名称", "岗位名称", "年龄", "工作经历", "招聘人数");
            var values = List.of("杭州市测试信息中心", "软件工程师", age, experience, "1");
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
                row.createCell(i).setCellValue(values.get(i));
            }
            workbook.write(output);
            bytes = output.toByteArray();
        }
        var result = new OfficialExcelImportService(events, organizations, upserts,
            mock(OfficialJobAdmissionService.class)).importWorkbook(new ByteArrayInputStream(bytes),
                new OfficialExcelImportService.ImportCommand("2026招聘", "https://example.test/notice",
                    2026, LocalDate.of(2026, 3, 17), null, "杭州", EventType.PUBLIC_INSTITUTION));
        var batch = ArgumentCaptor.forClass(JobUpsertBatch.class);
        verify(upserts).upsert(batch.capture());
        assertThat(batch.getValue().jobs()).singleElement().satisfies(job -> {
            assertThat(job.maximumAge()).isNull();
            assertThat(job.minimumExperienceYears()).isNull();
            assertThat(job.ageRequirementText()).isEqualTo(age);
            assertThat(job.originalRequirementText()).contains(experience);
        });
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result);
        assertThat(json.path("warnings").size()).isEqualTo(2);
        assertThat(json.path("warnings").get(0).path("sheet").asText()).isEqualTo("岗位表");
        assertThat(json.path("warnings").get(0).path("row").asInt()).isEqualTo(2);
        assertThat(json.path("warnings").get(0).path("field").asText()).isEqualTo("年龄");
        assertThat(json.path("warnings").get(0).path("rawValue").asText()).isEqualTo(age);
        assertThat(json.path("warnings").get(1).path("sheet").asText()).isEqualTo("岗位表");
        assertThat(json.path("warnings").get(1).path("row").asInt()).isEqualTo(2);
        assertThat(json.path("warnings").get(1).path("field").asText()).isEqualTo("工作经历");
        assertThat(json.path("warnings").get(1).path("rawValue").asText()).isEqualTo(experience);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> ambiguousHardConditions() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("硕士35周岁以下，博士40周岁以下", "本科5年，硕士3年"),
            org.junit.jupiter.params.provider.Arguments.of("硕士35周岁以下\n博士不限", "本科需3年工作经验\n硕士不限"),
            org.junit.jupiter.params.provider.Arguments.of("硕士35周岁以下\n博士不限", "2年以上相关工作经验者优先"),
            org.junit.jupiter.params.provider.Arguments.of("35周岁以下或不限", "工作年限不限；本科须三年工作经验"));
    }

    @Test
    void identifiesApplicantRosterAsANonJobWorkbook() throws Exception {
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        when(events.findFirstBySourceUrl(any())).thenReturn(Optional.empty());
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> new OfficialExcelImportService(
            events, organizations, upserts, admissions).importWorkbook(
                new ByteArrayInputStream(applicantRosterWorkbook()),
                new OfficialExcelImportService.ImportCommand(
                    "2026年公开招聘", "https://example.test/notice", 2026,
                    LocalDate.of(2026, 3, 17), null, "杭州", EventType.PUBLIC_INSTITUTION)))
            .isInstanceOf(OfficialExcelImportService.NonJobWorkbookException.class);
    }

    @Test
    void identifiesProfessionalReferenceDirectoryAsANonJobWorkbook() throws Exception {
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        when(events.findFirstBySourceUrl(any())).thenReturn(Optional.empty());
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> new OfficialExcelImportService(
            events, organizations, upserts, admissions).importWorkbook(
                new ByteArrayInputStream(professionalReferenceDirectoryWorkbook()),
                new OfficialExcelImportService.ImportCommand(
                    "2025年紧缺岗位政府雇员公开招聘公告", "https://example.test/notice", 2025,
                    LocalDate.of(2025, 1, 27), null, "杭州", EventType.PUBLIC_INSTITUTION)))
            .isInstanceOf(OfficialExcelImportService.NonJobWorkbookException.class);
    }

    @Test
    void identifiesTeachingResearchPlanAsOutsideTheCandidateScope() throws Exception {
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        when(events.findFirstBySourceUrl(any())).thenReturn(Optional.empty());
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> new OfficialExcelImportService(
            events, organizations, upserts, admissions).importWorkbook(
                new ByteArrayInputStream(teachingResearchWorkbook()),
                new OfficialExcelImportService.ImportCommand(
                    "杭州师范大学2025年春季公开招聘", "https://example.test/teaching", 2025,
                    LocalDate.of(2025, 5, 8), null, "杭州", EventType.UNIVERSITY,
                    "杭州师范大学")))
            .isInstanceOf(OfficialExcelImportService.NonTargetWorkbookException.class);
    }

    @Test
    void workbookTitleOverridesGenericPublisherAndSuppliesTheOrganization() throws Exception {
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
        when(upserts.upsert(any())).thenReturn(
            new JobUpsertResult(1, 0, 0, 0, List.of(UUID.randomUUID())));

        new OfficialExcelImportService(events, organizations, upserts, admissions).importWorkbook(
            new ByteArrayInputStream(singleInstitutionWorkbook()),
            new OfficialExcelImportService.ImportCommand(
                "浙江省卫生健康委员会下属事业单位2026年公开招聘",
                "https://example.test/health-notice", 2026, LocalDate.of(2026, 3, 17), null,
                "浙江", EventType.PUBLIC_INSTITUTION, "浙江省卫生健康委员会"));

        ArgumentCaptor<JobUpsertBatch> batch = ArgumentCaptor.forClass(JobUpsertBatch.class);
        verify(upserts).upsert(batch.capture());
        assertThat(batch.getValue().jobs()).singleElement().satisfies(job -> {
            assertThat(job.organizationName()).isEqualTo("浙江省立同德医院");
            assertThat(job.title()).isEqualTo("信息中心/工程师");
            assertThat(job.location()).isEqualTo("浙江");
            assertThat(job.worksite()).isNull();
            assertThat(job.minimumEducation()).isEqualTo(EducationLevel.MASTER);
            assertThat(job.degreeRequirement()).isEqualTo("硕士研究生/硕士");
        });
    }

    @Test
    void teachingSheetDoesNotDiscardTechnicalSheetFromTheSameWorkbook() throws Exception {
        assertThat(importedJobs(mixedTeachingAndTechnicalWorkbook()))
            .singleElement()
            .satisfies(job -> {
                assertThat(job.organizationName()).isEqualTo("杭州师范大学信息化中心");
                assertThat(job.title()).isEqualTo("软件工程师");
            });
    }

    @Test
    void jobTableRemarksAndContactColumnsDoNotTurnItIntoAnApplicantForm() throws Exception {
        assertThat(importedJobs(jobPlanWithApplicationFormRemark()))
            .singleElement()
            .satisfies(job -> assertThat(job.title()).isEqualTo("信息系统工程师"));
    }

    @Test
    void technicalRoleInTeachingPlanSheetPreventsTheWholeSheetFromBeingDiscarded() throws Exception {
        assertThat(importedJobs(singleSheetTeachingAndTechnicalWorkbook()))
            .extracting(JobUpsertService.NormalizedJob::title)
            .contains("软件工程师");
    }

    @Test
    void unrecognizedSubstantiveSheetPreventsMixedWorkbookFromBeingMarkedNonTarget() throws Exception {
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        when(events.findFirstBySourceUrl(any())).thenReturn(Optional.empty());
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> new OfficialExcelImportService(
            events, organizations, upserts, admissions).importWorkbook(
                new ByteArrayInputStream(mixedTeachingAndUnrecognizedTechnicalWorkbook()),
                new OfficialExcelImportService.ImportCommand(
                    "杭州师范大学2025年公开招聘", "https://example.test/mixed", 2025,
                    LocalDate.of(2025, 5, 8), null, "杭州", EventType.UNIVERSITY,
                    "杭州师范大学")))
            .isInstanceOf(IllegalArgumentException.class)
            .isNotInstanceOf(OfficialExcelImportService.NonTargetWorkbookException.class);
    }

    @Test
    void rowOrganizationOverridesWorkbookPublisherForMultiInstitutionPlans() throws Exception {
        assertThat(importedJobs(multiInstitutionWorkbook()))
            .extracting(JobUpsertService.NormalizedJob::organizationName)
            .containsExactly("浙江省第一医院", "浙江省第二研究院");
    }

    @Test
    void keepsPublishingGroupSeparateFromActualEmployerAndAppliesDefaultsPerField() throws Exception {
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        when(events.findFirstBySourceUrl(any())).thenReturn(Optional.empty());
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizations.findFirstByName(any())).thenReturn(Optional.empty());
        when(organizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(upserts.stableKey(any())).thenAnswer(invocation ->
            ((JobUpsertService.NormalizedJob) invocation.getArgument(0)).externalJobCode());
        when(upserts.upsert(any())).thenAnswer(invocation -> {
            JobUpsertBatch batch = invocation.getArgument(0);
            return new JobUpsertResult(batch.jobs().size(), 0, 0, 0,
                batch.jobs().stream().map(ignored -> UUID.randomUUID()).toList());
        });

        new OfficialExcelImportService(events, organizations, upserts, admissions).importWorkbook(
            new ByteArrayInputStream(soeIdentityWorkbook()), new OfficialExcelImportService.ImportCommand(
                "杭州数据集团2027届招聘", "https://example.test/soe", 2027,
                LocalDate.of(2026, 8, 27), null, "杭州", EventType.STATE_OWNED_ENTERPRISE,
                "杭州数据集团", "https://example.test/soe.xlsx", "杭州数据集团直属用人单位",
                "杭州市", "公告：国企正式劳动合同"));

        ArgumentCaptor<JobUpsertBatch> batch = ArgumentCaptor.forClass(JobUpsertBatch.class);
        verify(upserts).upsert(batch.capture());
        assertThat(batch.getValue().jobs()).hasSize(2);
        assertThat(batch.getValue().jobs().get(0)).satisfies(job -> {
            assertThat(job.organizationName()).isEqualTo("杭州数据集团");
            assertThat(job.actualEmployer()).isEqualTo("杭州数科有限公司");
            assertThat(job.worksite()).isEqualTo("滨江区");
            assertThat(job.location()).isEqualTo("杭州");
            assertThat(job.employmentType()).isEqualTo(EmploymentType.SOE_FORMAL);
            assertThat(job.employmentEvidence()).contains("国企正式劳动合同", "杭州数科有限公司", "滨江区");
        });
        assertThat(batch.getValue().jobs().get(1)).satisfies(job -> {
            assertThat(job.actualEmployer()).isEqualTo("杭州数据集团直属用人单位");
            assertThat(job.worksite()).isEqualTo("杭州市");
            assertThat(job.employmentType()).isEqualTo(EmploymentType.UNKNOWN);
            assertThat(job.employmentEvidence()).contains("公告：国企正式劳动合同");
        });
    }

    @Test
    void repeatedPrintedHeadersAreIgnoredInsteadOfImportedAsJobs() throws Exception {
        assertThat(importedJobs(workbookWithRepeatedPrintedHeaders()))
            .extracting(JobUpsertService.NormalizedJob::title)
            .containsExactly("信息系统工程师", "数据治理工程师");
    }

    @Test
    void attachesWorkbookEvidenceAndLocatedFieldsToImportedJob() throws Exception {
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        OfficialWorkbookEvidenceService evidence = mock(OfficialWorkbookEvidenceService.class);
        UUID evidenceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(events.findFirstBySourceUrl(any())).thenReturn(Optional.empty());
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizations.findFirstByName(any())).thenReturn(Optional.empty());
        when(organizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(upserts.stableKey(any())).thenAnswer(invocation -> ((JobUpsertService.NormalizedJob) invocation.getArgument(0)).title());
        when(upserts.upsert(any())).thenReturn(new JobUpsertResult(1, 0, 0, 0, List.of(jobId)));
        when(evidence.begin(any(), any(), any())).thenReturn(evidenceId);

        new OfficialExcelImportService(events, organizations, upserts, admissions, evidence).importWorkbook(
            new ByteArrayInputStream(workbook()), new OfficialExcelImportService.ImportCommand(
                "2026年公开招聘", "https://hrss.hangzhou.gov.cn/notice.html", 2026,
                LocalDate.of(2026, 3, 17), null, "杭州", EventType.PUBLIC_INSTITUTION,
                "杭州科技职业技术学院", "https://hrss.hangzhou.gov.cn/plan.xlsx"));

        ArgumentCaptor<JobUpsertBatch> batch = ArgumentCaptor.forClass(JobUpsertBatch.class);
        verify(upserts).upsert(batch.capture());
        assertThat(batch.getValue().jobs().getFirst().evidenceIds()).contains(evidenceId);
        verify(evidence).replaceJobFacts(
            org.mockito.ArgumentMatchers.eq(evidenceId), org.mockito.ArgumentMatchers.eq(jobId),
            org.mockito.ArgumentMatchers.eq("岗位计划"), org.mockito.ArgumentMatchers.eq(2),
            org.mockito.ArgumentMatchers.argThat(fields ->
                "信息管理".equals(fields.get("title"))
                    && "硕士研究生".equals(fields.get("educationRequirementText"))
                    && "计算机科学与技术类（限计算机科学与技术、软件工程方向）；电子信息类（限控制工程方向）".equals(fields.get("majorRequirementText"))
                    && "0571-12345678".equals(fields.get("contactPhone"))),
            org.mockito.ArgumentMatchers.argThat(columns ->
                "学科/专业要求".equals(columns.get("majorRequirementText"))
                    && "招聘单位咨询电话".equals(columns.get("contactPhone"))));
    }

    @Test
    void doesNotCopyDatesFromEvidenceEmptyLegacyEvent() throws Exception {
        String announcementUrl = "https://hrss.hangzhou.gov.cn/art/2026/notice.html";
        String workbookUrl = "https://hrss.hangzhou.gov.cn/files/plan-a.xlsx";
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        var legacy = new JpaModels.RecruitmentEventEntity();
      legacy.id = UUID.randomUUID(); legacy.sourceUrl = announcementUrl;
      legacy.legacyWorkbookSnapshot = true;
        legacy.evidenceIds = new java.util.ArrayList<>();
        legacy.applicationStartsOn = LocalDate.of(2025, 1, 1);
        legacy.applicationEndsOn = LocalDate.of(2025, 1, 2);
        when(events.findFirstBySourceUrl(announcementUrl)).thenReturn(Optional.of(legacy));
        when(events.findFirstBySourceUrl(workbookUrl)).thenReturn(Optional.empty());
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizations.findFirstByName(any())).thenReturn(Optional.empty());
        when(organizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(upserts.stableKey(any())).thenAnswer(invocation ->
            ((JobUpsertService.NormalizedJob) invocation.getArgument(0)).title());
        when(upserts.upsert(any())).thenReturn(new JobUpsertResult(1, 0, 0, 0, List.of(UUID.randomUUID())));

        new OfficialExcelImportService(events, organizations, upserts, admissions).importWorkbook(
            new ByteArrayInputStream(workbook()), new OfficialExcelImportService.ImportCommand(
                "2026年公开招聘", announcementUrl, 2026, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 1), "杭州", EventType.PUBLIC_INSTITUTION, null, workbookUrl));

        ArgumentCaptor<JpaModels.RecruitmentEventEntity> saved = ArgumentCaptor.forClass(JpaModels.RecruitmentEventEntity.class);
        org.mockito.Mockito.verify(events).save(saved.capture());
        assertThat(saved.getValue().applicationStartsOn).isNull();
        assertThat(saved.getValue().applicationEndsOn).isNull();
        ArgumentCaptor<JobUpsertBatch> batch = ArgumentCaptor.forClass(JobUpsertBatch.class);
        org.mockito.Mockito.verify(upserts).upsert(batch.capture());
        assertThat(batch.getValue().legacyRecruitmentEventId()).isEqualTo(legacy.id);
    }

    @Test
    void backfillsExistingWorkbookEventWithParentAnnouncementDates() throws Exception {
        String announcementUrl = "https://hrss.hangzhou.gov.cn/art/2026/notice.html";
        String workbookUrl = "https://hrss.hangzhou.gov.cn/files/plan-a.xlsx";
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        var announcement = new JpaModels.RecruitmentEventEntity();
        announcement.id = UUID.randomUUID(); announcement.sourceUrl = announcementUrl;
        announcement.evidenceIds = new java.util.ArrayList<>(List.of(UUID.randomUUID()));
        announcement.applicationStartsOn = LocalDate.of(2026, 8, 10);
        announcement.applicationEndsOn = LocalDate.of(2026, 8, 20);
        var workbookEvent = new JpaModels.RecruitmentEventEntity();
        workbookEvent.id = UUID.randomUUID(); workbookEvent.sourceUrl = workbookUrl;
        workbookEvent.evidenceIds = new java.util.ArrayList<>();
        when(events.findFirstBySourceUrl(announcementUrl)).thenReturn(Optional.of(announcement));
        when(events.findFirstBySourceUrl(workbookUrl)).thenReturn(Optional.of(workbookEvent));
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizations.findFirstByName(any())).thenReturn(Optional.empty());
        when(organizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(upserts.stableKey(any())).thenAnswer(invocation ->
            ((JobUpsertService.NormalizedJob) invocation.getArgument(0)).title());
        when(upserts.upsert(any())).thenReturn(new JobUpsertResult(0, 0, 1, 0, List.of(UUID.randomUUID())));

        new OfficialExcelImportService(events, organizations, upserts, admissions).importWorkbook(
            new ByteArrayInputStream(workbook()), new OfficialExcelImportService.ImportCommand(
                "2026年公开招聘", announcementUrl, 2026, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 1), "杭州", EventType.PUBLIC_INSTITUTION, null, workbookUrl));

        assertThat(workbookEvent.applicationStartsOn).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(workbookEvent.applicationEndsOn).isEqualTo(LocalDate.of(2026, 8, 20));
        org.mockito.Mockito.verify(events).save(workbookEvent);
    }

    @Test
    void signedTokenRefreshReusesTheWorkbookEventIdentity() throws Exception {
        String announcementUrl = "https://hrss.hangzhou.gov.cn/art/2026/notice.html";
        String oldUrl = "https://hrss.hangzhou.gov.cn/download?fileName=jobs-2026.xlsx&fileUrl=old-token";
        String refreshedUrl = "https://hrss.hangzhou.gov.cn/download?fileName=jobs-2026.xlsx&fileUrl=new-token";
        String identity = OfficialWorkbookIdentity.of(refreshedUrl);
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        var existing = new JpaModels.RecruitmentEventEntity();
        existing.id = UUID.randomUUID();
        existing.sourceUrl = oldUrl;
        existing.workbookIdentity = identity;
        existing.evidenceIds = new java.util.ArrayList<>();
        when(events.findFirstBySourceUrl(announcementUrl)).thenReturn(Optional.empty());
        when(events.findFirstByWorkbookIdentity(identity)).thenReturn(Optional.of(existing));
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizations.findFirstByName(any())).thenReturn(Optional.empty());
        when(organizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(upserts.stableKey(any())).thenAnswer(invocation ->
            ((JobUpsertService.NormalizedJob) invocation.getArgument(0)).title());
        when(upserts.upsert(any())).thenReturn(new JobUpsertResult(0, 0, 1, 0, List.of(UUID.randomUUID())));

        var result = new OfficialExcelImportService(events, organizations, upserts, admissions)
            .importWorkbook(new ByteArrayInputStream(workbook()),
                new OfficialExcelImportService.ImportCommand(
                    "2026年公开招聘", announcementUrl, 2026, LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 1), "杭州", EventType.PUBLIC_INSTITUTION,
                    null, refreshedUrl));

        assertThat(result.recruitmentEventId()).isEqualTo(existing.id);
        assertThat(existing.sourceUrl).isEqualTo(refreshedUrl);
        assertThat(existing.workbookIdentity).isEqualTo(identity);
        verify(events).save(existing);
    }

    @Test
    void signedTokenRefreshDoesNotMoveCanonicalEventOntoAnOccupiedRawUrl() throws Exception {
        String announcementUrl = "https://rlsbt.zj.gov.cn/art/2026/notice.html";
        String oldUrl = "https://rlsbt.zj.gov.cn/download?fileName=jobs.xlsx&fileUrl=old-token";
        String refreshedUrl = "https://rlsbt.zj.gov.cn/download?fileName=jobs.xlsx&fileUrl=new-token";
        String identity = OfficialWorkbookIdentity.of(refreshedUrl);
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        JobPostingJpaRepository jobPostings = mock(JobPostingJpaRepository.class);
        var canonical = new JpaModels.RecruitmentEventEntity();
        canonical.id = UUID.randomUUID();
        canonical.sourceUrl = oldUrl;
        canonical.workbookIdentity = identity;
        canonical.evidenceIds = new java.util.ArrayList<>();
        var rawUrlOccupant = new JpaModels.RecruitmentEventEntity();
        rawUrlOccupant.id = UUID.randomUUID();
        rawUrlOccupant.sourceUrl = refreshedUrl;
        rawUrlOccupant.evidenceIds = new java.util.ArrayList<>();
        when(events.findFirstBySourceUrl(announcementUrl)).thenReturn(Optional.empty());
        when(events.findFirstBySourceUrl(refreshedUrl)).thenReturn(Optional.of(rawUrlOccupant));
        when(events.findFirstByWorkbookIdentity(identity)).thenReturn(Optional.of(canonical));
        when(jobPostings.existsByRecruitmentEventIdAndActiveTrue(rawUrlOccupant.id)).thenReturn(false);
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizations.findFirstByName(any())).thenReturn(Optional.empty());
        when(organizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(upserts.stableKey(any())).thenAnswer(invocation ->
            ((JobUpsertService.NormalizedJob) invocation.getArgument(0)).title());
        when(upserts.upsert(any())).thenReturn(new JobUpsertResult(0, 0, 1, 0, List.of(UUID.randomUUID())));

        var result = new OfficialExcelImportService(events, organizations, upserts, admissions, null, jobPostings)
            .importWorkbook(new ByteArrayInputStream(workbook()),
                new OfficialExcelImportService.ImportCommand(
                    "2026年公开招聘", announcementUrl, 2026, LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 1), "浙江", EventType.PUBLIC_INSTITUTION,
                    null, refreshedUrl));

        assertThat(result.recruitmentEventId()).isEqualTo(canonical.id);
        assertThat(canonical.sourceUrl).isEqualTo(oldUrl);
        assertThat(canonical.workbookIdentity).isEqualTo(identity);
        verify(jobPostings).existsByRecruitmentEventIdAndActiveTrue(rawUrlOccupant.id);
    }

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
        announcementEvent.applicationStartsOn = LocalDate.of(2026, 8, 10);
        announcementEvent.applicationEndsOn = LocalDate.of(2026, 8, 20);
        announcementEvent.ageReferenceDate = LocalDate.of(2026, 3, 19);
        announcementEvent.defaultEmploymentType = EmploymentType.PUBLIC_INSTITUTION_FORMAL;
        announcementEvent.registrationUrl = "http://qssy.zjks.com";
        announcementEvent.employmentStatement = "经公示无异议，签订聘用合同。";
        announcementEvent.graduateRuleJson = GraduateEligibilityRule.fromExplicitYears(
            2026, java.util.Set.of(2025, 2026), true, "2025、2026届及留学回国人员");
        announcementEvent.writtenExamState = GraduateEligibilityRule.EvidenceState.CONFIRMED;
        announcementEvent.professionalTestState = GraduateEligibilityRule.EvidenceState.NOT_REQUIRED;
        announcementEvent.interviewState = GraduateEligibilityRule.EvidenceState.NOT_PUBLISHED;
        announcementEvent.interviewMethod = "结构化面试";
        announcementEvent.scoreFormula = "笔试、面试成绩各占50%";
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
        ArgumentCaptor<JpaModels.RecruitmentEventEntity> savedEvent = ArgumentCaptor.forClass(JpaModels.RecruitmentEventEntity.class);
        org.mockito.Mockito.verify(upserts).upsert(batch.capture());
        org.mockito.Mockito.verify(events).save(savedEvent.capture());
        assertThat(batch.getValue().legacyRecruitmentEventId()).isNull();
        assertThat(savedEvent.getValue().applicationStartsOn).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(savedEvent.getValue().applicationEndsOn).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(savedEvent.getValue().ageReferenceDate).isEqualTo(LocalDate.of(2026, 3, 19));
        assertThat(savedEvent.getValue().registrationUrl).isEqualTo("http://qssy.zjks.com");
        assertThat(savedEvent.getValue().employmentStatement).contains("签订聘用合同");
        assertThat(savedEvent.getValue().graduateRuleJson).isEqualTo(announcementEvent.graduateRuleJson);
        assertThat(savedEvent.getValue().writtenExamState)
            .isEqualTo(GraduateEligibilityRule.EvidenceState.CONFIRMED);
        assertThat(savedEvent.getValue().professionalTestState)
            .isEqualTo(GraduateEligibilityRule.EvidenceState.NOT_REQUIRED);
        assertThat(savedEvent.getValue().interviewState)
            .isEqualTo(GraduateEligibilityRule.EvidenceState.NOT_PUBLISHED);
        assertThat(savedEvent.getValue().interviewMethod).isEqualTo("结构化面试");
        assertThat(savedEvent.getValue().scoreFormula).isEqualTo("笔试、面试成绩各占50%");
        assertThat(savedEvent.getValue().defaultEmploymentType).isEqualTo(EmploymentType.PUBLIC_INSTITUTION_FORMAL);
        assertThat(batch.getValue().jobs().getFirst().ageReferenceDate()).isEqualTo(LocalDate.of(2026, 3, 19));
        assertThat(batch.getValue().jobs().getFirst().employmentType()).isEqualTo(EmploymentType.PUBLIC_INSTITUTION_FORMAL);
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
        assertThat(job.jobCategory()).isEqualTo("专业技术");
        assertThat(job.jobGrade()).isEqualTo("十级以下");
        assertThat(job.degreeRequirement()).isEqualTo("硕士以上");
        assertThat(job.genderRequirement()).isEqualTo("不限制");
        assertThat(job.otherRequirements()).isEqualTo("3年以上信息系统工作经历");
        assertThat(job.originalRequirementText()).contains("硕士研究生", "计算机科学与技术", "3年以上信息系统工作经历");
        assertThat(job.interviewRatio()).isEqualTo("1：4");
        assertThat(job.professionalTestRequired()).isFalse();
        assertThat(job.contactPhone()).isEqualTo("0571-12345678");
        assertThat(job.duties()).isNull();
        assertThat(job.location()).as("规范化地区应保留采集命令中已核实的来源区域").isEqualTo("杭州");
        assertThat(job.worksite()).as("Excel 未写明的具体工作地点仍应保持未知").isNull();
    }

    private static byte[] workbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("岗位计划");
            var header = sheet.createRow(0);
            List<String> headers = List.of(
                "岗位编号", "用人学院（部门）", "岗位名称", "计划数", "学历要求",
                "学科/专业要求", "职称要求", "其他条件", "岗位类别", "岗位等级",
                "学位", "性别要求", "经笔试入围比例", "是否设置专业（业务、技能、心理素质）测试",
                "招聘单位咨询电话");
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
            row.createCell(8).setCellValue("专业技术");
            row.createCell(9).setCellValue("十级以下");
            row.createCell(10).setCellValue("硕士以上");
            row.createCell(11).setCellValue("不限制");
            row.createCell(12).setCellValue("1：4");
            row.createCell(13).setCellValue("否");
            row.createCell(14).setCellValue("0571-12345678");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] applicantRosterWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("应聘信息汇总表");
            sheet.createRow(0).createCell(0).setCellValue("公开招聘应聘信息汇总表");
            var header = sheet.createRow(1);
            List<String> headers = List.of("姓名", "性别", "身份证号", "联系电话", "毕业院校");
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] professionalReferenceDirectoryWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("专业参考目录");
            sheet.createRow(0).createCell(0).setCellValue("2025年公务员招考专业参考目录");
            var header = sheet.createRow(1);
            List<String> headers = List.of("学历层次", "专业类别名称", "大类专业目录", "具体专业名称");
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("研究生");
            row.createCell(1).setCellValue("工学类");
            row.createCell(2).setCellValue("计算机类");
            row.createCell(3).setCellValue("计算机科学与技术");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] teachingResearchWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("教学科研人员计划");
            sheet.createRow(0).createCell(0).setCellValue("杭州师范大学2025年春季公开招聘教学科研人员计划");
            var header = sheet.createRow(1);
            List<String> headers = List.of("岗位名称", "岗位编号", "岗位类别", "招聘人数", "学历学位", "学科/专业");
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("经济学院教师");
            row.createCell(1).setCellValue("HSD2501");
            row.createCell(2).setCellValue("教学科研岗");
            row.createCell(3).setCellValue(1);
            row.createCell(4).setCellValue("博士研究生");
            row.createCell(5).setCellValue("应用经济学");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] singleInstitutionWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("计划表");
            sheet.createRow(0).createCell(0).setCellValue("浙江省立同德医院2026年招聘计划表");
            var header = sheet.createRow(1);
            List<String> headers = List.of("部门岗位", "岗位代码", "招聘人数", "专业", "学历/学位");
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("信息中心/工程师");
            row.createCell(1).setCellValue("IT-01");
            row.createCell(2).setCellValue(1);
            row.createCell(3).setCellValue("计算机科学与技术");
            row.createCell(4).setCellValue("硕士研究生/硕士");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static List<JobUpsertService.NormalizedJob> importedJobs(byte[] workbook) throws Exception {
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        OrganizationJpaRepository organizations = mock(OrganizationJpaRepository.class);
        JobUpsertService upserts = mock(JobUpsertService.class);
        OfficialJobAdmissionService admissions = mock(OfficialJobAdmissionService.class);
        when(events.findFirstBySourceUrl(any())).thenReturn(Optional.empty());
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizations.findFirstByName(any())).thenReturn(Optional.empty());
        when(organizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(upserts.stableKey(any())).thenAnswer(invocation -> {
            var job = (JobUpsertService.NormalizedJob) invocation.getArgument(0);
            return job.organizationName() + ":" + job.title();
        });
        when(upserts.upsert(any())).thenAnswer(invocation -> {
            JobUpsertBatch batch = invocation.getArgument(0);
            return new JobUpsertResult(batch.jobs().size(), 0, 0, 0,
                batch.jobs().stream().map(ignored -> UUID.randomUUID()).toList());
        });
        new OfficialExcelImportService(events, organizations, upserts, admissions).importWorkbook(
            new ByteArrayInputStream(workbook), new OfficialExcelImportService.ImportCommand(
                "浙江省卫生健康委员会下属事业单位2026年公开招聘",
                "https://example.test/multi-notice", 2026, LocalDate.of(2026, 3, 17), null,
                "浙江", EventType.PUBLIC_INSTITUTION, "浙江省卫生健康委员会"));
        ArgumentCaptor<JobUpsertBatch> batch = ArgumentCaptor.forClass(JobUpsertBatch.class);
        verify(upserts).upsert(batch.capture());
        return batch.getValue().jobs();
    }

    private static byte[] mixedTeachingAndTechnicalWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var teaching = workbook.createSheet("教学科研人员计划");
            teaching.createRow(0).createCell(0).setCellValue("杭州师范大学教学科研人员计划");
            var teachingHeader = teaching.createRow(1);
            teachingHeader.createCell(0).setCellValue("岗位名称");
            teachingHeader.createCell(1).setCellValue("招聘单位");
            var teachingRow = teaching.createRow(2);
            teachingRow.createCell(0).setCellValue("教师岗");
            teachingRow.createCell(1).setCellValue("杭州师范大学");

            var technical = workbook.createSheet("管理与技术岗位");
            var header = technical.createRow(0);
            List.of("招聘单位", "岗位名称", "学历/学位", "专业").forEach(value ->
                header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            var row = technical.createRow(1);
            row.createCell(0).setCellValue("杭州师范大学信息化中心");
            row.createCell(1).setCellValue("软件工程师");
            row.createCell(2).setCellValue("硕士研究生/硕士");
            row.createCell(3).setCellValue("计算机科学与技术");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] jobPlanWithApplicationFormRemark() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("招聘岗位表");
            var header = sheet.createRow(0);
            List.of("招聘单位", "岗位名称", "专业", "备注", "联系人姓名", "联系电话")
                .forEach(value -> header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("杭州市信息中心");
            row.createCell(1).setCellValue("信息系统工程师");
            row.createCell(2).setCellValue("计算机科学与技术");
            row.createCell(3).setCellValue("请按要求填写报名表");
            row.createCell(4).setCellValue("张老师");
            row.createCell(5).setCellValue("0571-12345678");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] singleSheetTeachingAndTechnicalWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("教学科研人员计划");
            sheet.createRow(0).createCell(0).setCellValue("教学科研人员计划及信息化技术岗位计划");
            var header = sheet.createRow(1);
            List.of("招聘单位", "岗位名称", "专业")
                .forEach(value -> header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            var teaching = sheet.createRow(2);
            teaching.createCell(0).setCellValue("杭州师范大学");
            teaching.createCell(1).setCellValue("经济学院教师岗");
            teaching.createCell(2).setCellValue("应用经济学");
            var technical = sheet.createRow(3);
            technical.createCell(0).setCellValue("杭州师范大学信息化中心");
            technical.createCell(1).setCellValue("软件工程师");
            technical.createCell(2).setCellValue("计算机科学与技术");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] mixedTeachingAndUnrecognizedTechnicalWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var teaching = workbook.createSheet("教学科研人员计划");
            var teachingHeader = teaching.createRow(0);
            teachingHeader.createCell(0).setCellValue("岗位名称");
            teachingHeader.createCell(1).setCellValue("招聘单位");
            var teachingRow = teaching.createRow(1);
            teachingRow.createCell(0).setCellValue("教师岗");
            teachingRow.createCell(1).setCellValue("杭州师范大学");

            var technical = workbook.createSheet("信息化岗位计划");
            var header = technical.createRow(0);
            header.createCell(0).setCellValue("职务名称");
            header.createCell(1).setCellValue("所属部门");
            header.createCell(2).setCellValue("专业门类");
            var row = technical.createRow(1);
            row.createCell(0).setCellValue("软件工程师");
            row.createCell(1).setCellValue("信息化中心");
            row.createCell(2).setCellValue("计算机类");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] multiInstitutionWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("招聘计划");
            sheet.createRow(0).createCell(0).setCellValue("浙江省卫生健康委员会2026年招聘计划表");
            var header = sheet.createRow(1);
            List<String> headers = List.of("招聘单位", "岗位名称", "学历", "专业");
            for (int index = 0; index < headers.size(); index++) header.createCell(index).setCellValue(headers.get(index));
            var first = sheet.createRow(2);
            first.createCell(0).setCellValue("浙江省第一医院");
            first.createCell(1).setCellValue("信息中心工程师");
            first.createCell(2).setCellValue("硕士研究生");
            first.createCell(3).setCellValue("计算机科学与技术");
            var second = sheet.createRow(3);
            second.createCell(0).setCellValue("浙江省第二研究院");
            second.createCell(1).setCellValue("数据工程师");
            second.createCell(2).setCellValue("硕士研究生");
            second.createCell(3).setCellValue("软件工程");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] soeIdentityWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("招聘岗位表");
            var headers = List.of("招聘单位", "用人单位", "岗位编号", "岗位名称", "用工性质", "工作地点", "专业");
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.size(); index++) header.createCell(index).setCellValue(headers.get(index));
            var explicit = sheet.createRow(1);
            explicit.createCell(0).setCellValue("杭州数据集团");
            explicit.createCell(1).setCellValue("杭州数科有限公司");
            explicit.createCell(2).setCellValue("A1");
            explicit.createCell(3).setCellValue("Java开发");
            explicit.createCell(4).setCellValue("国企正式劳动合同");
            explicit.createCell(5).setCellValue("滨江区");
            explicit.createCell(6).setCellValue("计算机科学与技术");
            var ambiguous = sheet.createRow(2);
            ambiguous.createCell(0).setCellValue("杭州数据集团");
            ambiguous.createCell(2).setCellValue("A2");
            ambiguous.createCell(3).setCellValue("数据平台工程师");
            ambiguous.createCell(4).setCellValue("聘用制");
            ambiguous.createCell(6).setCellValue("计算机科学与技术");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] workbookWithRepeatedPrintedHeaders() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("高层次和特殊专业技术岗位招聘计划表");
            var headers = List.of("序号", "主管单位（部门）", "招聘单位", "招聘岗位", "招聘人数", "专业要求");
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.size(); index++) header.createCell(index).setCellValue(headers.get(index));
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue("1");
            first.createCell(1).setCellValue("杭州市数据资源管理局");
            first.createCell(2).setCellValue("杭州市信息中心");
            first.createCell(3).setCellValue("信息系统工程师");
            first.createCell(4).setCellValue("1");
            first.createCell(5).setCellValue("计算机科学与技术");
            for (int repeatedRow : List.of(2, 4)) {
                var repeated = sheet.createRow(repeatedRow);
                for (int index = 0; index < headers.size(); index++) repeated.createCell(index).setCellValue(headers.get(index));
            }
            var second = sheet.createRow(3);
            second.createCell(0).setCellValue("2");
            second.createCell(1).setCellValue("杭州市数据资源管理局");
            second.createCell(2).setCellValue("杭州市大数据管理服务中心");
            second.createCell(3).setCellValue("数据治理工程师");
            second.createCell(4).setCellValue("1");
            second.createCell(5).setCellValue("计算机科学与技术");
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
