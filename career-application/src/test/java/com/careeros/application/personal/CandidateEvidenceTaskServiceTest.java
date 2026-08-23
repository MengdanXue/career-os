package com.careeros.application.personal;

import static com.careeros.domain.CandidateFacts.CandidateFactKey.*;
import static com.careeros.domain.CandidateFacts.CandidateFactStatus.CONFIRMED;
import static com.careeros.domain.CandidateFacts.CandidateFactStatus.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.CandidateFacts.CandidateFactStatus;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.Gender;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OrganizationType;
import com.careeros.domain.DomainEnums.PoliticalAffiliation;
import com.careeros.domain.EducationRecord;
import com.careeros.domain.EducationRecord.CompletionStatus;
import com.careeros.domain.EducationRecord.CredentialVerificationStatus;
import com.careeros.domain.PartialDate;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateEvidenceTaskServiceTest {
    private static final UUID CANDIDATE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000001");
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 23);

    @Test
    void derivesMissingPersonalEvidenceWithoutInventingCandidateValues() {
        var service = service(statuses(Map.of(
            EXPERIENCE_YEARS, UNKNOWN,
            EMPLOYMENT_HISTORY, UNKNOWN,
            POLITICAL_AFFILIATION, UNKNOWN,
            SKILLS, UNKNOWN,
            RESEARCH_KEYWORDS, UNKNOWN)), impacts(Map.of(
                EMPLOYMENT_HISTORY, 14,
                POLITICAL_AFFILIATION, 2,
                EDUCATION_RECORDS, 11,
                SKILLS, 7,
                RESEARCH_KEYWORDS, 3)));

        var result = service.tasks(CANDIDATE_ID, AS_OF);

        assertThat(result.available()).isTrue();
        assertThat(result.items()).extracting(CandidateEvidenceTask::code).containsExactly(
            "VERIFY_EMPLOYMENT_HISTORY",
            "CONFIRM_MASTER_GRADUATION_MONTH",
            "VERIFY_MASTER_CREDENTIAL",
            "ADD_SKILL_EVIDENCE",
            "ADD_RESEARCH_EVIDENCE",
            "CONFIRM_POLITICAL_AFFILIATION");
        assertThat(result.items()).allSatisfy(task -> {
            assertThat(task.deepLink()).startsWith("/profile#");
            assertThat(task.title()).isNotBlank();
            assertThat(task.reason()).isNotBlank();
        });
        assertThat(result.items().getFirst().affectedJobCount()).isEqualTo(14);
    }

    @Test
    void legacyExperienceTotalNeverCountsAsVerifiedEmploymentAndConfirmedEmptySkillsStayKnown() {
        var service = service(statuses(Map.of(
            EXPERIENCE_YEARS, CONFIRMED,
            EMPLOYMENT_HISTORY, UNKNOWN,
            SKILLS, CONFIRMED,
            RESEARCH_KEYWORDS, CONFIRMED)), impacts(Map.of(EMPLOYMENT_HISTORY, 5)));

        var result = service.tasks(CANDIDATE_ID, AS_OF);

        assertThat(result.items()).extracting(CandidateEvidenceTask::code)
            .contains("VERIFY_EMPLOYMENT_HISTORY")
            .doesNotContain("ADD_SKILL_EVIDENCE", "ADD_RESEARCH_EVIDENCE");
    }

    @Test
    void returnsStableCodeOrderingWhenImpactCountsTie() {
        var service = service(statuses(Map.of(
            POLITICAL_AFFILIATION, UNKNOWN,
            SKILLS, UNKNOWN,
            RESEARCH_KEYWORDS, UNKNOWN)), impacts(Map.of(
                POLITICAL_AFFILIATION, 4,
                SKILLS, 4,
                RESEARCH_KEYWORDS, 4)));

        var first = service.tasks(CANDIDATE_ID, AS_OF);
        var second = service.tasks(CANDIDATE_ID, AS_OF);

        assertThat(first).isEqualTo(second);
        assertThat(first.items()).extracting(CandidateEvidenceTask::code).containsSubsequence(
            "ADD_SKILL_EVIDENCE", "ADD_RESEARCH_EVIDENCE", "CONFIRM_POLITICAL_AFFILIATION");
    }

    @Test
    void exposesImpactFailureWithoutPretendingTheImpactIsZero() {
        CandidateEvidenceTaskPorts.CandidateQualificationImpact unavailable = (candidateId, asOf) -> {
            throw new IllegalStateException("decision snapshot unavailable");
        };
        var service = service(statuses(Map.of(EMPLOYMENT_HISTORY, UNKNOWN)), unavailable);

        var result = service.tasks(CANDIDATE_ID, AS_OF);

        assertThat(result.available()).isFalse();
        assertThat(result.message()).isEqualTo("目标岗位影响暂时无法计算");
        assertThat(result.items()).extracting(CandidateEvidenceTask::code).contains("VERIFY_EMPLOYMENT_HISTORY");
        assertThat(result.items()).allSatisfy(task -> assertThat(task.affectedJobCount()).isZero());
    }

    @Test
    void candidateNotFoundIsNeverReportedAsTemporaryUnavailability() {
        var missing = new com.careeros.application.CandidateProfileService.CandidateProfileNotFoundException("missing");
        var service = service(candidateId -> { throw missing; }, impacts(Map.of()));

        assertThatThrownBy(() -> service.tasks(CANDIDATE_ID, AS_OF)).isSameAs(missing);
    }

    @Test
    void confirmedEmptyEmploymentMeansKnownZeroAndDoesNotRequestInventedHistory() {
        var result = service(statuses(Map.of(EMPLOYMENT_HISTORY, CONFIRMED)), impacts(Map.of()))
            .tasks(CANDIDATE_ID, AS_OF);

        assertThat(result.items()).extracting(CandidateEvidenceTask::code)
            .doesNotContain("VERIFY_EMPLOYMENT_HISTORY");
    }

    private static CandidateEvidenceTaskService service(
        CandidateEvidenceTaskPorts.CandidateFactsSnapshot facts,
        CandidateEvidenceTaskPorts.CandidateQualificationImpact impact
    ) {
        return new CandidateEvidenceTaskService(facts, impact);
    }

    private static CandidateEvidenceTaskPorts.CandidateFactsSnapshot statuses(
        Map<CandidateFactKey, CandidateFactStatus> overrides
    ) {
        var values = new EnumMap<CandidateFactKey, CandidateFactStatus>(CandidateFactKey.class);
        for (var key : CandidateFactKey.values()) values.put(key, CONFIRMED);
        values.putAll(overrides);
        return candidateId -> new CandidateEvidenceTaskPorts.CandidateSnapshot(candidate(), values);
    }

    private static CandidateEvidenceTaskPorts.CandidateQualificationImpact impacts(
        Map<CandidateFactKey, Integer> counts
    ) {
        return (candidateId, asOf) -> new CandidateEvidenceTaskPorts.QualificationImpact(counts);
    }

    private static CandidateProfile candidate() {
        return new CandidateProfile(
            CANDIDATE_ID,
            "测试候选人",
            new PartialDate(1992, 12, 31),
            EducationLevel.BACHELOR,
            Set.of("计算机科学与技术", "计算机科学"),
            2014,
            7,
            Set.of("中级：计算机应用（评审）"),
            List.of("杭州", "浙江"),
            Set.of(EmploymentType.ESTABLISHMENT, EmploymentType.PUBLIC_INSTITUTION_FORMAL),
            "profile-test",
            Set.of(),
            Set.of(),
            Set.of(JobFamily.SOFTWARE, JobFamily.DATA, JobFamily.INFORMATION_SYSTEMS),
            Set.of(OrganizationType.PUBLIC_INSTITUTION, OrganizationType.UNIVERSITY),
            List.of(
                new EducationRecord(null, "中国", EducationLevel.BACHELOR, "计算机科学与技术", 2014,
                    null, CompletionStatus.COMPLETED, CredentialVerificationStatus.NOT_REQUIRED),
                new EducationRecord("示例海外大学", "示例国", EducationLevel.MASTER, "计算机科学", 2027,
                    null, CompletionStatus.EXPECTED, CredentialVerificationStatus.PLANNED)
            ),
            Gender.FEMALE,
            PoliticalAffiliation.NON_MEMBER,
            List.of()
        );
    }
}
