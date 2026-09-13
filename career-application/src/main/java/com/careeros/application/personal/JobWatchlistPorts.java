package com.careeros.application.personal;

import com.careeros.domain.DomainEnums.EligibilityStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JobWatchlistPorts {
    private JobWatchlistPorts() {}

    public interface Watchlist {
        List<WatchedJob> findByCandidate(UUID candidateId);
        Optional<WatchedJob> find(UUID candidateId, UUID jobPostingId);
        WatchedJob save(WatchedJob entry);
        void remove(UUID candidateId, UUID jobPostingId);
    }

    /**
     * 一个被关注的岗位。
     *
     * @param lastSeenStatus     用户最后一次看到的硬资格结论。它与当前结论的差别就是"有什么变了"，
     *        所以只有用户确实看过之后才推进——后台重算顺手推进它，等于把变化悄悄抹掉。
     * @param lastSeenEvaluatorVersion 用户最后看到的那次评估的版本，用来说明旧结论已经不适用。
     */
    public record WatchedJob(
        UUID candidateId,
        UUID jobPostingId,
        EligibilityStatus lastSeenStatus,
        String lastSeenEvaluatorVersion,
        Instant watchedAt,
        Instant lastSeenAt
    ) {
        public WatchedJob {
            Objects.requireNonNull(candidateId, "candidateId");
            Objects.requireNonNull(jobPostingId, "jobPostingId");
            Objects.requireNonNull(watchedAt, "watchedAt");
        }

        public WatchedJob seen(EligibilityStatus status, String evaluatorVersion, Instant at) {
            return new WatchedJob(candidateId, jobPostingId, status, evaluatorVersion, watchedAt, at);
        }
    }
}
