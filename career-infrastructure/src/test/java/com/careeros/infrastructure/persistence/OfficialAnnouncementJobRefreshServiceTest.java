package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.careeros.application.JobAdmissionPorts;
import com.careeros.application.JobAdmissionPorts.JobFieldEvidence;
import com.careeros.application.OfficialJobAdmissionService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialAnnouncementJobRefreshServiceTest {
    @Test
    void refreshesInheritedHardFactsAndReclassifiesWhenAttachmentIsNotReprocessed() {
        var jobs = mock(JobPostingJpaRepository.class);
        var evidence = mock(JobFieldEvidence.class);
        var admissions = mock(OfficialJobAdmissionService.class);
        var inherited = job("信息中心岗");
        var explicitEmployment = job("软件开发岗");
        when(jobs.findBySourceUrlAndActiveTrue("https://example.gov.cn/notice"))
            .thenReturn(List.of(inherited, explicitEmployment));
        when(evidence.coverage(any(java.util.Collection.class))).thenReturn(Map.of(
            inherited.id, new JobAdmissionPorts.FieldEvidenceCoverage(Set.of(), Set.of(), true, true, true),
            explicitEmployment.id, new JobAdmissionPorts.FieldEvidenceCoverage(
                Set.of("employmentType"), Set.of(), true, true, true)));
        when(jobs.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new OfficialAnnouncementJobRefreshService(jobs, evidence, admissions,
            Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));
        var announcement = new JpaModels.RecruitmentEventEntity();
        announcement.defaultEmploymentType = EmploymentType.UNKNOWN;
        announcement.ageReferenceDate = LocalDate.of(2026, 8, 31);

        service.refresh("https://example.gov.cn/notice", announcement);

        assertThat(inherited.employmentType).isEqualTo(EmploymentType.UNKNOWN);
        assertThat(explicitEmployment.employmentType).isEqualTo(EmploymentType.CONTRACT);
        assertThat(inherited.ageReferenceDate).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(inherited.contentFingerprint).hasSize(64).isNotEqualTo("a".repeat(64));
        verify(admissions).classify(
            org.mockito.ArgumentMatchers.eq(List.of(inherited.id, explicitEmployment.id)), any(Instant.class));
    }

    private static JpaModels.JobPostingEntity job(String title) {
        var job = new JpaModels.JobPostingEntity();
        job.id = UUID.randomUUID();
        job.recruitmentEventId = UUID.randomUUID();
        job.organizationId = UUID.randomUUID();
        job.title = title;
        job.jobFamily = JobFamily.INFORMATION_SYSTEMS;
        job.employmentType = EmploymentType.CONTRACT;
        job.location = "杭州";
        job.headcount = 1;
        job.minimumEducation = EducationLevel.MASTER;
        job.exactMajors = new java.util.LinkedHashSet<>(Set.of("计算机科学与技术"));
        job.acceptedGraduationYears = new java.util.LinkedHashSet<>();
        job.maximumAge = 35;
        job.ageReferenceDate = LocalDate.of(2026, 8, 1);
        job.requiredProfessionalTitles = new java.util.LinkedHashSet<>();
        job.sourceUrl = "https://example.gov.cn/notice";
        job.evidenceIds = new ArrayList<>();
        job.ageRequirementText = "35周岁以下";
        job.contentFingerprint = "a".repeat(64);
        job.active = true;
        return job;
    }
}
