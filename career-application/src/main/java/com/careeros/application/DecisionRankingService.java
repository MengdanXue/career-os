package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class DecisionRankingService {
    private final JobContexts jobContexts;
    private final JobAdmissions admissions;
    private final DecisionAssessor assessor;

    public DecisionRankingService(JobContexts jobContexts, JobAdmissions admissions, DecisionAssessor assessor) {
        this.jobContexts = jobContexts;
        this.admissions = admissions;
        this.assessor = assessor;
    }

    public RankingPage rank(UUID candidateId, RankingQuery query, Instant now) {
        return rank(candidateId, query, now, null);
    }

    /**
     * 计入共享预算地排名。
     *
     * <p>排名的扇出是逐岗评估：符合条件的岗位有多少个，就评估多少次。Agent 工具面必须按这个
     * 真实次数扣预算，否则"一次工具调用扣一个单位"会让模型三次调用就触发上百次评估。
     *
     * <p>预算用完时<b>不静默截断</b>：没评估到的岗位数量原样报在 {@link RankingPage#notAssessed()} 里，
     * 由调用方决定是标注不完整还是拒绝给出结论。
     *
     * @param budget 共享预算；{@code null} 表示不计量（主干 HTTP 路径本来就被 {@code size<=100} 约束）
     */
    public RankingPage rank(UUID candidateId, RankingQuery query, Instant now, ToolCallBudget budget) {
        if (query.page() < 0) throw new IllegalArgumentException("page must not be negative");
        if (query.size() < 1 || query.size() > 100) throw new IllegalArgumentException("size must be between 1 and 100");
        Assessed assessed = ordered(candidateId, query, now, budget);
        List<DecisionBundle> ordered = assessed.items();
        int from = (int) Math.min((long) query.page() * query.size(), ordered.size());
        int to = Math.min(from + query.size(), ordered.size());
        return new RankingPage(ordered.subList(from, to), query.page(), query.size(), ordered.size(),
            assessed.notAssessed());
    }

    public List<DecisionBundle> rankAll(UUID candidateId, Instant now) {
        return ordered(candidateId, new RankingQuery(null, null, null, 0, 100, true), now, null).items();
    }

    /** 计入共享预算地全量排名。预算不足时 {@link RankingPage#notAssessed()} 大于零，结果不完整。 */
    public RankingPage rankAll(UUID candidateId, Instant now, ToolCallBudget budget) {
        Assessed assessed = ordered(candidateId, new RankingQuery(null, null, null, 0, 100, true), now, budget);
        return new RankingPage(assessed.items(), 0, 100, assessed.items().size(), assessed.notAssessed());
    }

    private Assessed ordered(UUID candidateId, RankingQuery query, Instant now, ToolCallBudget budget) {
        var candidateContexts = jobContexts.findActive().stream()
            .filter(context -> query.location() == null || contains(context.job().location(), query.location()))
            .filter(context -> query.jobFamily() == null || context.job().jobFamily() == query.jobFamily())
            .toList();
        var admissionsByJob = admissions.findByJobIds(candidateContexts.stream()
            .map(context -> context.job().id()).toList());
        var admitted = candidateContexts.stream()
            .filter(context -> {
                var admission = admissionsByJob.get(context.job().id());
                return admission != null && admission.admits(context.job());
            })
            .toList();
        var assessed = new java.util.ArrayList<DecisionBundle>();
        int notAssessed = 0;
        for (var context : admitted) {
            // 逐岗评估就是这里的扇出。有预算就一岗一扣；扣不动了就停下来并如实计数，
            // 不静默少评几个——少评的那几个读起来会像"没有符合条件的岗位"。
            if (budget != null && !budget.tryConsume()) { notAssessed++; continue; }
            assessed.add(assessor.assess(candidateId, context.job().id(), now));
        }
        List<DecisionBundle> ordered = assessed.stream()
            .filter(bundle -> query.includeExcluded() || bundle.decision().tier() != OpportunityTier.EXCLUDED)
            .filter(bundle -> query.tier() == null || bundle.decision().tier() == query.tier())
            .sorted(order()).toList();
        return new Assessed(ordered, notAssessed);
    }

    private record Assessed(List<DecisionBundle> items, int notAssessed) {}

    private static Comparator<DecisionBundle> order() {
        return Comparator.<DecisionBundle>comparingInt(value -> value.decision().tier().ordinal())
            .thenComparing(Comparator.comparingInt((DecisionBundle value) -> value.decision().fitScore()).reversed())
            .thenComparing(Comparator.comparingInt((DecisionBundle value) -> value.decision().stabilityScore()).reversed())
            .thenComparing(Comparator.comparingInt((DecisionBundle value) -> value.decision().coveragePercent()).reversed())
            .thenComparing(value -> value.jobContext().event().applicationEndsOn(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(value -> value.decision().jobPostingId());
    }

    private static boolean contains(String value, String query) { return value != null && value.toLowerCase().contains(query.toLowerCase()); }

    public record RankingQuery(OpportunityTier tier, String location, JobFamily jobFamily, int page, int size, boolean includeExcluded) {}
    /**
     * @param notAssessed 因共享预算耗尽而没有评估的岗位数。大于零表示这一页不完整——
     *                    调用方必须报出来或拒绝给结论，不能当成"就这么多"
     */
    public record RankingPage(List<DecisionBundle> items, int page, int size, long total, int notAssessed) {
        public RankingPage { items = List.copyOf(items); }
        public RankingPage(List<DecisionBundle> items, int page, int size, long total) {
            this(items, page, size, total, 0);
        }
        public boolean complete() { return notAssessed == 0; }
    }
}
