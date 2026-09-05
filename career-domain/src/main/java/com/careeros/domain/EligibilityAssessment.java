package com.careeros.domain;

import com.careeros.domain.DomainEnums.CriterionStatus;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.RuleType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EligibilityAssessment(
    UUID id,
    UUID candidateProfileId,
    UUID jobPostingId,
    EligibilityStatus status,
    Map<RuleType, RuleResult> ruleResults,
    List<String> requiredConfirmations,
    List<UUID> evidenceIds,
    String evaluatorVersion,
    Instant assessedAt
) {
    public EligibilityAssessment {
        Objects.requireNonNull(id); Objects.requireNonNull(candidateProfileId); Objects.requireNonNull(jobPostingId); Objects.requireNonNull(status);
        ruleResults = ruleResults == null ? Map.of() : Map.copyOf(ruleResults);
        requiredConfirmations = requiredConfirmations == null ? List.of() : List.copyOf(requiredConfirmations);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (evaluatorVersion == null || evaluatorVersion.isBlank()) throw new IllegalArgumentException("evaluatorVersion is required");
        Objects.requireNonNull(assessedAt);
        if (status == EligibilityStatus.ELIGIBLE && !requiredConfirmations.isEmpty()) {
            throw new IllegalArgumentException("ELIGIBLE assessment must not carry required confirmations");
        }
    }

    /**
     * 单条硬条件的判定。{@code requirement} 保留公告侧的要求原文，{@code candidateFact} 保留候选人侧
     * 被拿来比对的事实，两者都可能为空（规则不适用或事实缺失）。{@code evidenceIds} 指向支撑这条
     * 结论的证据片段——抽取阶段记下的片段级 ID，没有片段级证据时回落到公告级。
     */
    public record RuleResult(
        CriterionStatus status,
        String requirement,
        String candidateFact,
        String reason,
        List<UUID> evidenceIds
    ) {
        public RuleResult {
            Objects.requireNonNull(status, "status");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }
}
