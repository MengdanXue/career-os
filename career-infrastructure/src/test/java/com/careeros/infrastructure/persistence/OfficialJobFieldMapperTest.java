package com.careeros.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialJobFieldMapperTest {
    @Test
    void mapsHospitalInformationJobIntoCandidateRelevantOfficialFields() {
        var mapper = new OfficialJobFieldMapper();
        var context = new OfficialJobFieldMapper.ImportContext(
            UUID.randomUUID(), UUID.randomUUID(), "杭州市第一人民医院", "杭州",
            LocalDate.of(2024, 12, 31), "https://zp.hz-hospital.com/index/index/announcement_desc/id/212.html",
            "https://zp.hz-hospital.com/index/index/announcement_desc/id/212.html", List.of());
        var row = new OfficialJobFieldMapper.RawOfficialJob(
            "X-信息中心|信息工作人员", "信息工作人员", null,
            "计算机科学与技术、软件工程", "硕士研究生/硕士", null, "1",
            "应届毕业生", "38周岁及以下", null, null,
            "X-信息中心", "专业技术");

        var job = mapper.toNormalizedJob(row, context);

        assertThat(job.jobFamily()).isEqualTo(com.careeros.domain.DomainEnums.JobFamily.INFORMATION_SYSTEMS);
        assertThat(job.minimumEducation()).isEqualTo(com.careeros.domain.DomainEnums.EducationLevel.MASTER);
        assertThat(job.maximumAge()).isEqualTo(38);
        assertThat(job.exactMajors()).contains("计算机科学与技术", "软件工程");
        assertThat(job.candidateScope()).contains("应届毕业生");
        assertThat(job.jobCategory()).isEqualTo("专业技术");
    }

    @Test
    void doesNotTreatDomainWorkThatMentionsResearchAsCandidateTargetResearch() {
        assertThat(OfficialJobFieldMapper.jobFamily(
            "财务人员", "从事医院财务管理及相关研究工作", "会计学、财务管理", "区卫生健康局"))
            .isEqualTo(com.careeros.domain.DomainEnums.JobFamily.OTHER);
    }

    @Test
    void keepsExplicitResearchPositionsInResearchFamily() {
        assertThat(OfficialJobFieldMapper.jobFamily(
            "科研助理", "承担科研项目支撑", "计算机科学与技术", "人工智能研究院"))
            .isEqualTo(com.careeros.domain.DomainEnums.JobFamily.AI);
        assertThat(OfficialJobFieldMapper.jobFamily(
            "科研助理", "承担科研项目支撑", "生物学", "生命科学研究院"))
            .isEqualTo(com.careeros.domain.DomainEnums.JobFamily.RESEARCH);
    }
}
