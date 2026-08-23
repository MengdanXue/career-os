package com.careeros.application.personal;

import com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength;
import java.time.LocalDate;
import java.util.Objects;

public record PersonalAction(
    String id,
    ActionKind kind,
    int priority,
    String title,
    String reason,
    int affectedObjectCount,
    LocalDate dueOn,
    EvidenceStrength evidenceStrength,
    String deepLink
) {
    public enum ActionKind {
        CURRENT_JOB_DEADLINE,
        CANDIDATE_EVIDENCE,
        TARGET_JOB_CHANGE,
        APPLICATION_NEXT_STEP,
        PREPARATION_TIMELINE
    }

    public PersonalAction {
        require(id, "id");
        Objects.requireNonNull(kind, "kind");
        if (priority < 1) throw new IllegalArgumentException("priority must be positive");
        require(title, "title");
        require(reason, "reason");
        if (affectedObjectCount < 0) throw new IllegalArgumentException("affectedObjectCount cannot be negative");
        Objects.requireNonNull(evidenceStrength, "evidenceStrength");
        if (deepLink == null || !deepLink.startsWith("/")) {
            throw new IllegalArgumentException("deepLink must be an internal path");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
