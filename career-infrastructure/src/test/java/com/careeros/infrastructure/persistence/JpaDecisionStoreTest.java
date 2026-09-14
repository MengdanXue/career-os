package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JpaDecisionStoreTest {
    @Test
    void exposesLatestOfficialAttachmentEvidenceUrlInsteadOfFrozenEventUrl() {
        var jobs = mock(JobPostingJpaRepository.class);
        var organizations = mock(OrganizationJpaRepository.class);
        var events = mock(RecruitmentEventJpaRepository.class);
        var evidence = mock(EvidenceJpaRepository.class);
        var job = job();
        var organization = new JpaModels.OrganizationEntity();
        organization.id = job.organizationId;
        organization.name = "浙江省测试事业单位";
        organization.organizationType = OrganizationType.PUBLIC_INSTITUTION;
        var event = new JpaModels.RecruitmentEventEntity();
        event.id = job.recruitmentEventId;
        event.title = "2026年公开招聘";
        event.recruitmentYear = 2026;
        event.eventType = EventType.PUBLIC_INSTITUTION;
        event.sourceUrl = "https://example.gov.cn/download?fileUrl=expired-token";
        event.defaultEmploymentType = EmploymentType.PUBLIC_INSTITUTION_FORMAL;
        event.evidenceIds = new ArrayList<>();
        var attachment = new JpaModels.EvidenceEntity();
        attachment.id = job.evidenceIds.getFirst();
        attachment.evidenceType = EvidenceType.OFFICIAL_ATTACHMENT;
        attachment.sourceUrl = "https://example.gov.cn/download?fileUrl=fresh-token";
        attachment.capturedAt = Instant.parse("2026-08-21T07:00:00Z");
        when(jobs.findAllById(Set.of(job.id))).thenReturn(List.of(job));
        when(organizations.findAllById(Set.of(organization.id))).thenReturn(List.of(organization));
        when(events.findAllById(Set.of(event.id))).thenReturn(List.of(event));
        when(events.findBySourceUrlIn(Set.of(job.sourceUrl))).thenReturn(List.of());
        when(evidence.findAllById(Set.of(attachment.id))).thenReturn(List.of(attachment));
        var store = new JpaDecisionStore(jobs, organizations, events, evidence,
            mock(EligibilityAssessmentJpaRepository.class), mock(FitAssessmentJpaRepository.class),
            mock(StabilityAssessmentJpaRepository.class), mock(DecisionAssessmentJpaRepository.class),
            mock(AssessmentDimensionJpaRepository.class), mock(OrganizationStabilityFactJpaRepository.class));

        var context = store.findActiveByJobIds(Set.of(job.id)).getFirst();

        assertThat(context.event().sourceUrl()).isEqualTo(attachment.sourceUrl);
        verify(evidence).findAllById(Set.of(attachment.id));
    }

    @Test
    void restoresOfficialEmploymentIdentityFieldsFromPersistence() {
        var jobs = mock(JobPostingJpaRepository.class);
        var organizations = mock(OrganizationJpaRepository.class);
        var events = mock(RecruitmentEventJpaRepository.class);
        var evidence = mock(EvidenceJpaRepository.class);
        var job = job();
        job.actualEmployer = "杭州数科有限公司";
        job.worksite = "滨江区";
        job.employmentEvidence = "用工性质：国企正式劳动合同";
        var organization = organization(job);
        var event = event(job);
        when(jobs.findAllById(Set.of(job.id))).thenReturn(List.of(job));
        when(organizations.findAllById(Set.of(organization.id))).thenReturn(List.of(organization));
        when(events.findAllById(Set.of(event.id))).thenReturn(List.of(event));
        when(events.findBySourceUrlIn(Set.of(job.sourceUrl))).thenReturn(List.of());
        when(evidence.findAllById(Set.copyOf(job.evidenceIds))).thenReturn(List.of());

        var context = store(jobs, organizations, events, evidence)
            .findActiveByJobIds(Set.of(job.id)).getFirst();

        assertThat(context.job().actualEmployer()).isEqualTo("杭州数科有限公司");
        assertThat(context.job().worksite()).isEqualTo("滨江区");
        assertThat(context.job().employmentEvidence()).isEqualTo("用工性质：国企正式劳动合同");
    }

    @Test
    void loadsAttachmentEvidenceOnceForTheWholeActiveJobCollection() {
        var jobs = mock(JobPostingJpaRepository.class);
        var organizations = mock(OrganizationJpaRepository.class);
        var events = mock(RecruitmentEventJpaRepository.class);
        var evidence = mock(EvidenceJpaRepository.class);
        var first = job();
        var second = job();
        var organizationValues = List.of(organization(first), organization(second));
        var eventValues = List.of(event(first), event(second));
        var firstEvidence = attachment(first, "https://example.gov.cn/first.xlsx");
        var secondEvidence = attachment(second, "https://example.gov.cn/second.xlsx");
        when(jobs.findByActiveTrue()).thenReturn(List.of(first, second));
        when(organizations.findAllById(Set.of(first.organizationId, second.organizationId))).thenReturn(organizationValues);
        when(events.findAllById(Set.of(first.recruitmentEventId, second.recruitmentEventId))).thenReturn(eventValues);
        when(events.findBySourceUrlIn(Set.of(first.sourceUrl))).thenReturn(List.of());
        when(evidence.findAllById(Set.of(firstEvidence.id, secondEvidence.id))).thenReturn(List.of(firstEvidence, secondEvidence));
        var store = store(jobs, organizations, events, evidence);

        var contexts = store.findActive();

        assertThat(contexts).extracting(value -> value.event().sourceUrl())
            .containsExactly(firstEvidence.sourceUrl, secondEvidence.sourceUrl);
        verify(evidence, times(1)).findAllById(Set.of(firstEvidence.id, secondEvidence.id));
    }

    @Test void parsedAnnouncementFactsKeepTheirEvidenceStatesAndTheOriginalEventIdentity() {
        var jobs = mock(JobPostingJpaRepository.class);
        var organizations = mock(OrganizationJpaRepository.class);
        var events = mock(RecruitmentEventJpaRepository.class);
        var evidence = mock(EvidenceJpaRepository.class);
        var job = job();
        var organization = organization(job);
        var original = event(job);
        var announcement = event(job);
        announcement.id = UUID.randomUUID();
        announcement.sourceUrl = job.sourceUrl;
        announcement.title = "官网当前公告";
        announcement.graduateRuleJson = new com.careeros.domain.GraduateEligibilityRule(2026, Set.of(2026),
            Set.of(com.careeros.domain.GraduateEligibilityRule.CohortScope.CURRENT_YEAR), false,
            com.careeros.domain.GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null,
            com.careeros.domain.GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null,
            true, true, "报名时未落实单位且无社保",
            com.careeros.domain.GraduateEligibilityRule.EvidenceState.CONFIRMED);
        announcement.noticeState = com.careeros.domain.GraduateEligibilityRule.EvidenceState.CONFIRMED;
        announcement.applicationState = com.careeros.domain.GraduateEligibilityRule.EvidenceState.REVIEW_REQUIRED;
        announcement.writtenExamState = com.careeros.domain.GraduateEligibilityRule.EvidenceState.NOT_REQUIRED;
        announcement.interviewState = com.careeros.domain.GraduateEligibilityRule.EvidenceState.CONFIRMED;
        announcement.interviewRule = "结构化面试";
        announcement.interviewMethod = "现场";
        announcement.scoreFormula = "面试成绩";
        announcement.appointmentState = com.careeros.domain.GraduateEligibilityRule.EvidenceState.REVIEW_REQUIRED;
        announcement.appointmentRule = "聘用前复核";
        UUID originalEvidence = UUID.randomUUID();
        UUID announcementEvidence = UUID.randomUUID();
        original.evidenceIds.add(originalEvidence);
        announcement.evidenceIds.add(announcementEvidence);
        when(jobs.findAllById(Set.of(job.id))).thenReturn(List.of(job));
        when(organizations.findAllById(Set.of(organization.id))).thenReturn(List.of(organization));
        when(events.findAllById(Set.of(original.id))).thenReturn(List.of(original));
        when(events.findBySourceUrlIn(Set.of(job.sourceUrl))).thenReturn(List.of(announcement));
        when(evidence.findAllById(Set.copyOf(job.evidenceIds))).thenReturn(List.of());

        var projected = store(jobs, organizations, events, evidence).findActiveByJobIds(Set.of(job.id)).getFirst().event();

        assertThat(projected.id()).isEqualTo(original.id);
        assertThat(projected.title()).isEqualTo(announcement.title);
        assertThat(projected.sourceUrl()).isEqualTo(original.sourceUrl);
        assertThat(projected.evidenceIds()).containsExactly(originalEvidence, announcementEvidence);
        assertThat(projected.graduateEligibilityRule()).isEqualTo(announcement.graduateRuleJson);
        assertThat(projected.writtenExamState()).isEqualTo(announcement.writtenExamState);
        assertThat(projected.interviewMethod()).isEqualTo(announcement.interviewMethod);
        assertThat(projected.scoreFormula()).isEqualTo(announcement.scoreFormula);
        assertThat(projected.processFacts().notice().state()).isEqualTo(announcement.noticeState);
        assertThat(projected.processFacts().application().state()).isEqualTo(announcement.applicationState);
        assertThat(projected.processFacts().interview().detail()).isEqualTo(announcement.interviewRule);
        assertThat(projected.processFacts().appointment().state()).isEqualTo(announcement.appointmentState);
        assertThat(projected.processFacts().appointment().detail()).isEqualTo(announcement.appointmentRule);
    }

    private static JpaDecisionStore store(JobPostingJpaRepository jobs,
                                          OrganizationJpaRepository organizations,
                                          RecruitmentEventJpaRepository events,
                                          EvidenceJpaRepository evidence) {
        return new JpaDecisionStore(jobs, organizations, events, evidence,
            mock(EligibilityAssessmentJpaRepository.class), mock(FitAssessmentJpaRepository.class),
            mock(StabilityAssessmentJpaRepository.class), mock(DecisionAssessmentJpaRepository.class),
            mock(AssessmentDimensionJpaRepository.class), mock(OrganizationStabilityFactJpaRepository.class));
    }

    private static JpaModels.OrganizationEntity organization(JpaModels.JobPostingEntity job) {
        var organization = new JpaModels.OrganizationEntity();
        organization.id = job.organizationId;
        organization.name = "浙江省测试事业单位";
        organization.organizationType = OrganizationType.PUBLIC_INSTITUTION;
        return organization;
    }

    private static JpaModels.RecruitmentEventEntity event(JpaModels.JobPostingEntity job) {
        var event = new JpaModels.RecruitmentEventEntity();
        event.id = job.recruitmentEventId;
        event.title = "2026年公开招聘";
        event.recruitmentYear = 2026;
        event.eventType = EventType.PUBLIC_INSTITUTION;
        event.sourceUrl = "https://example.gov.cn/download?fileUrl=expired-token";
        event.defaultEmploymentType = EmploymentType.PUBLIC_INSTITUTION_FORMAL;
        event.evidenceIds = new ArrayList<>();
        return event;
    }

    private static JpaModels.EvidenceEntity attachment(JpaModels.JobPostingEntity job, String sourceUrl) {
        var attachment = new JpaModels.EvidenceEntity();
        attachment.id = job.evidenceIds.getFirst();
        attachment.evidenceType = EvidenceType.OFFICIAL_ATTACHMENT;
        attachment.sourceUrl = sourceUrl;
        attachment.capturedAt = Instant.parse("2026-08-21T07:00:00Z");
        return attachment;
    }

    private static JpaModels.JobPostingEntity job() {
        var job = new JpaModels.JobPostingEntity();
        job.id = UUID.randomUUID();
        job.recruitmentEventId = UUID.randomUUID();
        job.organizationId = UUID.randomUUID();
        job.title = "信息中心岗";
        job.jobFamily = JobFamily.INFORMATION_SYSTEMS;
        job.employmentType = EmploymentType.PUBLIC_INSTITUTION_FORMAL;
        job.headcount = 1;
        job.minimumEducation = EducationLevel.MASTER;
        job.exactMajors = new java.util.LinkedHashSet<>();
        job.acceptedGraduationYears = new java.util.LinkedHashSet<>();
        job.requiredProfessionalTitles = new java.util.LinkedHashSet<>();
        job.sourceUrl = "https://example.gov.cn/notice";
        job.evidenceIds = new ArrayList<>(List.of(UUID.randomUUID()));
        job.contentFingerprint = "a".repeat(64);
        job.active = true;
        return job;
    }
}
