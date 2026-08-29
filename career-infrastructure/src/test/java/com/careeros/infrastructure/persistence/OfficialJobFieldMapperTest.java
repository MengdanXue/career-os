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

    @Test
    void mapsOnlyExplicitEmploymentIdentityWording() {
        assertThat(List.of(
            OfficialJobFieldMapper.employmentType("事业编制").name(),
            OfficialJobFieldMapper.employmentType("员额制管理").name(),
            OfficialJobFieldMapper.employmentType("备案制人员").name(),
            OfficialJobFieldMapper.employmentType("单位正式聘用").name(),
            OfficialJobFieldMapper.employmentType("国企正式劳动合同").name(),
            OfficialJobFieldMapper.employmentType("编外聘用").name(),
            OfficialJobFieldMapper.employmentType("项目聘用").name(),
            OfficialJobFieldMapper.employmentType("劳务派遣").name(),
            OfficialJobFieldMapper.employmentType("聘用制").name(),
            OfficialJobFieldMapper.employmentType(null).name()))
            .containsExactly(
                "ESTABLISHMENT", "QUOTA_OR_FILING", "QUOTA_OR_FILING",
                "UNIT_FORMAL", "SOE_FORMAL", "CONTRACT", "PROJECT_BASED",
                "LABOR_DISPATCH", "UNKNOWN", "UNKNOWN");
    }

    @Test
    void negativeAndThirdPartyWordingWinsOverFormalKeywords() {
        assertThat(OfficialJobFieldMapper.employmentType("非正式员工，由劳务派遣公司签约"))
            .isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.LABOR_DISPATCH);
        assertThat(OfficialJobFieldMapper.employmentType("国企发布，录用后与第三方签订劳动合同"))
            .isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.CONTRACT);
        assertThat(OfficialJobFieldMapper.employmentType("不属于单位正式聘用人员"))
            .isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.UNKNOWN);
        assertThat(OfficialJobFieldMapper.employmentType("非正式国企劳动合同人员"))
            .isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.CONTRACT);
        assertThat(OfficialJobFieldMapper.employmentType("不属于事业编制"))
            .isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.UNKNOWN);
        assertThat(OfficialJobFieldMapper.employmentType("非事业编制岗位"))
            .isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.UNKNOWN);
        assertThat(OfficialJobFieldMapper.employmentType("无事业编制"))
            .isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.UNKNOWN);
        assertThat(OfficialJobFieldMapper.employmentType("不占事业编制"))
            .isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.UNKNOWN);
        assertThat(OfficialJobFieldMapper.employmentType("不纳入员额或备案制"))
            .isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.UNKNOWN);
    }

    @Test
    void rowIdentityFieldsWinAndAnnouncementDefaultsOnlyFillBlanks() {
        var mapper = new OfficialJobFieldMapper();
        var context = new OfficialJobFieldMapper.ImportContext(
            UUID.randomUUID(), UUID.randomUUID(), "杭州数据集团", "杭州",
            LocalDate.of(2027, 1, 1), "https://example.gov.cn/notice", "https://example.gov.cn/jobs.xlsx",
            List.of(), com.careeros.domain.DomainEnums.EmploymentType.SOE_FORMAL,
            "杭州数据集团用人子公司", "杭州市", "公告：国企正式劳动合同");
        var explicitRow = new OfficialJobFieldMapper.RawOfficialJob(
            "A1", "Java开发", null, "计算机科学与技术", "硕士", "单位正式聘用", "1",
            null, null, null, null, "技术部", "专业技术", "杭州数科有限公司", "滨江区");
        var defaultedRow = new OfficialJobFieldMapper.RawOfficialJob(
            "A2", "数据平台", null, "计算机科学与技术", "硕士", null, "1",
            null, null, null, null, "技术部", "专业技术", null, null);
        var mixedRow = new OfficialJobFieldMapper.RawOfficialJob(
            "A3", "AI应用", null, "计算机科学与技术", "硕士", null, "1",
            null, null, null, null, "技术部", "专业技术", "杭州数科有限公司", "余杭区");

        var explicit = mapper.toNormalizedJob(explicitRow, context);
        var defaulted = mapper.toNormalizedJob(defaultedRow, context);
        var mixed = mapper.toNormalizedJob(mixedRow, context);

        assertThat(explicit.employmentType()).isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.UNIT_FORMAL);
        assertThat(explicit.actualEmployer()).isEqualTo("杭州数科有限公司");
        assertThat(explicit.worksite()).isEqualTo("滨江区");
        assertThat(explicit.location()).isEqualTo("杭州");
        assertThat(explicit.employmentEvidence())
            .contains("用工性质：单位正式聘用", "实际用人单位：杭州数科有限公司", "工作地点：滨江区");
        assertThat(defaulted.employmentType()).isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.SOE_FORMAL);
        assertThat(defaulted.actualEmployer()).isEqualTo("杭州数据集团用人子公司");
        assertThat(defaulted.worksite()).isEqualTo("杭州市");
        assertThat(defaulted.employmentEvidence()).contains("公告：国企正式劳动合同");
        assertThat(mixed.employmentType()).isEqualTo(com.careeros.domain.DomainEnums.EmploymentType.SOE_FORMAL);
        assertThat(mixed.location()).isEqualTo("杭州");
        assertThat(mixed.worksite()).isEqualTo("余杭区");
        assertThat(mixed.employmentEvidence())
            .contains("公告：国企正式劳动合同", "实际用人单位：杭州数科有限公司", "工作地点：余杭区");
    }

    @Test
    void duplicateAnnouncementAndRowIdentityEvidenceIsStoredOnce() {
        var context = new OfficialJobFieldMapper.ImportContext(
            UUID.randomUUID(), UUID.randomUUID(), "杭州市信息中心", "杭州", null,
            "https://example.gov.cn/notice", "https://example.gov.cn/jobs.xlsx", List.of(),
            com.careeros.domain.DomainEnums.EmploymentType.ESTABLISHMENT,
            null, null, "用工性质： 事业编制");
        var row = new OfficialJobFieldMapper.RawOfficialJob(
            "A1", "信息化岗", null, "计算机科学与技术", "硕士", "事业编制", "1",
            null, null, null, null, null, null, null, null);

        assertThat(new OfficialJobFieldMapper().toNormalizedJob(row, context).employmentEvidence().replace(" ", ""))
            .isEqualTo("用工性质：事业编制");
    }
}
