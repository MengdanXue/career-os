package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.CandidateFacts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class CandidateMatchServiceTest {
    private static final UUID CANDIDATE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void listsProfileMatchesEvenWhenEmploymentIdentityStillNeedsConfirmation() {
        CandidateProfile candidate = candidate();
        JobContext context = context();
        JobAdmission admission = new JobAdmission(context.job().id(), DataQualityStatus.NORMALIZED,
            TargetScopeStatus.NEEDS_REVIEW, Set.of(JobAdmissionReason.TARGET_TECHNICAL_ROLE,
            JobAdmissionReason.EMPLOYMENT_IDENTITY_UNKNOWN), "admission-v2", NOW, false);
        var service = new CandidateMatchService(
            candidates(candidate), facts(candidate), contexts(context),
            admissions(admission), new EligibilityEvaluator(), new FitEvaluator());

        var page = service.list(CANDIDATE_ID, new CandidateMatchService.MatchQuery(0, 20), NOW);

        assertThat(page.total()).isEqualTo(1);
        var match = page.items().getFirst();
        assertThat(match.jobTitle()).isEqualTo("信息中心工作人员");
        assertThat(match.organizationName()).isEqualTo("杭州市西溪医院");
        // 这个 fixture 的公告里有应届原文（"2024年、2025年、2026年毕业生可报考"）但没有解析出
        // 结构化条款——即解析失败。读不到不等于没限制，所以硬资格是待确认而不是可报。
        // 本用例的重点是"即使有待确认项也仍然出现在匹配队列里"，这一点下面继续断言。
        assertThat(match.eligibilityStatus()).isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
        assertThat(match.fitScore()).isPositive();
        assertThat(match.employmentIdentityConfirmed()).isFalse();
        assertThat(match.actualEmployer()).isEqualTo("杭州市西溪医院");
        assertThat(match.worksite()).isEqualTo("西溪院区");
        assertThat(match.employmentEvidence()).isEqualTo("公告原文：医院直接聘用");
        assertThat(match.warnings()).contains("用工身份待官方证据确认");
        assertThat(match.sourceUrl()).startsWith("https://hrss.hangzhou.gov.cn/");
        assertThat(match.externalJobCode()).isEqualTo("101");
        assertThat(match.headcount()).isEqualTo(1);
        assertThat(match.jobFamily()).isEqualTo(JobFamily.INFORMATION_SYSTEMS);
        assertThat(match.minimumEducation()).isEqualTo(EducationLevel.MASTER);
        assertThat(match.exactMajors()).containsExactly("计算机科学与技术");
        assertThat(match.maximumAge()).isEqualTo(38);
        assertThat(match.ageReferenceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(match.duties()).isEqualTo("医院信息系统建设和数据库管理");
        assertThat(match.eventTitle()).isEqualTo("2026年公开招聘");
        assertThat(match.publishedOn()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(match.applicationStartsOn()).isNull();
        assertThat(match.applicationEndsOn()).isNull();
        assertThat(match.jobCategory()).isEqualTo("专业技术");
        assertThat(match.jobGrade()).isEqualTo("十级以下");
        assertThat(match.educationRequirementText()).isEqualTo("硕士研究生及以上");
        assertThat(match.degreeRequirement()).isEqualTo("硕士及以上");
        assertThat(match.majorRequirementText()).isEqualTo("计算机科学与技术、软件工程");
        assertThat(match.ageRequirementText()).isEqualTo("38周岁及以下");
        assertThat(match.genderRequirement()).isEqualTo("不限");
        assertThat(match.interviewRatio()).isEqualTo("1:4");
        assertThat(match.professionalTestRequired()).isTrue();
        assertThat(match.contactPhone()).isEqualTo("0571-12345678");
        assertThat(match.attachmentSourceUrl()).endsWith("jobs.xlsx");
        assertThat(match.applicationStartsAt()).isEqualTo(OffsetDateTime.parse("2026-03-19T09:00:00+08:00"));
        assertThat(match.applicationEndsAt()).isEqualTo(OffsetDateTime.parse("2026-03-25T16:00:00+08:00"));
        assertThat(match.registrationUrl()).isEqualTo("https://qssy.zjks.com");
        assertThat(match.writtenExamOn()).isEqualTo(LocalDate.of(2026, 4, 25));
        assertThat(match.writtenExamSubjects()).containsExactly("职业能力倾向测验", "综合应用能力");
        assertThat(match.graduateRule()).contains("2024年、2025年、2026年");
        assertThat(match.employmentStatement()).contains("签订聘用合同");
        assertThat(match.dataQualityStatus()).isEqualTo(DataQualityStatus.NORMALIZED);
    }

    private static RepositoryPorts.CandidateProfiles candidates(CandidateProfile candidate) {
        return new RepositoryPorts.CandidateProfiles() {
            public CandidateProfile save(CandidateProfile value) { return value; }
            public Optional<CandidateProfile> findById(UUID id) { return id.equals(candidate.id()) ? Optional.of(candidate) : Optional.empty(); }
            public Optional<CandidateProfile> findByIdForUpdate(UUID id) { return findById(id); }
            public List<CandidateProfile> findAll() { return List.of(candidate); }
            public void deleteById(UUID id) {}
        };
    }

    private static List<CandidateFactConfirmation> confirmations(CandidateProfile candidate) {
        return Arrays.stream(CandidateFactKey.values()).map(key -> new CandidateFactConfirmation(
            candidate.id(), key, CandidateFactStatus.CONFIRMED, CandidateFacts.fingerprint(candidate, key),
            CandidateFactSource.USER_CONFIRMED, NOW, NOW)).toList();
    }

    private static RepositoryPorts.CandidateFactConfirmations facts(CandidateProfile candidate) {
        return new RepositoryPorts.CandidateFactConfirmations() {
            public List<CandidateFactConfirmation> findByCandidateId(UUID candidateId) {
                return candidate.id().equals(candidateId) ? confirmations(candidate) : List.of();
            }
            public List<CandidateFactConfirmation> saveAll(List<CandidateFactConfirmation> values) {
                return List.copyOf(values);
            }
        };
    }

    private static JobContexts contexts(JobContext context) {
        return new JobContexts() {
            public Optional<JobContext> findByJobId(UUID id) { return id.equals(context.job().id()) ? Optional.of(context) : Optional.empty(); }
            public List<JobContext> findActive() { return List.of(context); }
        };
    }

    private static JobAdmissions admissions(JobAdmission admission) {
        return new JobAdmissions() {
            public Optional<JobAdmission> findByJobId(UUID id) { return id.equals(admission.jobPostingId()) ? Optional.of(admission) : Optional.empty(); }
            public List<JobAdmission> findCandidateMatches() { return List.of(admission); }
            public JobAdmission save(JobAdmission value) { return value; }
            public JobAdmissionPorts.AdmissionSummary summarize() { throw new UnsupportedOperationException(); }
        };
    }

    private static CandidateProfile candidate() {
        return new CandidateProfile(CANDIDATE_ID, "候选人", new PartialDate(1992, 12, null),
            EducationLevel.MASTER, Set.of("计算机科学与技术"), null, null,
            Set.of("中级：计算机应用（评审）"), List.of("杭州", "浙江"),
            Set.of(EmploymentType.ESTABLISHMENT, EmploymentType.CONTRACT), "profile-v1");
    }

    private static JobContext context() {
        UUID jobId = UUID.randomUUID(), eventId = UUID.randomUUID(), organizationId = UUID.randomUUID();
        var job = new JobPosting(jobId, eventId, organizationId, "101", "信息中心工作人员",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.UNKNOWN, "杭州", 1, EducationLevel.MASTER,
            Set.of("计算机科学与技术"), Set.of(), 38, LocalDate.of(2026, 8, 1), 0,
            Set.of(), "医院信息系统建设和数据库管理",
            "https://hrss.hangzhou.gov.cn/art/2026/notice.html", List.of(),
            "杭州市卫生健康委员会", "专业技术", "十级以下", "硕士研究生及以上",
            "硕士及以上", "计算机科学与技术、软件工程", "38周岁及以下", "不限",
            "不限", "需进行专业知识测试", "岗位原始条件", "1:4", true, "0571-12345678",
            "杭州市西溪医院", "西溪院区", "公告原文：医院直接聘用");
        var organization = new Organization(organizationId, "杭州市西溪医院", OrganizationType.HOSPITAL,
            null, "浙江", "杭州", null, null, null);
        var event = new RecruitmentEvent(eventId, "2026年公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            LocalDate.of(2026, 7, 1), null, null,
            "https://hrss.hangzhou.gov.cn/jobs.xlsx", EmploymentType.UNKNOWN, List.of(),
            OffsetDateTime.parse("2026-03-19T09:00:00+08:00"),
            OffsetDateTime.parse("2026-03-25T16:00:00+08:00"), LocalDate.of(2026, 3, 19),
            "https://qssy.zjks.com", OffsetDateTime.parse("2026-03-26T17:00:00+08:00"),
            OffsetDateTime.parse("2026-03-28T00:00:00+08:00"), LocalDate.of(2026, 4, 20),
            LocalDate.of(2026, 4, 25), LocalDate.of(2026, 4, 25),
            List.of("职业能力倾向测验", "综合应用能力"), "2024年、2025年、2026年毕业生可报考",
            "境外学历须完成认证", "工作经历须提供证明", "公示后签订聘用合同", "按笔试成绩确定面试人选");
        return new JobContext(job, organization, event, "b".repeat(64), true);
    }
}
