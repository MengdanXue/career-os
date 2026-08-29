package com.careeros.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.careeros.application.JobUpsertService;
import com.careeros.application.JobUpsertService.JobUpsertBatch;
import com.careeros.application.JobUpsertService.JobUpsertResult;
import com.careeros.application.OfficialJobAdmissionService;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.EventType;
import com.careeros.infrastructure.acquisition.HospitalOfficialPageParser.HospitalJobRow;
import com.careeros.infrastructure.acquisition.HospitalOfficialPageParser.HospitalParseIssue;
import com.careeros.infrastructure.acquisition.HospitalOfficialPageParser.ParsedHospitalAnnouncement;
import java.time.LocalDate;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HospitalOfficialJobImportServiceTest {
    @Test
    void transactionalServiceCanBeProxiedBySpring() {
        assertThat(Modifier.isFinal(HospitalOfficialJobImportService.class.getModifiers())).isFalse();
    }

    @Test
    void importsHospitalRowsAsOneCompleteStableSnapshot() {
        var events = mock(RecruitmentEventJpaRepository.class);
        var organizations = mock(OrganizationJpaRepository.class);
        var upserts = mock(JobUpsertService.class);
        var admissions = mock(OfficialJobAdmissionService.class);
        var event = new JpaModels.RecruitmentEventEntity();
        event.id = UUID.randomUUID(); event.title = "杭州市第一人民医院2024年公开招聘";
        event.recruitmentYear = 2024; event.eventType = EventType.HOSPITAL;
        event.sourceUrl = "https://zp.hz-hospital.com/index/index/announcement_desc/id/212.html";
        event.defaultEmploymentType = EmploymentType.PUBLIC_INSTITUTION_FORMAL;
        event.employmentStatement = "公告：签订事业单位聘用合同";
        event.evidenceIds = List.of(UUID.randomUUID());
        var organization = new JpaModels.OrganizationEntity();
        organization.id = UUID.randomUUID(); organization.name = "杭州市第一人民医院";
        when(events.findFirstBySourceUrl(event.sourceUrl)).thenReturn(Optional.of(event));
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizations.findFirstByName("杭州市第一人民医院")).thenReturn(Optional.of(organization));
        when(upserts.upsert(any())).thenReturn(new JobUpsertResult(1, 0, 0, 0, List.of(UUID.randomUUID())));
        var service = new HospitalOfficialJobImportService(events, organizations, upserts, admissions,
            new OfficialJobFieldMapper());
        var parsed = new ParsedHospitalAnnouncement(event.title, LocalDate.of(2024, 3, 8),
            "http://zhaopin.hz-hospital.com:8080/", List.of(new HospitalJobRow(
                "X-信息中心", "信息工作人员", "专业技术", "硕士研究生/硕士",
                "计算机科学与技术", "应届毕业生", "1", "38周岁及以下",
                "杭州市第一人民医院", "城北院区", "员额制")));

        var result = service.importAnnouncement(event.sourceUrl, parsed);

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(event.registrationUrl).isEqualTo("http://zhaopin.hz-hospital.com:8080/");
        verify(events).save(event);
        ArgumentCaptor<JobUpsertBatch> batch = ArgumentCaptor.forClass(JobUpsertBatch.class);
        verify(upserts).upsert(batch.capture());
        assertThat(batch.getValue().completeSnapshot()).isTrue();
        assertThat(batch.getValue().jobs()).singleElement().satisfies(job -> {
            assertThat(job.externalJobCode()).isEqualTo("X-信息中心|信息工作人员");
            assertThat(job.stableSourceUrl()).isEqualTo(event.sourceUrl);
            assertThat(job.location()).isEqualTo("杭州");
            assertThat(job.actualEmployer()).isEqualTo("杭州市第一人民医院");
            assertThat(job.worksite()).isEqualTo("城北院区");
            assertThat(job.employmentType()).isEqualTo(EmploymentType.QUOTA_OR_FILING);
            assertThat(job.employmentEvidence()).contains("员额制", "杭州市第一人民医院", "城北院区");
        });
        verify(admissions).classify(any(), any());
    }

    @Test
    void partialHospitalParseNeverDeactivatesMissingRows() {
        var events = mock(RecruitmentEventJpaRepository.class);
        var organizations = mock(OrganizationJpaRepository.class);
        var upserts = mock(JobUpsertService.class);
        var admissions = mock(OfficialJobAdmissionService.class);
        var event = new JpaModels.RecruitmentEventEntity();
        event.id = UUID.randomUUID();
        event.sourceUrl = "https://zp.hz-hospital.com/index/index/announcement_desc/id/212.html";
        event.evidenceIds = List.of();
        var organization = new JpaModels.OrganizationEntity();
        organization.id = UUID.randomUUID(); organization.name = "杭州市第一人民医院";
        when(events.findFirstBySourceUrl(event.sourceUrl)).thenReturn(Optional.of(event));
        when(organizations.findFirstByName("杭州市第一人民医院")).thenReturn(Optional.of(organization));
        when(upserts.upsert(any())).thenReturn(new JobUpsertResult(1, 0, 0, 0, List.of(UUID.randomUUID())));
        var service = new HospitalOfficialJobImportService(events, organizations, upserts, admissions,
            new OfficialJobFieldMapper());
        var parsed = new ParsedHospitalAnnouncement("招聘公告", LocalDate.of(2024, 3, 8), null,
            List.of(new HospitalJobRow("信息中心", "系统工程师", "专业技术", "硕士", "计算机",
                "应届毕业生", "1", "38周岁以下")),
            List.of(new HospitalParseIssue(3, "MISSING_JOB_TITLE", "岗位名称为空")));

        service.importAnnouncement(event.sourceUrl, parsed);

        ArgumentCaptor<JobUpsertBatch> batch = ArgumentCaptor.forClass(JobUpsertBatch.class);
        verify(upserts).upsert(batch.capture());
        assertThat(batch.getValue().completeSnapshot()).isFalse();
    }
}
