package com.careeros.application.agent;

import com.careeros.application.AgentSession;
import com.careeros.application.DecisionPorts.DecisionBundle;
import com.careeros.application.DecisionRankingService;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.agent.AgentTooling.ToolCall;
import com.careeros.application.personal.JobWatchlistService;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OpportunityTier;
import java.time.Clock;
import java.time.LocalDate;
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
 * <p>候选人身份由 {@link AgentExecutor} 绑定后传进来，工具不从参数里取。
 */
public final class ReadOnlyTools {
    private ReadOnlyTools() {}

    /** 一次最多取几个岗位。规划器报再大的数也按这个截断。 */
    static final int MAX_LIMIT = 10;

    /** 查岗位。规划器用它决定"有没有东西可谈"。 */
    public static ReadOnlyTool searchJobs(com.careeros.application.AgentQueryService.RankingPort rankings, Clock clock) {
        Objects.requireNonNull(rankings, "rankings");
        return new ReadOnlyTool() {
            public String name() { return "search_jobs"; }
            public String description() {
                return "按城市、职位类别、机会分层查岗位。参数：location、jobFamily、tier、limit。";
            }
            public Observation invoke(UUID candidateId, ToolCall call) {
                var query = new DecisionRankingService.RankingQuery(
                    tier(call.argument("tier")), blankToNull(call.argument("location")),
                    jobFamily(call.argument("jobFamily")), 0, limit(call.argument("limit")), false);
                var page = rankings.rank(candidateId, query, clock.instant());
                var jobs = page.items().stream().map(ReadOnlyTools::describe).toList();
                var data = new LinkedHashMap<String, Object>();
                data.put("count", jobs.size());
                data.put("totalAvailable", page.total());
                data.put("jobs", jobs);
                return Observation.ok(name(),
                    jobs.isEmpty() ? "这个范围内没有岗位。" : "找到 " + jobs.size() + " 个岗位。", data);
            }
        };
    }

    /** 看一个岗位的完整结论与限制条件。规划器用它回答"第 N 个怎么样"。 */
    public static ReadOnlyTool jobFacts(DecisionPortsAssessor assessor, Clock clock) {
        Objects.requireNonNull(assessor, "assessor");
        return new ReadOnlyTool() {
            public String name() { return "job_facts"; }
            public String description() { return "取一个岗位的硬资格、限制条件与截止日。参数：jobId。"; }
            public Observation invoke(UUID candidateId, ToolCall call) {
                String raw = call.argument("jobId");
                UUID jobId;
                try {
                    jobId = UUID.fromString(Objects.requireNonNull(raw, "jobId"));
                } catch (RuntimeException invalid) {
                    return Observation.failed(name(), "jobId 不是一个合法的岗位标识。");
                }
                var bundle = assessor.assess(candidateId, jobId, clock.instant());
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
     */
    public static ReadOnlyTool pendingConfirmations(PendingConfirmationsView pending) {
        Objects.requireNonNull(pending, "pending");
        return new ReadOnlyTool() {
            public String name() { return "pending_confirmations"; }
            public String description() { return "列出还需要用户确认的资料项，以及是哪个岗位提出的。"; }
            public Observation invoke(UUID candidateId, ToolCall call) {
                var items = pending.pendingFor(candidateId);
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
            public Observation invoke(UUID candidateId, ToolCall call) {
                var list = watchlist.list(candidateId, LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
                long changed = list.items().stream().filter(item -> item.changedSinceLastSeen()).count();
                long unreadable = list.items().stream().filter(item -> !item.available()).count();
                var data = new LinkedHashMap<String, Object>();
                data.put("count", list.items().size());
                data.put("changedCount", changed);
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
                    : "关注了 " + list.items().size() + " 个岗位，其中 " + changed + " 个有变化。", data);
            }
        };
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static int limit(String raw) {
        if (raw == null || raw.isBlank()) return 5;
        try {
            return Math.min(MAX_LIMIT, Math.max(1, Integer.parseInt(raw.strip())));
        } catch (NumberFormatException invalid) {
            return 5;
        }
    }

    /** 认不出来就当没筛选，不猜——猜错会静默换掉用户问的范围。 */
    private static OpportunityTier tier(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OpportunityTier.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static JobFamily jobFamily(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return JobFamily.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** 单岗位评估。与 {@code DecisionPorts.DecisionAssessor} 同形，这里另立一个名字避免把整包引进来。 */
    @FunctionalInterface
    public interface DecisionPortsAssessor {
        DecisionBundle assess(UUID candidateId, UUID jobId, java.time.Instant now);
    }

    /** 待确认事项的只读视图。由 Web 层用会话或即时排名去满足。 */
    @FunctionalInterface
    public interface PendingConfirmationsView {
        List<AgentSession.PendingConfirmation> pendingFor(UUID candidateId);
    }
}
