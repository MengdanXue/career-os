package com.careeros.application;

import com.careeros.domain.*;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.careeros.domain.GraduateEligibilityRule;
import java.util.UUID;
import java.util.Set;
import java.util.function.Supplier;

public final class DecisionPorts {
    private DecisionPorts() {}

    public record JobContext(JobPosting job, Organization organization, RecruitmentEvent event, String contentFingerprint, boolean active) {
        public JobContext {
            Objects.requireNonNull(job); Objects.requireNonNull(organization); Objects.requireNonNull(event);
            if (contentFingerprint == null || contentFingerprint.isBlank()) throw new IllegalArgumentException("contentFingerprint is required");
        }

        /**
         * 适用于本岗位的应届身份条款，供资格评估使用。
         *
         * <p>三种情况必须分开，压成一件就会把"没读到"说成"没限制"：已解析出条款则直接用；
         * 原文在但没解析成条款是解析失败，返回 null 让评估器落到待确认；原文本身就没有，
         * 说明公告处理过且确实没提应届，合成一条 NOT_REQUIRED 表示这个可下结论的事实。
         *
         * <p>放在这里而不是某个服务里，是因为资格评估有两个入口（决策快照与匹配队列），
         * 两边必须用同一套推导，否则同一个岗位在两处会显示不同结论。
         */
        public GraduateEligibilityRule graduateClause() {
            var parsed = event.graduateEligibilityRule();
            if (parsed != null) return parsed;
            String rawClause = event.graduateRule();
            if (rawClause != null && !rawClause.isBlank()) return null;
            return new GraduateEligibilityRule(event.recruitmentYear(), Set.of(), Set.of(), false,
                GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null,
                GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null,
                false, false, "", GraduateEligibilityRule.EvidenceState.NOT_REQUIRED);
        }
    }

    public record DecisionInputKey(UUID candidateProfileId, UUID jobPostingId, String profileVersion, String jobContentFingerprint, String evaluatorVersion) {
        public DecisionInputKey {
            Objects.requireNonNull(candidateProfileId); Objects.requireNonNull(jobPostingId);
            require(profileVersion, "profileVersion"); require(jobContentFingerprint, "jobContentFingerprint"); require(evaluatorVersion, "evaluatorVersion");
        }
        private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); }
    }

    public record DecisionBundle(EligibilityAssessment eligibility, FitAssessment fit, StabilityAssessment stability, DecisionAssessment decision, JobContext jobContext) {
        public DecisionBundle { Objects.requireNonNull(eligibility); Objects.requireNonNull(fit); Objects.requireNonNull(stability); Objects.requireNonNull(decision); Objects.requireNonNull(jobContext); }
    }

    public interface JobContexts {
        Optional<JobContext> findByJobId(UUID id);
        default Optional<JobContext> findByJobIdForUpdate(UUID id) { return findByJobId(id); }
        List<JobContext> findActive();
        default List<JobContext> findActiveByJobIds(Set<UUID> ids) {
            return findActive().stream().filter(context -> ids.contains(context.job().id())).toList();
        }
    }

    @FunctionalInterface
    public interface OrganizationStabilityFacts {
        List<OrganizationStabilityFact> findByOrganizationId(UUID organizationId);
    }

    public interface DecisionSnapshots {
        Optional<DecisionBundle> findByInput(DecisionInputKey input);
        DecisionBundle save(DecisionInputKey input, DecisionBundle bundle);
        List<DecisionBundle> findCurrentByCandidate(UUID candidateId);
        default List<DecisionBundle> findByCandidateAndProfileVersion(UUID candidateId, String profileVersion) {
            return List.of();
        }
    }

    @FunctionalInterface
    public interface DecisionAssessor {
        DecisionBundle assess(UUID candidateId, UUID jobId, java.time.Instant now);
    }

    @FunctionalInterface
    public interface DecisionInputLock {
        <T> T execute(String inputFingerprint, Supplier<T> operation);
    }
}
