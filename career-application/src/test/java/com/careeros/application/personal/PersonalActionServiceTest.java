package com.careeros.application.personal;

import static com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength.NONE;
import static com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength.SELF_REPORTED;
import static com.careeros.application.personal.CandidateEvidenceTask.EvidenceTaskKind.CREDENTIAL;
import static com.careeros.application.personal.CandidateEvidenceTask.EvidenceTaskKind.EMPLOYMENT;
import static com.careeros.application.personal.CandidateEvidenceTask.EvidenceTaskKind.GRADUATION;
import static com.careeros.application.personal.PersonalAction.ActionKind.CANDIDATE_EVIDENCE;
import static com.careeros.application.personal.PersonalAction.ActionKind.CURRENT_JOB_DEADLINE;
import static com.careeros.application.personal.PersonalAction.ActionKind.TARGET_JOB_CHANGE;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.EMPLOYMENT_HISTORY;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.EDUCATION_RECORDS;
import static com.careeros.domain.DomainEnums.EligibilityStatus.ELIGIBLE;
import static com.careeros.domain.DomainEnums.EligibilityStatus.INELIGIBLE;
import static com.careeros.domain.DomainEnums.OpportunityTier.EXCLUDED;
import static com.careeros.domain.DomainEnums.OpportunityTier.T1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.personal.CandidateEvidenceTaskService.CandidateEvidenceTasks;
import com.careeros.application.personal.PersonalActionPorts.CurrentJobSignal;
import com.careeros.application.personal.PersonalActionPorts.TargetJobChange;
import com.careeros.application.personal.PersonalActionPorts.TargetJobChangeSnapshot;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PersonalActionServiceTest {
    private static final UUID CANDIDATE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000001");
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 23);

    @Test
    void capsActionsAtThreeInPersonalPriorityOrderRegardlessOfInputOrder() {
        var calls = new AtomicInteger();
        PersonalActionPorts.CurrentJobs jobs = (candidateId, asOf) -> shuffled(calls.incrementAndGet(), List.of(
            job("11111111-1111-1111-1111-111111111111", "八日后截止", AS_OF.plusDays(8), ELIGIBLE, T1),
            job("22222222-2222-2222-2222-222222222222", "十四日后截止", AS_OF.plusDays(14), ELIGIBLE, T1)));
        PersonalActionPorts.EvidenceTasks evidence = (candidateId, asOf) -> evidenceTasks(12);
        PersonalActionPorts.TargetJobChanges changes = (candidateId, asOf) -> new TargetJobChangeSnapshot(
            true, null, shuffled(calls.incrementAndGet(), List.of(
                new TargetJobChange(UUID.fromString("33333333-3333-3333-3333-333333333333"), "UPDATED", "目标岗位条件有变化", 2, "/jobs?changed=true"))));
        var service = new PersonalActionService(jobs, evidence, changes);

        var first = service.actions(CANDIDATE_ID, AS_OF);
        var second = service.actions(CANDIDATE_ID, AS_OF);

        assertThat(first).isEqualTo(second);
        assertThat(first.items()).hasSize(3);
        assertThat(first.items()).extracting(PersonalAction::kind).containsExactly(
            CURRENT_JOB_DEADLINE, CURRENT_JOB_DEADLINE, CANDIDATE_EVIDENCE);
        assertThat(first.items()).extracting(PersonalAction::title).containsExactly(
            "八日后截止即将截止", "十四日后截止即将截止", "补齐可核验工作经历");
    }

    @Test
    void includesTheFourteenDayBoundaryAndExcludesIneligibleExcludedAndFifteenDayJobs() {
        PersonalActionPorts.CurrentJobs jobs = (candidateId, asOf) -> List.of(
            job("11111111-1111-1111-1111-111111111111", "边界岗位", AS_OF.plusDays(14), ELIGIBLE, T1),
            job("22222222-2222-2222-2222-222222222222", "过远岗位", AS_OF.plusDays(15), ELIGIBLE, T1),
            job("33333333-3333-3333-3333-333333333333", "明确不符合", AS_OF.plusDays(2), INELIGIBLE, T1),
            job("44444444-4444-4444-4444-444444444444", "已排除", AS_OF.plusDays(1), ELIGIBLE, EXCLUDED));
        var service = new PersonalActionService(jobs, PersonalActionServiceTest::noEvidence, PersonalActionServiceTest::noChanges);

        var result = service.actions(CANDIDATE_ID, AS_OF);

        assertThat(result.items()).singleElement().satisfies(action -> {
            assertThat(action.title()).isEqualTo("边界岗位即将截止");
            assertThat(action.dueOn()).isEqualTo(AS_OF.plusDays(14));
            assertThat(action.deepLink()).isEqualTo("/opportunities/11111111-1111-1111-1111-111111111111");
        });
    }

    @Test
    void neverManufacturesDeadlinesForEvidenceOrChangeActions() {
        var service = new PersonalActionService(
            (candidateId, asOf) -> List.of(),
            (candidateId, asOf) -> evidenceTasks(5),
            (candidateId, asOf) -> new TargetJobChangeSnapshot(true, null, List.of(
                new TargetJobChange(UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    "UPDATED", "目标岗位条件有变化", 2, "/jobs?changed=true"))));

        var result = service.actions(CANDIDATE_ID, AS_OF);

        assertThat(result.items()).extracting(PersonalAction::kind)
            .containsExactly(PersonalAction.ActionKind.PREPARATION_TIMELINE, CANDIDATE_EVIDENCE, TARGET_JOB_CHANGE);
        assertThat(result.items()).allSatisfy(action -> assertThat(action.dueOn()).isNull());
    }

    @Test
    void emitsProfileAndExamPreparationActionsWhenTrustedPoolIsEmpty() {
        var service = new PersonalActionService(
            (candidateId, asOf) -> List.of(),
            (candidateId, asOf) -> new CandidateEvidenceTasks(candidateId, asOf, true, null, List.of(
                new CandidateEvidenceTask("CONFIRM_MASTER_GRADUATION_MONTH", GRADUATION, EDUCATION_RECORDS,
                    "确认硕士预计毕业月份", "应届资格需要明确学位取得月份。", 0,
                    SELF_REPORTED, "/profile#master-graduation"),
                new CandidateEvidenceTask("VERIFY_MASTER_CREDENTIAL", CREDENTIAL, EDUCATION_RECORDS,
                    "跟进海外学历认证证据", "需要记录留服认证状态和完成时间。", 0,
                    SELF_REPORTED, "/profile#credential-verification"))),
            PersonalActionServiceTest::noChanges);

        var result = service.actions(CANDIDATE_ID, LocalDate.of(2026, 8, 24));

        assertThat(result.items()).extracting(PersonalAction::id).containsExactly(
            "evidence:CONFIRM_MASTER_GRADUATION_MONTH",
            "evidence:VERIFY_MASTER_CREDENTIAL",
            "preparation:PREPARE_WRITTEN_EXAM_BASELINE");
        assertThat(result.items()).allSatisfy(action -> assertThat(action.dueOn()).isNull());
    }

    @Test
    void keepsAvailableActionsWhileMergingUnavailableSectionMessages() {
        var service = new PersonalActionService(
            (candidateId, asOf) -> { throw new IllegalStateException("decisions unavailable"); },
            (candidateId, asOf) -> new CandidateEvidenceTasks(candidateId, asOf, false,
                "目标岗位影响暂时无法计算", evidenceTasks(0).items()),
            (candidateId, asOf) -> new TargetJobChangeSnapshot(false, "目标岗位变化暂时无法读取", List.of()));

        var result = service.actions(CANDIDATE_ID, AS_OF);

        assertThat(result.available()).isFalse();
        assertThat(result.message()).isEqualTo("当前岗位截止暂时无法读取；目标岗位影响暂时无法计算；目标岗位变化暂时无法读取");
        assertThat(result.items()).extracting(PersonalAction::id)
            .containsExactly("evidence:VERIFY_EMPLOYMENT_HISTORY");
    }

    @Test
    void candidateNotFoundIsNeverDowngradedToPartialAvailability() {
        var missing = new com.careeros.application.CandidateProfileService.CandidateProfileNotFoundException("missing");
        var service = new PersonalActionService(
            (candidateId, asOf) -> { throw missing; },
            PersonalActionServiceTest::noEvidence,
            PersonalActionServiceTest::noChanges);

        assertThatThrownBy(() -> service.actions(CANDIDATE_ID, AS_OF)).isSameAs(missing);
    }

    private static CurrentJobSignal job(
        String id,
        String title,
        LocalDate deadline,
        com.careeros.domain.DomainEnums.EligibilityStatus eligibility,
        com.careeros.domain.DomainEnums.OpportunityTier tier
    ) {
        return new CurrentJobSignal(UUID.fromString(id), title, "杭州测试单位", eligibility, tier, deadline);
    }

    private static CandidateEvidenceTasks evidenceTasks(int affectedJobs) {
        return new CandidateEvidenceTasks(CANDIDATE_ID, AS_OF, true, null, List.of(
            new CandidateEvidenceTask("VERIFY_EMPLOYMENT_HISTORY", EMPLOYMENT, EMPLOYMENT_HISTORY,
                "补齐可核验工作经历", "旧资料总年数不能作为硬资格证据。", affectedJobs,
                NONE, "/profile#employment-history")));
    }

    private static CandidateEvidenceTasks noEvidence(UUID candidateId, LocalDate asOf) {
        return new CandidateEvidenceTasks(candidateId, asOf, true, null, List.of());
    }

    private static TargetJobChangeSnapshot noChanges(UUID candidateId, LocalDate asOf) {
        return new TargetJobChangeSnapshot(true, null, List.of());
    }

    private static <T> List<T> shuffled(int seed, List<T> input) {
        var result = new ArrayList<>(input);
        Collections.rotate(result, seed % result.size());
        return result;
    }
}
