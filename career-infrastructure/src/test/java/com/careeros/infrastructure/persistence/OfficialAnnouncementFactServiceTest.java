package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.EmploymentType.PUBLIC_INSTITUTION_FORMAL;
import static com.careeros.domain.DomainEnums.EventType.PUBLIC_INSTITUTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.careeros.infrastructure.acquisition.OfficialAnnouncementFactParser.OfficialAnnouncementFacts;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialAnnouncementFactServiceTest {
    @Test
    void createsEvidenceBackedAnnouncementEventWithAllSharedRules() {
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        when(events.findFirstBySourceUrl(any())).thenReturn(Optional.empty());
        when(events.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UUID evidenceId = UUID.randomUUID();
        var service = new OfficialAnnouncementFactService(events);

        var event = service.upsert("2026年杭州市市属事业单位统一公开招聘工作人员公告",
            "https://hrss.hangzhou.gov.cn/notice.html", 2026, PUBLIC_INSTITUTION, facts(), evidenceId);

        assertThat(event.applicationStartsAt).isEqualTo(OffsetDateTime.parse("2026-03-19T09:00:00+08:00"));
        assertThat(event.applicationEndsAt).isEqualTo(OffsetDateTime.parse("2026-03-25T16:00:00+08:00"));
        assertThat(event.ageReferenceDate).isEqualTo(LocalDate.of(2026, 3, 19));
        assertThat(event.registrationUrl).isEqualTo("http://qssy.zjks.com");
        assertThat(event.writtenExamSubjects).containsExactly("综合应用能力", "职业能力倾向测验");
        assertThat(event.overseasDegreeRule).contains("留服认证");
        assertThat(event.defaultEmploymentType).isEqualTo(PUBLIC_INSTITUTION_FORMAL);
        assertThat(event.evidenceIds).containsExactly(evidenceId);
    }

    @Test
    void reparseAppliesNewExplicitOfficialValuesWithoutNullOverwrites() {
        RecruitmentEventJpaRepository events = mock(RecruitmentEventJpaRepository.class);
        var existing = new JpaModels.RecruitmentEventEntity();
        existing.id = UUID.randomUUID();
        existing.title = "原公告";
        existing.sourceUrl = "https://hrss.hangzhou.gov.cn/notice.html";
        existing.recruitmentYear = 2026;
        existing.eventType = PUBLIC_INSTITUTION;
        existing.applicationEndsAt = OffsetDateTime.parse("2026-03-25T16:00:00+08:00");
        existing.registrationUrl = "https://original.example.cn";
        existing.employmentStatement = "签订聘用合同";
        existing.defaultEmploymentType = PUBLIC_INSTITUTION_FORMAL;
        existing.evidenceIds = new java.util.ArrayList<>();
        when(events.findFirstBySourceUrl(existing.sourceUrl)).thenReturn(Optional.of(existing));
        when(events.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new OfficialAnnouncementFactService(events);
        var conflicting = new OfficialAnnouncementFacts(
            null, null, OffsetDateTime.parse("2026-03-26T16:00:00+08:00"),
            null, null, null, "https://changed.example.cn", null, null, null,
            List.of(), null, null, null, null, null, Map.of());
        UUID newEvidence = UUID.randomUUID();

        var saved = service.upsert(existing.title, existing.sourceUrl, 2026,
            PUBLIC_INSTITUTION, conflicting, newEvidence);

        assertThat(saved.applicationEndsAt)
            .isEqualTo(OffsetDateTime.parse("2026-03-26T16:00:00+08:00"));
        assertThat(saved.registrationUrl).isEqualTo("https://changed.example.cn");
        assertThat(saved.employmentStatement).isEqualTo("签订聘用合同");
        assertThat(saved.defaultEmploymentType).isEqualTo(PUBLIC_INSTITUTION_FORMAL);
        assertThat(saved.evidenceIds).containsExactly(newEvidence);
    }

    private static OfficialAnnouncementFacts facts() {
        return new OfficialAnnouncementFacts(
            LocalDate.of(2026, 3, 17),
            OffsetDateTime.parse("2026-03-19T09:00:00+08:00"),
            OffsetDateTime.parse("2026-03-25T16:00:00+08:00"),
            OffsetDateTime.parse("2026-03-26T17:00:00+08:00"),
            OffsetDateTime.parse("2026-03-28T00:00:00+08:00"),
            LocalDate.of(2026, 3, 19), "http://qssy.zjks.com",
            LocalDate.of(2026, 4, 20), LocalDate.of(2026, 4, 25), LocalDate.of(2026, 4, 25),
            List.of("综合应用能力", "职业能力倾向测验"), "应届生规则", "留服认证规则",
            "经历证明规则", "签订聘用合同", "结构化面试", Map.of("employmentStatement", "签订聘用合同"));
    }
}
