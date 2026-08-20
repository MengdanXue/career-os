package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.CandidateFacts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class CandidateMatchServiceTest {
    private static final UUID CANDIDATE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void listsProfileMatchesEvenWhenEmploymentIdentityStillNeedsConfirmation() {
        CandidateProfile candidate = candidate();
        JobContext context = context();
        JobAdmission admission = new JobAdmission(context.job().id(), DataQualityStatus.VERIFIED,
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
        assertThat(match.eligibilityStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(match.fitScore()).isPositive();
        assertThat(match.employmentIdentityConfirmed()).isFalse();
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
            Set.of("计算机科学与技术"), Set.of(), 38, LocalDate.of(2026, 8, 1), null,
            Set.of(), "医院信息系统建设和数据库管理",
            "https://hrss.hangzhou.gov.cn/art/2026/notice.html", List.of());
        var organization = new Organization(organizationId, "杭州市西溪医院", OrganizationType.HOSPITAL,
            null, "浙江", "杭州", null, null, null);
        var event = new RecruitmentEvent(eventId, "2026年公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            LocalDate.of(2026, 7, 1), null, null, job.sourceUrl(), EmploymentType.UNKNOWN, List.of());
        return new JobContext(job, organization, event, "b".repeat(64), true);
    }
}
