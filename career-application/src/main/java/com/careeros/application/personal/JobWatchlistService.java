package com.careeros.application.personal;

import com.careeros.application.DecisionPorts.DecisionAssessor;
import com.careeros.application.personal.JobWatchlistPorts.WatchedJob;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 关注清单：用户自己盯着的那几个岗位，以及它们自上次查看以来变了什么。
 *
 * <p><b>关注不是报名。</b> 这里只记录"我在盯着它"，没有任何动作会提交报名——系统不代替用户报名，
 * 这条边界不因为清单的存在而松动。
 *
 * <p><b>"上次看到的结论"只在用户确实看过之后才推进。</b> 这是本类最容易被写错的地方：
 * 让重算顺手把 lastSeenStatus 刷成当前值，代码会更短，清单也永远"是最新的"——
 * 代价是用户永远不知道岗位从待确认变成了不可报。变化信号只有一次，抹掉就没了。
 *
 * <p><b>报名已截止的岗位单独标出。</b> 结论仍是"可报"但窗口已经关闭时，只显示"可报"
 * 会让人以为还来得及。
 */
public final class JobWatchlistService {
    private final JobWatchlistPorts.Watchlist watchlist;
    private final DecisionAssessor assessor;
    private final Clock clock;

    public JobWatchlistService(
        JobWatchlistPorts.Watchlist watchlist,
        DecisionAssessor assessor,
        Clock clock
    ) {
        this.watchlist = Objects.requireNonNull(watchlist, "watchlist");
        this.assessor = Objects.requireNonNull(assessor, "assessor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 关注一个岗位。重复关注不会重置"上次看到的结论"——那会把尚未查看的变化抹掉。
     */
    public WatchedJob watch(UUID candidateId, UUID jobPostingId) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(jobPostingId, "jobPostingId");
        var existing = watchlist.find(candidateId, jobPostingId).orElse(null);
        if (existing != null) return existing;
        return watchlist.save(new WatchedJob(candidateId, jobPostingId, null, null, clock.instant(), null));
    }

    public void unwatch(UUID candidateId, UUID jobPostingId) {
        watchlist.remove(Objects.requireNonNull(candidateId), Objects.requireNonNull(jobPostingId));
    }

    /**
     * 列出关注清单及其变化。
     *
     * <p>只读：不推进"上次看到的结论"。要推进得由 {@link #acknowledge} 明确地做，
     * 否则光是刷新一下页面就会把变化标记清掉。
     */
    public Watchlist list(UUID candidateId, LocalDate asOf) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(asOf, "asOf");
        var entries = new ArrayList<WatchedJobView>();
        var now = clock.instant();
        for (WatchedJob watched : watchlist.findByCandidate(candidateId)) {
            try {
                var bundle = assessor.assess(candidateId, watched.jobPostingId(), now);
                var deadline = bundle.jobContext().event().applicationEndsOn();
                var current = bundle.decision().eligibilityStatus();
                entries.add(new WatchedJobView(
                    watched.jobPostingId(),
                    bundle.jobContext().job().title(),
                    bundle.jobContext().organization().name(),
                    watched.lastSeenStatus(),
                    current,
                    // 只有真的看过一个不同的结论，才算"变了"。从没看过不是变化，是第一次看到。
                    watched.lastSeenStatus() != null && watched.lastSeenStatus() != current,
                    deadline,
                    deadline != null && deadline.isBefore(asOf),
                    bundle.decision().evaluatorVersion(),
                    "/opportunities/" + watched.jobPostingId()));
            } catch (RuntimeException failure) {
                // 一个岗位算不出来不该让整张清单消失，但也不能假装它还是老样子。
                entries.add(WatchedJobView.unavailable(watched.jobPostingId(), watched.lastSeenStatus()));
            }
        }
        entries.sort(Comparator
            .comparing(WatchedJobView::changedSinceLastSeen).reversed()
            .thenComparing(WatchedJobView::applicationEndsOn, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(WatchedJobView::jobPostingId));
        return new Watchlist(candidateId, asOf, List.copyOf(entries));
    }

    /**
     * 把某个岗位标记为"已看过当前结论"。
     *
     * <p>要带上用户看到的那个结论，而不是此刻重新算一遍：中间若又变了，
     * 用无参推进会把那次变化一起吞掉。
     */
    public WatchedJob acknowledge(UUID candidateId, UUID jobPostingId, EligibilityStatus seenStatus,
                                  String seenEvaluatorVersion) {
        Objects.requireNonNull(seenStatus, "seenStatus");
        var watched = watchlist.find(candidateId, jobPostingId).orElseThrow(() ->
            new IllegalArgumentException("job is not on the watchlist: " + jobPostingId));
        return watchlist.save(watched.seen(seenStatus, seenEvaluatorVersion, clock.instant()));
    }

    /**
     * @param changedSinceLastSeen 自用户上次查看以来结论是否变了
     * @param applicationClosed    报名窗口是否已经关闭。结论仍是"可报"但窗口关了，
     *        只显示"可报"会让人以为还来得及。
     */
    public record WatchedJobView(
        UUID jobPostingId,
        String jobTitle,
        String organizationName,
        EligibilityStatus lastSeenStatus,
        EligibilityStatus currentStatus,
        boolean changedSinceLastSeen,
        LocalDate applicationEndsOn,
        boolean applicationClosed,
        String evaluatorVersion,
        String deepLink
    ) {
        static WatchedJobView unavailable(UUID jobPostingId, EligibilityStatus lastSeenStatus) {
            return new WatchedJobView(jobPostingId, null, null, lastSeenStatus, null, false,
                null, false, null, "/opportunities/" + jobPostingId);
        }

        /** 当前结论算不出来。不是"没变化"，是读不到。 */
        public boolean available() { return currentStatus != null; }
    }

    public record Watchlist(UUID candidateId, LocalDate asOf, List<WatchedJobView> items) {
        public Watchlist { items = List.copyOf(items == null ? List.of() : items); }
    }
}
