package com.careeros.application.agent;

import com.careeros.application.AgentSession;
import com.careeros.application.DecisionPorts.DecisionBundle;
import com.careeros.application.DecisionRankingService;
import com.careeros.application.ToolCallBudget;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.agent.AgentTooling.ToolContext;
import com.careeros.application.agent.AgentTooling.ToolParameter;
import com.careeros.application.personal.JobWatchlistService;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OpportunityTier;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 首版的四个只读工具，全部转调主干既有服务。
 *
 * <p>这里不重新实现任何判定。资格、分层、分数都由既有确定性服务给出，工具只是把它们
 * 变成规划器能分支的结构化观察——规划器要能据此选下一步，所以 {@code data} 里必须有
 * 可判断的东西（数量、状态），而不只是一段话。
 *
 * <p><b>参数认不出来就失败，不降级成"没筛选"。</b> 这是这一版改掉的关键行为。
 * 把 {@code tier=T9} 当成"不筛分层"，返回的是全量结果，而它读起来和"T9 没有岗位"
 * 或"T9 就是这些"完全一样——用户问的范围被静默换掉，回答却看不出任何异常。
 * 现在这类参数一律返回失败观察，并把允许的取值写进去，规划器能看见、能改、能重试。
 *
 * <p>候选人身份与共享预算都由 {@link AgentExecutor} 绑定后传进来，工具不从参数里取。
 * 内部的逐岗评估必须用那份预算去扣——一次 {@code search_jobs} 背后是几十次评估。
 */
public final class ReadOnlyTools {
    private ReadOnlyTools() {}

    /** 一次最多取几个岗位。超过这个数直接拒绝，不悄悄截断成别的范围。 */
    public static final int MAX_LIMIT = 10;

    /** 没给 limit 时取几个。 */
    static final int DEFAULT_LIMIT = 5;

    /** 查岗位。规划器用它决定"有没有东西可谈"。 */
    public static ReadOnlyTool searchJobs(MeteredRankingPort rankings, Clock clock) {
        Objects.requireNonNull(rankings, "rankings");
        var parameters = List.of(
            ToolParameter.optional("location", "城市或地区名，按包含匹配，例如 杭州"),
            ToolParameter.oneOf("jobFamily", "职位类别", names(JobFamily.values())),
            ToolParameter.oneOf("tier", "机会分层", names(OpportunityTier.values())),
            ToolParameter.optional("limit", "返回几个岗位，1 到 " + MAX_LIMIT + "，默认 " + DEFAULT_LIMIT));
        return new ReadOnlyTool() {
            public String name() { return "search_jobs"; }
            public String description() {
                return "按城市、职位类别、机会分层查岗位，返回排好序的一页结果。";
            }
            public List<ToolParameter> parameters() { return parameters; }
            public Observation invoke(ToolContext context) {
                String rejection = reject(parameters, context);
                if (rejection != null) return Observation.failed(name(), rejection);
                int limit;
                try {
                    limit = limit(context.argument("limit"));
                } catch (IllegalArgumentException invalid) {
                    return Observation.failed(name(), invalid.getMessage());
                }
                var query = new DecisionRankingService.RankingQuery(
                    enumValue(OpportunityTier.class, context.argument("tier")),
                    blankToNull(context.argument("location")),
                    enumValue(JobFamily.class, context.argument("jobFamily")), 0, limit, false);
                var page = rankings.rank(context.candidateId(), query, clock.instant(), context.budget());
                var jobs = page.items().stream().map(ReadOnlyTools::describe).toList();
                var data = new LinkedHashMap<String, Object>();
                data.put("count", jobs.size());
                data.put("totalAvailable", page.total());
                data.put("notAssessed", page.notAssessed());
                data.put("complete", page.complete());
                data.put("jobs", jobs);
                // 预算不够时不能把"只评估了一部分"读成"就这么多"。
                String incomplete = page.complete() ? ""
                    : "（共享预算已用完，还有 " + page.notAssessed() + " 个岗位没有评估，这批结果不完整）";
                return Observation.ok(name(),
                    (jobs.isEmpty() ? "这个范围内没有岗位。" : "找到 " + jobs.size() + " 个岗位。") + incomplete, data);
            }
        };
    }

