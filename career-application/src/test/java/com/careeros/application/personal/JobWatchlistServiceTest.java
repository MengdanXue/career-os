package com.careeros.application.personal;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.DecisionPorts.DecisionAssessor;
import com.careeros.application.DecisionPorts.DecisionBundle;
import com.careeros.application.DecisionPorts.JobContext;
import com.careeros.application.personal.JobWatchlistPorts.WatchedJob;
import com.careeros.application.personal.JobWatchlistService.WatchedJobView;
import com.careeros.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobWatchlistServiceTest {
    private static final UUID CANDIDATE_ID = UUID.randomUUID();
    private static final UUID JOB_A = UUID.randomUUID();
    private static final UUID JOB_B = UUID.randomUUID();
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 24);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T15:00:00Z"), ZoneOffset.UTC);

    private InMemoryWatchlist watchlist;
    private final Map<UUID, EligibilityStatus> statuses = new HashMap<>();
    private final Map<UUID, LocalDate> deadlines = new HashMap<>();

    @BeforeEach void setUp() {
        watchlist = new InMemoryWatchlist();
        statuses.clear();
        deadlines.clear();
        statuses.put(JOB_A, EligibilityStatus.NEEDS_CONFIRMATION);
        deadlines.put(JOB_A, AS_OF.plusDays(7));
    }

    private JobWatchlistService service() {
        DecisionAssessor assessor = (candidateId, jobId, now) -> {
            var status = statuses.get(jobId);
            if (status == null) throw new IllegalStateException("no assessment for " + jobId);
            return bundle(jobId, status, deadlines.get(jobId));
        };
        return new JobWatchlistService(watchlist, assessor, CLOCK);
    }

    // --- 关注不是报名 ---

    /**
     * 关注只写一条记录。这条用例存在是因为边界容易被"顺手也帮他报了"侵蚀——
     * 系统不代替用户报名，关注清单的存在不改变这一点。
     */
    @Test void watchingRecordsInterestAndNothingElse() {
        var watched = service().watch(CANDIDATE_ID, JOB_A);

        assertThat(watched.jobPostingId()).isEqualTo(JOB_A);
        assertThat(watched.lastSeenStatus()).isNull();
        assertThat(watchlist.stored).hasSize(1);
    }

    @Test void watchingTheSameJobTwiceDoesNotDuplicateIt() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);
        service.watch(CANDIDATE_ID, JOB_A);

        assertThat(watchlist.stored).hasSize(1);
    }

    // --- 变化信号只有一次，不能被顺手抹掉 ---

    /**
     * 这是本类最关键的一条。让列表顺手把"上次看到的结论"刷成当前值，代码更短、
     * 清单永远最新——代价是用户永远不知道岗位从待确认变成了不可报。
     */
    @Test void listingDoesNotAdvanceWhatTheUserHasSeen() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);
        service.acknowledge(CANDIDATE_ID, JOB_A, EligibilityStatus.NEEDS_CONFIRMATION, "v7");

        statuses.put(JOB_A, EligibilityStatus.INELIGIBLE);
        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement()
            .satisfies(item -> assertThat(item.changedSinceLastSeen()).isTrue());

        // 再看一次，变化仍然在：只有 acknowledge 能把它清掉。
        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement()
            .satisfies(item -> assertThat(item.changedSinceLastSeen()).isTrue());
        assertThat(watchlist.stored.get(JOB_A).lastSeenStatus()).isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
    }

    @Test void acknowledgingClearsTheChangeSignal() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);
        service.acknowledge(CANDIDATE_ID, JOB_A, EligibilityStatus.NEEDS_CONFIRMATION, "v7");
        statuses.put(JOB_A, EligibilityStatus.ELIGIBLE);

        service.acknowledge(CANDIDATE_ID, JOB_A, EligibilityStatus.ELIGIBLE, "v7");

        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement()
            .satisfies(item -> assertThat(item.changedSinceLastSeen()).isFalse());
    }

    /**
     * 确认的是用户看到的那个结论，不是此刻重新算的。中间若又变了一次，
     * 用"当前值"推进会把那次变化一起吞掉。
     */
    @Test void acknowledgingRecordsTheStatusTheUserSawNotTheCurrentOne() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);
        statuses.put(JOB_A, EligibilityStatus.INELIGIBLE);

        service.acknowledge(CANDIDATE_ID, JOB_A, EligibilityStatus.ELIGIBLE, "v7");

        assertThat(watchlist.stored.get(JOB_A).lastSeenStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement()
            .satisfies(item -> assertThat(item.changedSinceLastSeen()).isTrue());
    }

    /** 重复关注不会重置"上次看到的结论"，否则再点一次关注就把变化抹掉了。 */
    @Test void rewatchingDoesNotResetWhatTheUserHasSeen() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);
        service.acknowledge(CANDIDATE_ID, JOB_A, EligibilityStatus.NEEDS_CONFIRMATION, "v7");
        statuses.put(JOB_A, EligibilityStatus.ELIGIBLE);

        service.watch(CANDIDATE_ID, JOB_A);

        assertThat(watchlist.stored.get(JOB_A).lastSeenStatus()).isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement()
            .satisfies(item -> assertThat(item.changedSinceLastSeen()).isTrue());
    }

    /** 刚关注那一刻不是"变了"，是第一次看到——基准就等于当前值。 */
    @Test void theMomentOfWatchingIsNotReportedAsAChange() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A, EligibilityStatus.NEEDS_CONFIRMATION, "v7");

        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement().satisfies(item -> {
            assertThat(item.changedSinceLastSeen()).isFalse();
            assertThat(item.baselineMissing()).isFalse();
            assertThat(item.currentStatus()).isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
        });
    }


    // --- 首次关注的基准 ---

    /**
     * 刚关注的岗位，第一次变化必须能被发现——那恰恰是最该发现的一次，用户就是因为在意它才关注的。
     *
     * <p>此前这里是断的：基准一直为空，而"变了"要求它非空；界面又只在"变了"时才给"知道了"，
     * 基准永远补不上，于是第一次变化静默丢失，而且这个洞自己不会愈合。
     */
    @Test void theFirstChangeAfterWatchingIsDetected() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A, EligibilityStatus.NEEDS_CONFIRMATION, "v7");
        service.list(CANDIDATE_ID, AS_OF);

        statuses.put(JOB_A, EligibilityStatus.INELIGIBLE);

        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement().satisfies(item -> {
            assertThat(item.changedSinceLastSeen()).isTrue();
            assertThat(item.lastSeenStatus()).isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
            assertThat(item.baselineMissing()).isFalse();
        });
    }

    /** 基准取用户屏幕上那个结论，不在关注时重算——重算可能给出另一个答案。 */
    @Test void theBaselineIsTheStatusTheUserSawNotARecomputedOne() {
        var service = service();
        statuses.put(JOB_A, EligibilityStatus.INELIGIBLE);

        service.watch(CANDIDATE_ID, JOB_A, EligibilityStatus.ELIGIBLE, "v7");

        assertThat(watchlist.stored.get(JOB_A).lastSeenStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    /**
     * 在看不到结论的位置关注时没有基准可记。这种情况要标出来，
     * 不能默默显示"无变化"——那是在替一个从没比对过的岗位下结论。
     */
    @Test void aWatchWithoutABaselineIsFlaggedRatherThanReportedAsUnchanged() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);

        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement().satisfies(item -> {
            assertThat(item.baselineMissing()).isTrue();
            assertThat(item.changedSinceLastSeen()).isFalse();
        });
    }

    /** 补上基准之后就不再是"缺基准"，并且后续变化能被发现。 */
    @Test void acknowledgingSuppliesTheMissingBaseline() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);
        service.acknowledge(CANDIDATE_ID, JOB_A, EligibilityStatus.NEEDS_CONFIRMATION, "v7");

        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement()
            .satisfies(item -> assertThat(item.baselineMissing()).isFalse());

        statuses.put(JOB_A, EligibilityStatus.ELIGIBLE);
        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement()
            .satisfies(item -> assertThat(item.changedSinceLastSeen()).isTrue());
    }

    /** 有基准的重复关注仍然不重置它。 */
    @Test void rewatchingWithADifferentStatusDoesNotResetTheBaseline() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A, EligibilityStatus.NEEDS_CONFIRMATION, "v7");

        service.watch(CANDIDATE_ID, JOB_A, EligibilityStatus.ELIGIBLE, "v7");

        assertThat(watchlist.stored.get(JOB_A).lastSeenStatus()).isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
    }

    // --- 截止与不可用 ---

    /** 结论仍是"可报"但报名窗口已经关了，只显示"可报"会让人以为还来得及。 */
    @Test void aClosedApplicationWindowIsMarkedEvenWhenStillEligible() {
        statuses.put(JOB_A, EligibilityStatus.ELIGIBLE);
        deadlines.put(JOB_A, AS_OF.minusDays(1));
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);

        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement().satisfies(item -> {
            assertThat(item.currentStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(item.applicationClosed()).isTrue();
        });
    }

    @Test void anOpenWindowIsNotMarkedClosedOnItsLastDay() {
        deadlines.put(JOB_A, AS_OF);
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);

        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).singleElement()
            .satisfies(item -> assertThat(item.applicationClosed()).isFalse());
    }

    /** 一个岗位算不出来不该让整张清单消失，但也不能假装它还是老样子。 */
    @Test void anUnreadableJobIsReportedAsUnavailableWithoutHidingTheRest() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);
        service.watch(CANDIDATE_ID, JOB_B);

        var items = service.list(CANDIDATE_ID, AS_OF).items();

        assertThat(items).hasSize(2);
        assertThat(items).filteredOn(item -> item.jobPostingId().equals(JOB_B)).singleElement()
            .satisfies(item -> {
                assertThat(item.available()).isFalse();
                assertThat(item.currentStatus()).isNull();
                assertThat(item.changedSinceLastSeen()).isFalse();
            });
        assertThat(items).filteredOn(item -> item.jobPostingId().equals(JOB_A)).singleElement()
            .satisfies(item -> assertThat(item.available()).isTrue());
    }

    // --- 调用预算 ---

    /**
     * 扇出由用户数据决定：关注 500 个岗位就是 500 次评估。超出上限的岗位仍然列出来，
     * 但标成读不到——静默省略会让人以为那些岗位没有变化。
     */
    @Test void jobsBeyondTheCallBudgetAreListedAsUnreadableRatherThanOmitted() {
        var service = service();
        var watched = new ArrayList<UUID>();
        for (int index = 0; index < com.careeros.application.ToolCallBudget.DEFAULT_LIMIT + 5; index++) {
            UUID jobId = UUID.randomUUID();
            statuses.put(jobId, EligibilityStatus.NEEDS_CONFIRMATION);
            deadlines.put(jobId, AS_OF.plusDays(3));
            service.watch(CANDIDATE_ID, jobId);
            watched.add(jobId);
        }

        var items = service.list(CANDIDATE_ID, AS_OF).items();

        // 一个都没少列。
        assertThat(items).hasSize(watched.size());
        // 超出预算的那些标成读不到，而不是被悄悄丢掉。
        assertThat(items).filteredOn(item -> !item.available()).hasSize(5);
        assertThat(items).filteredOn(WatchedJobView::available)
            .hasSize(com.careeros.application.ToolCallBudget.DEFAULT_LIMIT);
    }

    // --- 排序与移除 ---

    /** 变了的排在前面：那正是用户回来要看的东西。 */
    @Test void changedJobsSortFirst() {
        statuses.put(JOB_B, EligibilityStatus.NEEDS_CONFIRMATION);
        deadlines.put(JOB_B, AS_OF.plusDays(1));
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);
        service.watch(CANDIDATE_ID, JOB_B);
        service.acknowledge(CANDIDATE_ID, JOB_A, EligibilityStatus.NEEDS_CONFIRMATION, "v7");
        statuses.put(JOB_A, EligibilityStatus.ELIGIBLE);

        var items = service.list(CANDIDATE_ID, AS_OF).items();

        assertThat(items.getFirst().jobPostingId()).isEqualTo(JOB_A);
        assertThat(items.getFirst().changedSinceLastSeen()).isTrue();
    }

    @Test void unwatchingRemovesTheJob() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);
        service.unwatch(CANDIDATE_ID, JOB_A);

        assertThat(service.list(CANDIDATE_ID, AS_OF).items()).isEmpty();
    }

    @Test void acknowledgingAJobThatIsNotWatchedIsRejected() {
        assertThatThrownBy(() -> service().acknowledge(CANDIDATE_ID, JOB_A, EligibilityStatus.ELIGIBLE, "v7"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not on the watchlist");
    }

    /** 只读列表不该写库——否则每刷新一次页面都在改数据。 */
    @Test void listingPerformsNoWrites() {
        var service = service();
        service.watch(CANDIDATE_ID, JOB_A);
        int writesAfterWatch = watchlist.writes.get();

        service.list(CANDIDATE_ID, AS_OF);

        assertThat(watchlist.writes.get()).isEqualTo(writesAfterWatch);
    }

    // --- 固定装置 ---

    private static DecisionBundle bundle(UUID jobId, EligibilityStatus status, LocalDate deadline) {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant assessedAt = Instant.parse("2026-08-24T00:00:00Z");
        String fingerprint = "a".repeat(64);
        var evidence = List.of(UUID.randomUUID());
        var job = new JobPosting(jobId, eventId, organizationId, "J-1", "信息技术岗位",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, "杭州", 1,
            EducationLevel.BACHELOR, Set.of(), Set.of(), null, null, null, Set.of(), "",
            "https://example.gov.cn/job", evidence);
        var organization = new Organization(organizationId, "杭州市信息中心", OrganizationType.PUBLIC_INSTITUTION,
            "市级", "浙江", "杭州", null, null, "https://example.gov.cn");
        var event = new RecruitmentEvent(eventId, "公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            AS_OF.minusDays(10), null, deadline, job.sourceUrl(), EmploymentType.ESTABLISHMENT, evidence);
        var eligibility = new EligibilityAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, status, Map.of(),
            evidence, "v7", assessedAt, "profile-1", fingerprint);
        var fit = new FitAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, List.of(), "v7",
            "profile-1", fingerprint, assessedAt);
        var stability = new StabilityAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, List.of(), "v7",
            "profile-1", fingerprint, assessedAt);
        var decision = new DecisionAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, eligibility.id(), fit.id(),
            stability.id(), status, OpportunityTier.T1, RecommendationStatus.REVIEW, fit.score(),
            stability.score(), 0, "v7", "profile-1", fingerprint, assessedAt);
        return new DecisionBundle(eligibility, fit, stability, decision,
            new JobContext(job, organization, event, fingerprint, true));
    }

    private static final class InMemoryWatchlist implements JobWatchlistPorts.Watchlist {
        private final Map<UUID, WatchedJob> stored = new LinkedHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();

        public List<WatchedJob> findByCandidate(UUID candidateId) {
            return stored.values().stream().filter(value -> value.candidateId().equals(candidateId)).toList();
        }
        public Optional<WatchedJob> find(UUID candidateId, UUID jobPostingId) {
            return Optional.ofNullable(stored.get(jobPostingId))
                .filter(value -> value.candidateId().equals(candidateId));
        }
        public WatchedJob save(WatchedJob entry) {
            writes.incrementAndGet();
            stored.put(entry.jobPostingId(), entry);
            return entry;
        }
        public void remove(UUID candidateId, UUID jobPostingId) {
            writes.incrementAndGet();
            stored.remove(jobPostingId);
        }
    }
}
