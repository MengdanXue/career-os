package com.careeros.application.personal;

import com.careeros.domain.JobPosting;
import java.util.List;
import java.util.Objects;

/** Detects only explicit, mandatory political-affiliation conditions. */
public final class PoliticalRequirementClassifier {
    public boolean hasHardRequirement(JobPosting job) {
        Objects.requireNonNull(job);
        return List.of(nullable(job.candidateScope()), nullable(job.otherRequirements()),
                nullable(job.originalRequirementText())).stream()
            .flatMap(value -> java.util.Arrays.stream(value.split("[，。；;！？!?\\n\\r]+")))
            .map(PoliticalRequirementClassifier::compact)
            .anyMatch(PoliticalRequirementClassifier::isHardClause);
    }

    private static boolean isHardClause(String clause) {
        if (clause.isBlank() || !clause.contains("党员")) return false;
        if (clause.contains("不限") || clause.contains("优先")
            || clause.contains("党员或") || clause.contains("或民主党派")) return false;
        return clause.equals("中共党员")
            || clause.equals("中共预备党员")
            || clause.contains("限中共党员")
            || clause.contains("须为中共党员")
            || clause.contains("必须为中共党员")
            || clause.contains("要求中共党员")
            || clause.contains("政治面貌中共党员")
            || clause.contains("政治面貌为中共党员")
            || (clause.contains("政治面貌要求") && clause.contains("中共党员"));
    }

    private static String compact(String value) {
        return value.replaceAll("[\\s、：:]", "");
    }

    private static String nullable(String value) { return value == null ? "" : value; }
}