    /** 看一个岗位的完整结论与限制条件。规划器用它回答"第 N 个怎么样"。 */
    public static ReadOnlyTool jobFacts(DecisionPortsAssessor assessor, Clock clock) {
        Objects.requireNonNull(assessor, "assessor");
        var parameters = List.of(ToolParameter.required("jobId", "岗位的 UUID，取自 search_jobs 或关注清单的结果"));
        return new ReadOnlyTool() {
            public String name() { return "job_facts"; }
            public String description() { return "取一个岗位的硬资格、限制条件与截止日。"; }
            public List<ToolParameter> parameters() { return parameters; }
            public Observation invoke(ToolContext context) {
                String rejection = reject(parameters, context);
                if (rejection != null) return Observation.failed(name(), rejection);
                UUID jobId;
                try {
                    jobId = UUID.fromString(context.argument("jobId").strip());
                } catch (RuntimeException invalid) {
                    return Observation.failed(name(), "jobId 不是一个合法的岗位标识。");
                }
                // 单岗位也是一次逐岗评估，照扣。
                if (!context.budget().tryConsume()) {
                    return Observation.failed(name(), "共享预算已用完，这次没有评估这个岗位。");
                }
                var bundle = assessor.assess(context.candidateId(), jobId, clock.instant());
                var data = new LinkedHashMap<>(describe(bundle));
                var restrictions = bundle.eligibility() == null ? List.<String>of()
                    : bundle.eligibility().ruleResults().values().stream()
                        .filter(result -> result.status() != EligibilityStatus.ELIGIBLE)
                        .map(result -> result.explanation()).toList();
                data.put("restrictions", restrictions);
                data.put("restrictionCount", restrictions.size());
                return Observation.ok(name(), bundle.jobContext().job().title() + " 的结论已取到。", data);
            }
        };
    }

    /**
     * 还差哪些确认。
     *
     * <p>只列对话里能直接回答的标量字段——要材料的条件不该出现在"回一句就能解决"的清单里。
     * 规划器据此决定是继续查，还是转去追问用户。
     *
     * <p>预算不足以扫完全部岗位时<b>返回失败</b>，不返回一份短了的清单：
     * "还差哪些确认"是个结论，少列几项读起来就是"这些都齐了"。
     */
    public static ReadOnlyTool pendingConfirmations(PendingConfirmationsView pending) {
        Objects.requireNonNull(pending, "pending");
        return new ReadOnlyTool() {
            public String name() { return "pending_confirmations"; }
            public String description() { return "列出还需要用户确认的资料项，以及是哪个岗位提出的。"; }
            public List<ToolParameter> parameters() { return List.of(); }
            public Observation invoke(ToolContext context) {
                String rejection = reject(List.of(), context);
                if (rejection != null) return Observation.failed(name(), rejection);
                var result = pending.pendingFor(context.candidateId(), context.budget());
                if (!result.complete()) {
                    return Observation.failed(name(),
                        "共享预算不足以扫完全部岗位，这份待确认清单会是不完整的，不给出。");
                }
                var items = result.items();
                var data = new LinkedHashMap<String, Object>();
                data.put("count", items.size());
                data.put("items", items.stream().map(item -> Map.of(
                    "factKey", item.factKey().name(),
                    "question", item.question(),
                    "jobPostingId", item.jobPostingId().toString())).toList());
                return Observation.ok(name(),
                    items.isEmpty() ? "没有待确认的资料项。" : "还有 " + items.size() + " 项要用户确认。", data);
            }
        };
    }

