package com.careeros.application.personal;

import com.careeros.application.DecisionPorts.DecisionAssessor;
import com.careeros.application.ToolCallBudget;
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
     * 关注一个岗位，并把用户此刻看到的结论记为基准。
     *
     * <p><b>基准必须在关注时就建立。</b> 不建立的话"上次看到的结论"一直是空，
     * 而"变了"要求它非空——于是刚关注的岗位，第一次变化永远发现不了。那恰恰是最该发现的一次：
     * 用户就是因为在意它才关注的。界面又只在"变了"时才给"知道了"按钮，基准永远补不上，
     * 这个洞自己不会愈合。
     *
     * <p>基准取用户屏幕上的那个结论，不在这里重算。理由与 {@link #acknowledge} 相同：
     * 重算可能给出另一个答案，那就不是他看到的东西了。
     *
     * <p>重复关注不会重置基准——那会把尚未查看的变化抹掉。
     */
    public WatchedJob watch(UUID candidateId, UUID jobPostingId,
                            EligibilityStatus seenStatus, String seenEvaluatorVersion) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(jobPostingId, "jobPostingId");
        var existing = watchlist.find(candidateId, jobPostingId).orElse(null);
        if (existing != null) return existing;
        var now = clock.instant();
        return watchlist.save(new WatchedJob(candidateId, jobPostingId, seenStatus,
            seenStatus == null ? null : seenEvaluatorVersion, now, seenStatus == null ? null : now));
    }

    /**
     * 在看不到结论的位置关注（例如岗位详情还没算出结果）。
     *
     * <p>此时没有基准可记。清单会把这条标成"尚未建立基准"并给出显式入口，
     * 而不是默默显示"无变化"——那是在替一个从没比对过的岗位下结论。
     */
    public WatchedJob watch(UUID candidateId, UUID jobPostingId) {
        return watch(candidateId, jobPostingId, null, null);
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
        // 扇出由用户数据决定：关注 500 个岗位就是 500 次评估。上限之外的岗位照样列出来，
        // 但明确标成"未刷新"——静默省略会让人以为那些岗位没有变化。
        var budget = ToolCallBudget.standard();
        for (WatchedJob watched : watchlist.findByCandidate(candidateId)) {
            if (!budget.tryConsume()) {
                entries.add(WatchedJobView.notRefreshed(watched.jobPostingId(), watched.lastSeenStatus()));
                continue;
            }
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
                    // 有当前结论却没有基准，说明这条还没开始比对。要标出来，不能当成"无变化"。
                    watched.lastSeenStatus() == null,
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
     * @param baselineMissing      还没有比对基准。不是"无变化"——是从来没比过，
     *        下一次变化也发现不了，必须让用户能把基准补上。
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
        boolean baselineMissing,
        LocalDate applicationEndsOn,
        boolean applicationClosed,
        String evaluatorVersion,
        String deepLink
    ) {
        static WatchedJobView unavailable(UUID jobPostingId, EligibilityStatus lastSeenStatus) {
            return new WatchedJobView(jobPostingId, null, null, lastSeenStatus, null, false, false,
                null, false, null, "/opportunities/" + jobPostingId);
        }

        /** 本次超出调用预算，没有重新评估。与"算不出来"一样是读不到，不是"没变化"。 */
        static WatchedJobView notRefreshed(UUID jobPostingId, EligibilityStatus lastSeenStatus) {
            return unavailable(jobPostingId, lastSeenStatus);
        }

        /** 当前结论算不出来。不是"没变化"，是读不到。 */
        public boolean available() { return currentStatus != null; }
    }

    public record Watchlist(UUID candidateId, LocalDate asOf, List<WatchedJobView> items) {
        public Watchlist { items = List.copyOf(items == null ? List.of() : items); }
    }
}
