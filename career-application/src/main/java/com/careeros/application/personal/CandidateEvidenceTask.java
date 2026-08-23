package com.careeros.application.personal;

import com.careeros.domain.CandidateFacts.CandidateFactKey;
import java.util.Objects;

public record CandidateEvidenceTask(
    String code,
    EvidenceTaskKind kind,
    CandidateFactKey factKey,
    String title,
    String reason,
    int affectedJobCount,
    EvidenceStrength evidenceStrength,
    String deepLink
) {
    public enum EvidenceTaskKind {
        EMPLOYMENT,
        GRADUATION,
        CREDENTIAL,
        SKILL,
        RESEARCH,
        POLITICAL_AFFILIATION,
        PROFESSIONAL_TITLE
    }

    public enum EvidenceStrength { NONE, SELF_REPORTED, DOCUMENTED, VERIFIED }

    public CandidateEvidenceTask {
        require(code, "code");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(factKey, "factKey");
        require(title, "title");
        require(reason, "reason");
        if (affectedJobCount < 0) throw new IllegalArgumentException("affectedJobCount cannot be negative");
        Objects.requireNonNull(evidenceStrength, "evidenceStrength");
        if (deepLink == null || !deepLink.startsWith("/")) {
            throw new IllegalArgumentException("deepLink must be an internal path");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