    /** 关注清单及其变化。规划器用它回答"我关注的那些有没有动静"。 */
    public static ReadOnlyTool watchlist(JobWatchlistService watchlist, Clock clock) {
        Objects.requireNonNull(watchlist, "watchlist");
        return new ReadOnlyTool() {
            public String name() { return "watchlist"; }
            public String description() { return "列出用户关注的岗位，以及自上次查看以来的变化。"; }
            public List<ToolParameter> parameters() { return List.of(); }
            public Observation invoke(ToolContext context) {
                String rejection = reject(List.of(), context);
                if (rejection != null) return Observation.failed(name(), rejection);
                var list = watchlist.list(context.candidateId(),
                    LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC), context.budget());
                long changed = list.items().stream().filter(item -> item.changedSinceLastSeen()).count();
                long unreadable = list.items().stream().filter(item -> !item.available()).count();
                var data = new LinkedHashMap<String, Object>();
                data.put("count", list.items().size());
                data.put("changedCount", changed);
                // 算不出来和预算不够都算"这次读不到当前结论"，都不等于"没变化"。
                data.put("unreadableCount", unreadable);
                data.put("items", list.items().stream().map(item -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("jobPostingId", item.jobPostingId().toString());
                    row.put("jobTitle", item.jobTitle());
                    row.put("currentStatus", item.currentStatus() == null ? null : item.currentStatus().name());
                    row.put("changed", item.changedSinceLastSeen());
                    row.put("applicationClosed", item.applicationClosed());
                    return row;
                }).toList());
                return Observation.ok(name(), list.items().isEmpty() ? "关注清单是空的。"
                    : "关注了 " + list.items().size() + " 个岗位，其中 " + changed + " 个有变化，"
                        + unreadable + " 个这次读不到当前结论。", data);
            }
        };
    }

    /**
     * 参数校验。不认识就拒绝，绝不降级。
     *
     * @return 拒绝的理由；{@code null} 表示参数没问题
     */
    static String reject(List<ToolParameter> parameters, ToolContext context) {
        for (String key : context.call().arguments().keySet()) {
            if (parameters.stream().noneMatch(parameter -> parameter.name().equals(key))) {
                // 打错的参数名如果被忽略，筛选条件就静默消失了——candidateId 这种更是想越权。
                return "不认识参数「" + key + "」。这个工具" + (parameters.isEmpty() ? "不接受任何参数。"
                    : "只接受：" + parameters.stream().map(ToolParameter::name).reduce((a, b) -> a + "、" + b).orElse("") + "。");
            }
        }
        for (ToolParameter parameter : parameters) {
            String raw = context.argument(parameter.name());
            if (raw == null || raw.isBlank()) {
                if (parameter.required()) return "缺少必填参数「" + parameter.name() + "」。";
                continue;
            }
            if (!parameter.allowedValues().isEmpty()
                && !parameter.allowedValues().contains(raw.strip().toUpperCase())) {
                return "参数「" + parameter.name() + "」的取值「" + raw.strip() + "」不在允许的范围里。可选："
                    + String.join("、", parameter.allowedValues()) + "。";
            }
        }
        return null;
    }

    private static Map<String, Object> describe(DecisionBundle bundle) {
        var decision = bundle.decision();
        var row = new LinkedHashMap<String, Object>();
        row.put("jobPostingId", decision.jobPostingId().toString());
        row.put("jobTitle", bundle.jobContext().job().title());
        row.put("organizationName", bundle.jobContext().organization().name());
        row.put("eligibilityStatus", decision.eligibilityStatus().name());
        row.put("tier", decision.tier().name());
        row.put("applicationEndsOn", String.valueOf(bundle.jobContext().event().applicationEndsOn()));
        return row;
    }

    private static <E extends Enum<E>> List<String> names(E[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 取值已由 {@link #reject} 校验过，这里只做转换。 */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw) {
        return raw == null || raw.isBlank() ? null : Enum.valueOf(type, raw.strip().toUpperCase());
    }

    /** 超范围和不是数字都直接拒绝。悄悄截断到上限，等于把用户问的范围换掉还不说。 */
    private static int limit(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_LIMIT;
        int parsed;
        try {
            parsed = Integer.parseInt(raw.strip());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("参数「limit」的取值「" + raw.strip() + "」不是一个整数。");
        }
        if (parsed < 1 || parsed > MAX_LIMIT) {
            throw new IllegalArgumentException(
                "参数「limit」的取值「" + parsed + "」超出范围，只能是 1 到 " + MAX_LIMIT + "。");
        }
        return parsed;
    }

    /** 单岗位评估。与 {@code DecisionPorts.DecisionAssessor} 同形，这里另立一个名字避免把整包引进来。 */
    @FunctionalInterface
    public interface DecisionPortsAssessor {
        DecisionBundle assess(UUID candidateId, UUID jobId, java.time.Instant now);
    }

    /** 计入共享预算的排名端口。预算随调用传进去，逐岗评估在服务内部一岗一扣。 */
    @FunctionalInterface
    public interface MeteredRankingPort {
        DecisionRankingService.RankingPage rank(UUID candidateId, DecisionRankingService.RankingQuery query,
                                                java.time.Instant now, ToolCallBudget budget);
    }

    /**
     * 待确认事项的只读视图。由 Web 层用会话或即时排名去满足。
     *
     * @param complete 是否扫完了全部岗位。false 表示这份清单会漏项，调用方必须拒绝给出结论
     */
    public record PendingList(List<AgentSession.PendingConfirmation> items, boolean complete) {
        public PendingList { items = List.copyOf(items == null ? List.of() : items); }
    }

    @FunctionalInterface
    public interface PendingConfirmationsView {
        PendingList pendingFor(UUID candidateId, ToolCallBudget budget);
    }
}
