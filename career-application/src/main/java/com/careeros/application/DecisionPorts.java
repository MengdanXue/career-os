package com.careeros.application;

import com.careeros.domain.*;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
