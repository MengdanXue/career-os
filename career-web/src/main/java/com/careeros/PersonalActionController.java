package com.careeros;

import com.careeros.application.personal.CandidateEvidenceTaskService;
import com.careeros.application.personal.PersonalActionService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/candidates/{candidateId}")
final class PersonalActionController {
    private final PersonalActionService actions;
    private final CandidateEvidenceTaskService evidenceTasks;
    private final Clock clock;

    PersonalActionController(
        PersonalActionService actions,
        CandidateEvidenceTaskService evidenceTasks,
        Clock clock
    ) {
        this.actions = Objects.requireNonNull(actions);
        this.evidenceTasks = Objects.requireNonNull(evidenceTasks);
        this.clock = Objects.requireNonNull(clock);
    }

    @GetMapping("/personal-actions")
    PersonalActionService.PersonalActions actions(
        @PathVariable("candidateId") UUID candidateId,
        @RequestParam(value = "asOf", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        return actions.actions(candidateId, resolve(asOf));
    }

    @GetMapping("/evidence-tasks")
    CandidateEvidenceTaskService.CandidateEvidenceTasks evidenceTasks(
        @PathVariable("candidateId") UUID candidateId,
        @RequestParam(value = "asOf", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        return evidenceTasks.tasks(candidateId, resolve(asOf));
    }

    private LocalDate resolve(LocalDate asOf) {
        return asOf == null ? LocalDate.now(clock) : asOf;
    }
}
