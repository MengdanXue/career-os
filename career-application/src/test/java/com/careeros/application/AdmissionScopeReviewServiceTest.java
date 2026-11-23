package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.AdmissionScopeReviewService.Command;
import com.careeros.application.AdmissionScopeReviewService.Decision;
import com.careeros.application.AdmissionScopeReviewService.NotAdmissibleException;
import com.careeros.application.AdmissionScopeReviewService.RecordedDecision;
import com.careeros.application.AdmissionScopeReviewService.StaleContentException;
import com.careeros.application.JobAdmissionPorts.FieldEvidenceCoverage;
import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdmissionScopeReviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");
    private static final String FINGERPRINT = "f".repeat(64);
    private static final FieldEvidenceCoverage COMPLETE = new FieldEvidenceCoverage(Set.of(
        "title", "organizationName", "headcount", "educationRequirementText",
        "majorRequirementText", "ageRequirementText"), Set.of(), true, true, true);

    private final MemoryAdmissions admissions = new MemoryAdmissions();
    private final MemoryLedger ledger = new MemoryLedger();

    /** 这正是可信池一直为零的那一环：证据齐全的目标技术岗，经人确认后才真正进池。 */
    @Test void aReviewerCanAdmitAnEvidenceCompleteTechnicalJobIntoTheTrustedPool() {
        var job = context("信息中心工作人员", EmploymentType.PUBLIC_INSTITUTION_FORMAL, EducationLevel.MASTER);
        var automated = OfficialJobAdmissionService.automatedAssessment(job.job(), COMPLETE, NOW);
        assertThat(automated.dataQualityStatus()).isEqualTo(DataQualityStatus.VERIFIED);
        assertThat(automated.admitted()).as("自动分类单独做不到这一步").isFalse();

        var outcome = service(job, COMPLETE).decide(
            new Command(job.job().id(), Decision.INCLUDE, FINGERPRINT, "zhang", "信息中心岗，职责明确"));

        assertThat(outcome.admitted()).isTrue();
        assertThat(outcome.admission().admits(job.job())).isTrue();
        assertThat(outcome.admission().humanVerified()).isTrue();
        assertThat(outcome.admission().reasonCodes())
            .contains(JobAdmissionReason.TARGET_TECHNICAL_ROLE, JobAdmissionReason.HUMAN_SCOPE_INCLUDED);
        assertThat(admissions.values.get(job.job().id())).isEqualTo(outcome.admission());
        assertThat(ledger.rows).singleElement().satisfies(row -> {
            assertThat(row.reviewer()).isEqualTo("zhang");
            assertThat(row.contentFingerprint()).isEqualTo(FINGERPRINT);
            assertThat(row.resultingScope()).isEqualTo(TargetScopeStatus.INCLUDED);
            assertThat(row.decidedAt()).isEqualTo(NOW);
        });
    }

    /** 人不能替证据作保：字段证据不全时，纳入被拒，且不留下任何写入。 */
    @Test void incompleteEvidenceCannotBeIncludedByAReviewer() {
        var job = context("信息中心工作人员", EmploymentType.PUBLIC_INSTITUTION_FORMAL, EducationLevel.MASTER);
        var partial = new FieldEvidenceCoverage(Set.of("title", "organizationName"), Set.of(), true, false, true);

        assertThatThrownBy(() -> service(job, partial).decide(
                new Command(job.job().id(), Decision.INCLUDE, FINGERPRINT, "zhang", null)))
            .isInstanceOf(NotAdmissibleException.class)
            .satisfies(failure -> assertThat(((NotAdmissibleException) failure).blockers())
                .anyMatch(blocker -> blocker.contains("关键字段缺少官方证据")));
        assertThat(admissions.values).isEmpty();
        assertThat(ledger.rows).isEmpty();
    }

    @Test void conflictingOfficialFieldsCannotBeIncluded() {
        var job = context("信息中心工作人员", EmploymentType.PUBLIC_INSTITUTION_FORMAL, EducationLevel.MASTER);
        var conflicting = new FieldEvidenceCoverage(COMPLETE.explicitFields(), Set.of("ageRequirementText"),
            true, true, true);

        assertThatThrownBy(() -> service(job, conflicting).decide(
                new Command(job.job().id(), Decision.INCLUDE, FINGERPRINT, "zhang", null)))
            .isInstanceOf(NotAdmissibleException.class)
            .hasMessageContaining("官方字段互相矛盾");
    }

    /** 产品基线的排除项（博士限定、派遣、教师……）不是"自动分类拿不准"，人也不能把它纳入。 */
    @Test void ruleExcludedJobsCannotBeIncluded() {
        var doctorate = context("人工智能研究", EmploymentType.PUBLIC_INSTITUTION_FORMAL, EducationLevel.DOCTORATE);

        assertThatThrownBy(() -> service(doctorate, COMPLETE).decide(
                new Command(doctorate.job().id(), Decision.INCLUDE, FINGERPRINT, "zhang", null)))
            .isInstanceOf(NotAdmissibleException.class)
            .hasMessageContaining("DOCTOR_REQUIRED");
    }

    @Test void unknownEmploymentIdentityCannotBeIncluded() {
        var job = context("信息中心工作人员", EmploymentType.UNKNOWN, EducationLevel.MASTER);

        assertThatThrownBy(() -> service(job, COMPLETE).decide(
                new Command(job.job().id(), Decision.INCLUDE, FINGERPRINT, "zhang", null)))
            .isInstanceOf(NotAdmissibleException.class)
            .hasMessageContaining("用工身份未知");
    }

    /** 他确认的是他看到的那一版。内容在那之后变了，这个决定不算数。 */
    @Test void aDecisionOnStaleContentIsRejected() {
        var job = context("信息中心工作人员", EmploymentType.PUBLIC_INSTITUTION_FORMAL, EducationLevel.MASTER);

        assertThatThrownBy(() -> service(job, COMPLETE).decide(
                new Command(job.job().id(), Decision.INCLUDE, "0".repeat(64), "zhang", null)))
            .isInstanceOf(StaleContentException.class);
        assertThat(admissions.values).isEmpty();
        assertThat(ledger.rows).isEmpty();
    }

    /** 排除不需要证据齐全：它把岗位移出队列，不会让任何结论变得更肯定。 */
    @Test void excludingIsAllowedEvenWhenEvidenceIsIncomplete() {
        var job = context("信息中心工作人员", EmploymentType.UNKNOWN, EducationLevel.MASTER);
        var none = new FieldEvidenceCoverage(Set.of(), Set.of(), false, false, false);

        var outcome = service(job, none).decide(
            new Command(job.job().id(), Decision.EXCLUDE, FINGERPRINT, "zhang", "其实是综合岗"));

        assertThat(outcome.admitted()).isFalse();
        assertThat(outcome.admission().targetScopeStatus()).isEqualTo(TargetScopeStatus.EXCLUDED);
        assertThat(outcome.admission().reasonCodes()).contains(JobAdmissionReason.HUMAN_SCOPE_EXCLUDED);
        assertThat(outcome.admission().humanVerified()).isTrue();
        assertThat(ledger.rows).singleElement().extracting(RecordedDecision::note).isEqualTo("其实是综合岗");
    }

    @Test void anInactiveJobCannotBeDecided() {
        var job = context("信息中心工作人员", EmploymentType.PUBLIC_INSTITUTION_FORMAL, EducationLevel.MASTER);
        var inactive = new JobContext(job.job(), job.organization(), job.event(), FINGERPRINT, false);

        assertThatThrownBy(() -> service(inactive, COMPLETE).decide(
                new Command(job.job().id(), Decision.INCLUDE, FINGERPRINT, "zhang", null)))
            .isInstanceOf(DecisionExceptions.JobNotFoundException.class);
    }

    @Test void theCommandRequiresTheFingerprintTheReviewerSaw() {
        assertThatThrownBy(() -> new Command(UUID.randomUUID(), Decision.INCLUDE, " ", "zhang", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("contentFingerprint");
    }

    private AdmissionScopeReviewService service(JobContext context, FieldEvidenceCoverage coverage) {
        JobContexts contexts = new JobContexts() {
            public Optional<JobContext> findByJobId(UUID id) {
                return id.equals(context.job().id()) ? Optional.of(context) : Optional.empty();
            }
            public List<JobContext> findActive() { return List.of(context); }
        };
        return new AdmissionScopeReviewService(contexts, admissions, jobId -> coverage, ledger,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JobContext context(String title, EmploymentType employment, EducationLevel education) {
        UUID jobId = UUID.randomUUID(), eventId = UUID.randomUUID(), organizationId = UUID.randomUUID();
        var job = new JobPosting(jobId, eventId, organizationId, "A-1", title, JobFamily.INFORMATION_SYSTEMS,
            employment, "杭州", 1, education, Set.of("计算机科学与技术"), Set.of(), 35,
            LocalDate.of(2026, 8, 1), null, Set.of(), "负责信息系统建设与运维",
            "https://hrss.hangzhou.gov.cn/art/2026/notice.html", List.of(UUID.randomUUID()));
        var organization = new Organization(organizationId, "杭州市数据资源中心", OrganizationType.PUBLIC_INSTITUTION,
            null, "浙江", "杭州", null, null, null);
        var event = new RecruitmentEvent(eventId, "公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            LocalDate.of(2026, 7, 1), null, null, job.sourceUrl(), EmploymentType.UNKNOWN, List.of());
        return new JobContext(job, organization, event, FINGERPRINT, true);
    }

    private static final class MemoryAdmissions implements JobAdmissions {
        final Map<UUID, JobAdmission> values = new LinkedHashMap<>();
        public Optional<JobAdmission> findByJobId(UUID jobId) { return Optional.ofNullable(values.get(jobId)); }
        public JobAdmission save(JobAdmission value) { values.put(value.jobPostingId(), value); return value; }
        public JobAdmissionPorts.AdmissionSummary summarize() { throw new UnsupportedOperationException(); }
    }

    private static final class MemoryLedger implements AdmissionScopeReviewService.ScopeDecisionLedger {
        final List<RecordedDecision> rows = new ArrayList<>();
        public void append(RecordedDecision decision) { rows.add(decision); }
        public List<RecordedDecision> findByJobId(UUID jobId) {
            return rows.stream().filter(row -> row.jobPostingId().equals(jobId)).toList();
        }
    }
}
