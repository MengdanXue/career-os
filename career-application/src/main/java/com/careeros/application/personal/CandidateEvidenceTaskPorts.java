package com.careeros.application.personal;

import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.CandidateFacts.CandidateFactStatus;
import com.careeros.domain.CandidateProfile;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CandidateEvidenceTaskPorts {
    private CandidateEvidenceTaskPorts() {}

    @FunctionalInterface
    public interface CandidateFactsSnapshot {
        CandidateSnapshot load(UUID candidateId);
    }

    @FunctionalInterface
    public interface CandidateQualificationImpact {
        QualificationImpact load(UUID candidateId, LocalDate asOf);
    }

    public record CandidateSnapshot(
        CandidateProfile profile,
        Map<CandidateFactKey, CandidateFactStatus> statuses
    ) {
        public CandidateSnapshot {
            Objects.requireNonNull(profile, "profile");
            statuses = Map.copyOf(statuses);
        }

        public CandidateFactStatus status(CandidateFactKey key) {
            return statuses.getOrDefault(key, CandidateFactStatus.UNKNOWN);
        }
    }

    public record QualificationImpact(Map<CandidateFactKey, Integer> affectedJobCounts) {
        public QualificationImpact {
            affectedJobCounts = Map.copyOf(affectedJobCounts);
            if (affectedJobCounts.values().stream().anyMatch(value -> value == null || value < 0)) {
                throw new IllegalArgumentException("affected job counts cannot be null or negative");
            }
        }

        public int count(CandidateFactKey key) {
            return affectedJobCounts.getOrDefault(key, 0);
        }
    }
}
