package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class OfficialJobAdmissionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void classifiesTechnicalRowsAndKeepsUnknownEmploymentAsAnExplicitWarning() {
        JobContext technical = context("信息中心工作人员", JobFamily.INFORMATION_SYSTEMS,
            EmploymentType.UNKNOWN, EducationLevel.MASTER, "负责医院信息系统建设");
        JobContext doctorate = context("人工智能研究", JobFamily.AI,
            EmploymentType.UNKNOWN, EducationLevel.DOCTORATE, "算法研究");
        JobContext teacher = context("人工智能专业教师", JobFamily.AI,
            EmploymentType.UNKNOWN, EducationLevel.MASTER, "承担教学");
        MemoryAdmissions admissions = new MemoryAdmissions();
        var service = service(contexts(technical, doctorate, teacher), admissions,
            technical, doctorate, teacher);

        service.classify(List.of(
            technical.job().id(), doctorate.job().id(), teacher.job().id()), NOW);

        assertThat(admissions.values).containsKeys(
            technical.job().id(), doctorate.job().id(), teacher.job().id());
        JobAdmission included = admissions.values.get(technical.job().id());
        assertThat(included.dataQualityStatus()).isEqualTo(DataQualityStatus.NORMALIZED);
        assertThat(included.targetScopeStatus()).isEqualTo(TargetScopeStatus.NEEDS_REVIEW);
        assertThat(included.reasonCodes()).contains(
            JobAdmissionReason.TARGET_TECHNICAL_ROLE,
            JobAdmissionReason.EMPLOYMENT_IDENTITY_UNKNOWN,
            JobAdmissionReason.OFFICIAL_FACTS_INCOMPLETE,
            JobAdmissionReason.MISSING_FIELD_EVIDENCE);
        assertThat(included.admits(technical.job())).isFalse();
        assertThat(admissions.values.get(doctorate.job().id()).reasonCodes())
            .contains(JobAdmissionReason.DOCTOR_REQUIRED);
        assertThat(admissions.values.get(teacher.job().id()).reasonCodes())
            .contains(JobAdmissionReason.TEACHING_ROLE);
        assertThat(admissions.values.get(doctorate.job().id()).targetScopeStatus())
            .isEqualTo(TargetScopeStatus.EXCLUDED);
        assertThat(admissions.values.get(teacher.job().id()).targetScopeStatus())
            .isEqualTo(TargetScopeStatus.EXCLUDED);
    }

    @Test
    void verifiesOnlyEvidenceBackedJobsWithCompleteCriticalFacts() {
        JobContext complete = context("信息中心工作人员", JobFamily.INFORMATION_SYSTEMS,
            EmploymentType.PUBLIC_INSTITUTION_FORMAL, EducationLevel.MASTER,
            "负责医院信息系统建设", List.of(UUID.randomUUID()));
        MemoryAdmissions admissions = new MemoryAdmissions();

        service(contexts(complete), admissions, complete)
            .classify(List.of(complete.job().id()), NOW);

        JobAdmission admission = admissions.values.get(complete.job().id());
        assertThat(admission.dataQualityStatus()).isEqualTo(DataQualityStatus.VERIFIED);
        assertThat(admission.reasonCodes()).doesNotContain(
            JobAdmissionReason.OFFICIAL_FACTS_INCOMPLETE,
            JobAdmissionReason.MISSING_FIELD_EVIDENCE);
    }

    @Test
    void conflictingOrMissingFieldEvidenceCannotBeAutomaticallyVerified() {
        JobContext official = context("信息中心工作人员", JobFamily.INFORMATION_SYSTEMS,
            EmploymentType.PUBLIC_INSTITUTION_FORMAL, EducationLevel.MASTER,
            "负责医院信息系统建设", List.of(UUID.randomUUID()));
        MemoryAdmissions admissions = new MemoryAdmissions();
        var coverage = new JobAdmissionPorts.FieldEvidenceCoverage(Set.of(
            "title", "organizationName", "headcount", "educationRequirementText",
            "majorRequirementText"), Set.of("ageRequirementText"), true, true, true);
        var service = new OfficialJobAdmissionService(contexts(official), admissions, jobId -> coverage);

        service.classify(List.of(official.job().id()), NOW);

        JobAdmission admission = admissions.values.get(official.job().id());
        assertThat(admission.dataQualityStatus()).isEqualTo(DataQualityStatus.REVIEW_REQUIRED);
        assertThat(admission.targetScopeStatus()).isEqualTo(TargetScopeStatus.NEEDS_REVIEW);
        assertThat(admission.reasonCodes()).contains(
            JobAdmissionReason.MISSING_FIELD_EVIDENCE,
            JobAdmissionReason.OFFICIAL_FACTS_INCOMPLETE);
    }

    @Test
    void workbookClassificationNeverEntersTheTrustedQueueWithoutEvidenceReview() {
        JobContext official = context("信息中心工作人员", JobFamily.INFORMATION_SYSTEMS,
            EmploymentType.ESTABLISHMENT, EducationLevel.MASTER, "负责医院信息系统建设");
        MemoryAdmissions admissions = new MemoryAdmissions();
        var service = service(contexts(official), admissions, official);

        var classified = service.classify(List.of(official.job().id()), NOW);

        assertThat(classified.getFirst().targetScopeStatus()).isEqualTo(TargetScopeStatus.NEEDS_REVIEW);
        assertThat(classified.getFirst().admitted()).isFalse();
    }

    @Test
    void unchangedHumanVerifiedAdmissionIsNotOverwritten() {
        JobContext official = context("信息中心工作人员", JobFamily.INFORMATION_SYSTEMS,
            EmploymentType.ESTABLISHMENT, EducationLevel.MASTER, "负责医院信息系统建设");
        MemoryAdmissions admissions = new MemoryAdmissions();
        JobAdmission human = new JobAdmission(official.job().id(), DataQualityStatus.VERIFIED,
            TargetScopeStatus.INCLUDED, Set.of(JobAdmissionReason.TARGET_TECHNICAL_ROLE),
            "human-review", NOW.minusSeconds(60), true);
        admissions.save(human);
        var service = service(contexts(official), admissions, official);

        var classified = service.classify(List.of(official.job().id()), NOW);

        assertThat(classified).containsExactly(human);
        assertThat(admissions.findByJobId(official.job().id())).contains(human);
    }

    @Test
    void genericFinancialDataStatisticsIsNotClassifiedAsATechnicalRole() {
        JobContext finance = context("财务管理", JobFamily.DATA,
            EmploymentType.UNKNOWN, EducationLevel.BACHELOR, "负责财务数据统计和报表");
        MemoryAdmissions admissions = new MemoryAdmissions();

        service(contexts(finance), admissions, finance)
            .classify(List.of(finance.job().id()), NOW);

        assertThat(admissions.values.get(finance.job().id()).reasonCodes())
            .doesNotContain(JobAdmissionReason.TARGET_TECHNICAL_ROLE);
    }

    private static JobContexts contexts(JobContext... values) {
        Map<UUID, JobContext> byId = new LinkedHashMap<>();
        Arrays.stream(values).forEach(value -> byId.put(value.job().id(), value));
        return new JobContexts() {
            public Optional<JobContext> findByJobId(UUID id) { return Optional.ofNullable(byId.get(id)); }
            public List<JobContext> findActive() { return List.copyOf(byId.values()); }
        };
    }

    private static OfficialJobAdmissionService service(
        JobContexts contexts, MemoryAdmissions admissions, JobContext... values
    ) {
        Map<UUID, JobContext> byId = new HashMap<>();
        Arrays.stream(values).forEach(value -> byId.put(value.job().id(), value));
        return new OfficialJobAdmissionService(contexts, admissions, jobId -> {
            JobContext context = byId.get(jobId);
            if (context == null || context.job().evidenceIds().isEmpty()) {
                return new JobAdmissionPorts.FieldEvidenceCoverage(Set.of(), Set.of(), false, false, false);
            }
            return new JobAdmissionPorts.FieldEvidenceCoverage(Set.of(
                "title", "organizationName", "headcount", "educationRequirementText",
                "majorRequirementText", "ageRequirementText"), Set.of(), true, true, true);
        });
    }

    private static JobContext context(
        String title, JobFamily family, EmploymentType employment, EducationLevel education, String duties
    ) {
        return context(title, family, employment, education, duties, List.of());
    }

    private static JobContext context(
        String title, JobFamily family, EmploymentType employment, EducationLevel education,
        String duties, List<UUID> evidenceIds
    ) {
        return context(title, family, employment, education, duties,
            "https://hrss.hangzhou.gov.cn/art/2026/notice.html", evidenceIds);
    }

    private static JobContext context(
        String title, JobFamily family, EmploymentType employment, EducationLevel education,
        String duties, String sourceUrl
    ) {
        return context(title, family, employment, education, duties, sourceUrl, List.of());
    }

    private static JobContext context(
        String title, JobFamily family, EmploymentType employment, EducationLevel education,
        String duties, String sourceUrl, List<UUID> evidenceIds
    ) {
        UUID jobId = UUID.randomUUID(), eventId = UUID.randomUUID(), organizationId = UUID.randomUUID();
        var job = new JobPosting(jobId, eventId, organizationId, "A-1", title, family, employment,
            "杭州", 1, education, Set.of("计算机科学与技术"), Set.of(), 38,
            LocalDate.of(2026, 8, 1), null, Set.of(), duties,
            sourceUrl, evidenceIds);
        var organization = new Organization(organizationId, "杭州市西溪医院", OrganizationType.HOSPITAL,
            null, "浙江", "杭州", null, null, null);
        var event = new RecruitmentEvent(eventId, "公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            LocalDate.of(2026, 7, 1), null, null, job.sourceUrl(), EmploymentType.UNKNOWN, List.of());
        return new JobContext(job, organization, event, "a".repeat(64), true);
    }

    private static final class MemoryAdmissions implements JobAdmissions {
        final Map<UUID, JobAdmission> values = new LinkedHashMap<>();
        public Optional<JobAdmission> findByJobId(UUID jobId) { return Optional.ofNullable(values.get(jobId)); }
        public JobAdmission save(JobAdmission value) { values.put(value.jobPostingId(), value); return value; }
        public JobAdmissionPorts.AdmissionSummary summarize() { throw new UnsupportedOperationException(); }
    }
}
