package com.careeros.domain;

import java.util.List;
import java.util.Objects;

/** Separates a supported mandatory condition from absence and clauses needing review. */
public final class PoliticalRequirementClassifier {
    public boolean hasHardRequirement(JobPosting job) {
        return classify(job) == Classification.CPC_REQUIRED;
    }

    public Classification classify(JobPosting job) {
        Objects.requireNonNull(job);
        var clauses = List.of(nullable(job.candidateScope()), nullable(job.otherRequirements()),
                nullable(job.originalRequirementText())).stream()
            .flatMap(value -> java.util.Arrays.stream(value.split("[，。；;！？!?\\n\\r]+")))
            .map(PoliticalRequirementClassifier::compact)
            .map(PoliticalRequirementClassifier::classifyClause)
            .toList();
        if (clauses.contains(Classification.MANUAL_REVIEW)) return Classification.MANUAL_REVIEW;
        return clauses.contains(Classification.CPC_REQUIRED)
            ? Classification.CPC_REQUIRED : Classification.NO_HARD_REQUIREMENT;
    }

    private static Classification classifyClause(String clause) {
        if (clause.isBlank() || (!clause.contains("党员") && !clause.contains("民主党派")
            && !clause.contains("政治面貌"))) return Classification.NO_HARD_REQUIREMENT;
        if (clause.contains("不限") || clause.contains("优先")) return Classification.NO_HARD_REQUIREMENT;
        // Alternatives are not an unrestricted field, and NON_MEMBER does not identify
        // membership of every alternative group. Do not invent a legal interpretation.
        if (clause.contains("或") || clause.contains("民主党派") || clause.contains("非党员")
            || clause.contains("非中共党员") || clause.contains("不得") || clause.contains("不能")) {
            return Classification.MANUAL_REVIEW;
        }
        boolean hard = clause.equals("中共党员")
            || clause.equals("中共预备党员")
            || clause.contains("限中共党员")
            || clause.contains("须为中共党员")
            || clause.contains("必须为中共党员")
            || clause.contains("要求中共党员")
            || clause.contains("政治面貌中共党员")
            || clause.contains("政治面貌为中共党员")
            || (clause.contains("政治面貌要求") && clause.contains("中共党员"));
        return hard ? Classification.CPC_REQUIRED : Classification.MANUAL_REVIEW;
    }

    public enum Classification { NO_HARD_REQUIREMENT, CPC_REQUIRED, MANUAL_REVIEW }

    private static String compact(String value) {
        return value.replaceAll("[\\s、：:]", "");
    }

    private static String nullable(String value) { return value == null ? "" : value; }
}
