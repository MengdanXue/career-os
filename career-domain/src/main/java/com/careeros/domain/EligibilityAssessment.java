package com.careeros.domain;

import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.RuleType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EligibilityAssessment(UUID id, UUID candidateProfileId, UUID jobPostingId, EligibilityStatus status, Map<RuleType, RuleResult> ruleResults, List<UUID> evidenceIds, String evaluatorVersion, Instant assessedAt, String profileVersion, String jobContentFingerprint) {
    public EligibilityAssessment {
        Objects.requireNonNull(id); Objects.requireNonNull(candidateProfileId); Objects.requireNonNull(jobPostingId); Objects.requireNonNull(status);
        ruleResults = ruleResults == null ? Map.of() : Map.copyOf(ruleResults); evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (evaluatorVersion == null || evaluatorVersion.isBlank()) throw new IllegalArgumentException("evaluatorVersion is required"); Objects.requireNonNull(assessedAt);
        if (profileVersion == null || profileVersion.isBlank()) throw new IllegalArgumentException("profileVersion is required");
        if (jobContentFingerprint == null || jobContentFingerprint.isBlank()) throw new IllegalArgumentException("jobContentFingerprint is required");
    }
    public EligibilityAssessment(UUID id, UUID candidateProfileId, UUID jobPostingId, EligibilityStatus status, Map<RuleType, RuleResult> ruleResults, List<UUID> evidenceIds, String evaluatorVersion, Instant assessedAt) {
        this(id, candidateProfileId, jobPostingId, status, ruleResults, evidenceIds, evaluatorVersion, assessedAt, "legacy", "legacy");
    }
    /**
     * 一条硬规则的判定结果。
     *
     * <p>{@code evidenceIds} 是支撑这条结论的证据片段。产品需求 §10.6 要求"每个资格结论、
     * 用工身份和关键推荐都有可定位证据"——挂在整份评估上的公告级证据只能指到一份公告，
     * 指不到公告里具体是哪一句话让这条规则得出了这个结论。
     *
     * <p>为空表示这条规则没有片段级证据可挂（例如公告没提该字段、或走的是候选人侧事实），
     * 此时仍可回落到 {@link EligibilityAssessment#evidenceIds()} 的公告级证据。
     */
    public record RuleResult(EligibilityStatus status, String explanation, List<UUID> evidenceIds) {
        public RuleResult {
            Objects.requireNonNull(status);
            if (explanation == null || explanation.isBlank()) throw new IllegalArgumentException("explanation is required");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }

        public RuleResult(EligibilityStatus status, String explanation) { this(status, explanation, List.of()); }

        public RuleResult withEvidence(List<UUID> fragmentIds) {
            return new RuleResult(status, explanation, fragmentIds);
        }
    }
}
