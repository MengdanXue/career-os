package com.careeros.application.personal;

import static com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength.DOCUMENTED;
import static com.careeros.application.personal.PersonalAction.ActionKind.CANDIDATE_EVIDENCE;
import static com.careeros.application.personal.PersonalAction.ActionKind.CURRENT_JOB_DEADLINE;
import static com.careeros.application.personal.PersonalAction.ActionKind.TARGET_JOB_CHANGE;
import static com.careeros.domain.DomainEnums.EligibilityStatus.INELIGIBLE;
import static com.careeros.domain.DomainEnums.OpportunityTier.EXCLUDED;

import com.careeros.application.personal.PersonalActionPorts.CurrentJobs;
import com.careeros.application.personal.PersonalActionPorts.EvidenceTasks;
import com.careeros.application.personal.PersonalActionPorts.TargetJobChanges;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PersonalActionService {
    private final CurrentJobs currentJobs;
    private final EvidenceTasks evidenceTasks;
    private final TargetJobChanges targetJobChanges;

    public PersonalActionService(
        CurrentJobs currentJobs,
        EvidenceTasks evidenceTasks,
        TargetJobChanges targetJobChanges
    ) {
        this.currentJobs = Objects.requireNonNull(currentJobs);
        this.evidenceTasks = Objects.requireNonNull(evidenceTasks);
        this.targetJobChanges = Objects.requireNonNull(targetJobChanges);
    }

    public PersonalActions actions(UUID candidateId, LocalDate asOf) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(asOf, "asOf");
        var actions = new ArrayList<PersonalAction>();
        var messages = new ArrayList<String>();

        try {
            currentJobs.load(candidateId, asOf).stream()
                .filter(job -> job.deadline() != null)
                .filter(job -> !job.deadline().isBefore(asOf))
                .filter(job -> !job.deadline().isAfter(asOf.plusDays(14)))
                .filter(job -> job.eligibilityStatus() != INELIGIBLE)
                .filter(job -> job.tier() != EXCLUDED)
                .map(job -> new PersonalAction(
                    "deadline:" + job.jobId(), CURRENT_JOB_DEADLINE, 1,
                    job.jobTitle() + "即将截止",
                    job.organizationName() + "的官方报名截止日期已进入 14 天提醒窗口。",
                    1, job.deadline(), DOCUMENTED, "/opportunities/" + job.jobId()))
                .forEach(actions::add);
        } catch (RuntimeException exception) {
            messages.add("当前岗位截止暂时无法读取");
        }

        try {
            var snapshot = evidenceTasks.load(candidateId, asOf);
            if (!snapshot.available()) messages.add(snapshot.message());
            snapshot.items().stream()
                .filter(task -> task.affectedJobCount() > 0 || !snapshot.available())
                .map(task -> new PersonalAction(
                    "evidence:" + task.code(), CANDIDATE_EVIDENCE, 2,
                    task.title(), task.reason(), task.affectedJobCount(), null,
                    task.evidenceStrength(), task.deepLink()))
                .forEach(actions::add);
        } catch (RuntimeException exception) {
            messages.add("候选人证据任务暂时无法读取");
        }

        try {
            var snapshot = targetJobChanges.load(candidateId, asOf);
            if (!snapshot.available()) messages.add(snapshot.message());
            snapshot.items().stream()
                .map(change -> new PersonalAction(
                    "change:" + change.changeId(), TARGET_JOB_CHANGE, 3,
                    change.title(), "目标岗位发生" + change.changeType() + "变化，需要重新查看关键条件。",
                    change.affectedJobCount(), null, DOCUMENTED, change.deepLink()))
                .forEach(actions::add);
        } catch (RuntimeException exception) {
            messages.add("目标岗位变化暂时无法读取");
        }

        Comparator<PersonalAction> ordering = Comparator
            .comparingInt(PersonalAction::priority)
            .thenComparing(PersonalAction::dueOn, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Comparator.comparingInt(PersonalAction::affectedObjectCount).reversed())
            .thenComparing(action -> action.kind().name())
            .thenComparing(PersonalAction::id);
        var selected = actions.stream().sorted(ordering).limit(3).toList();
        String message = messages.isEmpty() ? null : String.join("；", messages.stream()
            .filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().toList());
        return new PersonalActions(candidateId, asOf, messages.isEmpty(), message, selected);
    }

    public record PersonalActions(
        UUID candidateId,
        LocalDate asOf,
        boolean available,
        String message,
        List<PersonalAction> items
    ) {
        public PersonalActions {
            Objects.requireNonNull(candidateId);
            Objects.requireNonNull(asOf);
            items = List.copyOf(items);
            if (items.size() > 3) throw new IllegalArgumentException("personal actions cannot exceed three");
        }
    }
}
