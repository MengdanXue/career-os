package com.careeros;

import com.careeros.application.AgentSession;
import com.careeros.application.AgentSessionService;
import com.careeros.application.CandidateProfileService;
import com.careeros.application.agent.AgentExecutor;
import com.careeros.application.agent.AgentTooling;
import com.careeros.application.agent.AgentTooling.AgentPlanner;
import com.careeros.application.agent.AgentTooling.JobRef;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.PendingRef;
import com.careeros.application.agent.AgentTooling.SessionContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

/**
 * 只读的 Agent 运行接口。
 *
 * <p>这里不写入任何东西。首版允许的是查询、读取、解释和追问；资料修改仍然只走用户明确操作的
 * 确定性接口（{@code /profile-confirmations}、{@code /watched-jobs}），规划器够不着它们。
 *
 * <p><b>没有规划器就返回 503，不退化成写死的流程。</b> 一个按固定顺序调工具的实现，
 * 在这个接口后面会被当成"Agent 能自己选工具"，那是把硬编码流程叫作 Agent。
 * 宁可明说模型没启用。
 *
 * <p><b>带 sessionId 的请求要核对归属，并且真的把上一轮交给执行器。</b>
 * 只把 sessionId 原样回传不算续跑：用户在追问之后只答一句"余杭"，模型看不到上一轮限定的是杭州，
 * 就会把它当成一个孤立的新问题；用户说"第二个"更是无从指认。
 *
 * <p><b>岗位结果由程序渲染。</b> 响应里的 jobs 与 pendingConfirmations 取自工具返回的结构化数据，
 * 不是模型那段话——模型的文字只做连接，事实一律来自确定性服务。
 */
@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/agent-runs")
class AgentRunController {
    private final AgentExecutor executor;
    private final ObjectProvider<AgentPlanner> planner;
    private final AgentSessionService sessions;
    private final CandidateProfileService profiles;

    AgentRunController(AgentExecutor executor, ObjectProvider<AgentPlanner> planner,
                       AgentSessionService sessions, CandidateProfileService profiles) {
        this.executor = executor;
        this.planner = planner;
        this.sessions = sessions;
        this.profiles = profiles;
    }

    @PostMapping
    AgentRunResponse run(@PathVariable("candidateId") UUID candidateId, @RequestBody AgentRunRequest request) {
        var active = planner.getIfAvailable();
        if (active == null) {
            throw new PlannerUnavailableException("没有可用的规划器：模型未启用，且不提供写死的替代流程。");
        }
        // 续跑同一轮会话时先核对归属；不存在或不是本人的一律 404，不泄露"存在但不是你的"。
        var session = request.sessionId() == null ? null
            : sessions.find(candidateId, request.sessionId()).orElseThrow(() ->
                new AgentSessionService.SessionNotFoundException("没有这轮会话，或它不属于当前候选人。"));
        var context = session == null ? SessionContext.none() : contextOf(candidateId, session);

        var run = executor.run(candidateId, request.question(), active, context);

        var jobs = jobs(run.observations());
        var pending = pending(run.observations(), context);
        // 这一轮的范围、列表和还没答的问题要存回去，否则会话永远停在第一轮：
        // 用户看到的是新列表，下一句"第二个"却解析回上一轮那一份。
        var saved = save(candidateId, request.sessionId(), session, run, jobs, pending);

        return new AgentRunResponse(
            run.outcome().name(),
            run.narrative(),
            run.question(),
            run.violations(),
            run.groundedOn(),
            run.budgetSpent(),
            run.budgetLimit(),
            saved == null ? (session == null ? null : session.sessionId()) : saved.sessionId(),
            saved == null ? (session == null ? null : session.profileVersion()) : saved.profileVersion(),
            jobs,
            pending,
            executor.toolCatalogue().stream().map(tool -> new ToolView(tool.name(), tool.description(),
                tool.parameters().stream().map(parameter -> new ParameterView(parameter.name(),
                    parameter.required(), parameter.description(), parameter.allowedValues())).toList())).toList(),
            run.trace().stream().map(entry -> new TraceStep(
                entry.tool(), entry.arguments(), entry.why(), entry.accepted(), entry.reason(),
                entry.budgetUnits())).toList(),
            run.observations().stream().map(observation -> new ObservationView(
                observation.tool(), observation.ok(), observation.summary(), observation.data())).toList());
    }

    /**
     * 把这一轮存回会话。
     *
     * <p>只在这一轮真的查出了新列表时才写：用户问"第一个的截止日是哪天"，系统只调了 job_facts，
     * 这时候把顺序清空，下一句"第二个"就没有东西可指了——上一轮明明还在他屏幕上。
     *
     * <p>范围取这一轮<b>实际执行成功</b>的那次 search_jobs 的参数，不取模型说过的话：
     * 被拒的调用没有产生任何结果，把它的参数当成"本轮范围"，下一轮就会在一个从未生效的范围上接着走。
     *
     * @return 存下来的会话；这一轮没有新列表时为 {@code null}
     */
    private AgentSession save(UUID candidateId, UUID requestedSessionId, AgentSession existing,
                              AgentExecutor.AgentRun run, List<JobView> jobs, List<PendingView> pending) {
        var listing = searchArguments(run);
        if (listing == null) return null;
        UUID sessionId = existing != null ? existing.sessionId()
            : requestedSessionId != null ? requestedSessionId : UUID.randomUUID();
        var order = jobs.stream().map(job -> UUID.fromString(job.jobPostingId())).toList();
        var items = pending.stream()
            .map(item -> new AgentSession.PendingConfirmation(
                com.careeros.domain.CandidateFacts.CandidateFactKey.valueOf(item.factKey()),
                item.question(), UUID.fromString(item.jobPostingId())))
            .toList();
        return sessions.rememberRun(sessionId, candidateId, filtersOf(listing), order, items);
    }

    /** 这一轮成功执行的最后一次 search_jobs 用了什么参数；没有就返回 null。 */
    private static Map<String, String> searchArguments(AgentExecutor.AgentRun run) {
        Map<String, String> arguments = null;
        var observations = run.observations();
        int index = 0;
        for (AgentExecutor.TraceEntry entry : run.trace()) {
            if (!entry.accepted()) continue;
            boolean succeeded = index < observations.size() && observations.get(index).ok();
            if (succeeded && "search_jobs".equals(entry.tool())) arguments = entry.arguments();
            index++;
        }
        return arguments;
    }

    private static AgentSession.SessionFilters filtersOf(Map<String, String> arguments) {
        return new AgentSession.SessionFilters(
            enumValue(com.careeros.domain.DomainEnums.OpportunityTier.class, arguments.get("tier")),
            blankToNull(arguments.get("location")),
            enumValue(com.careeros.domain.DomainEnums.JobFamily.class, arguments.get("jobFamily")),
            AgentSession.SessionFilters.DEFAULT_LIMIT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 取值到这里已经过工具的校验，认不出来就当没筛选——不猜一个最接近的。 */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** 把会话翻译成执行器认得的上下文：上一轮的范围、列表顺序、还没答的资料项。 */
    private SessionContext contextOf(UUID candidateId, AgentSession session) {
        var filters = session.filters();
        var order = session.lastJobIdsInOrder();
        var jobs = new ArrayList<JobRef>();
        for (int index = 0; index < order.size(); index++) {
            // 标题等展示字段这里不重新评估——那会是一次额外的逐岗扇出。
            // 留空而不是编一个占位标题：占位标题会让模型以为它已经知道这是什么岗位。
            // 要详情就调 job_facts，那一次才计预算。
            jobs.add(new JobRef(index + 1, order.get(index), null, null, null, null));
        }
        var statuses = profiles.facts(candidateId).statuses();
        var pending = session.pendingConfirmations().stream()
            // 已经答过的不再列进来：原样回放会让用户以为系统没收到他的回答。
            .filter(item -> statuses.get(item.factKey())
                != com.careeros.domain.CandidateFacts.CandidateFactStatus.CONFIRMED)
            .map(item -> new PendingRef(item.factKey().name(), item.question(), item.jobPostingId()))
            .toList();
        return new SessionContext(session.sessionId(),
            filters == null ? null : filters.location(),
            filters == null || filters.tier() == null ? null : filters.tier().name(),
            filters == null || filters.jobFamily() == null ? null : filters.jobFamily().name(),
            jobs, pending, session.profileVersion());
    }

    /**
     * 从工具结果里拼出结构化岗位列表。
     *
     * <p>取的是 {@code search_jobs} 与 {@code job_facts} 返回的结构化字段，不是模型那段话。
     * 同一个岗位在两处都出现时后者覆盖前者——job_facts 带着限制条件，信息更全。
     */
    @SuppressWarnings("unchecked")
    private static List<JobView> jobs(List<Observation> observations) {
        var byId = new LinkedHashMap<String, JobView>();
        for (Observation observation : observations) {
            if (!observation.ok()) continue;
            if (observation.data().get("jobs") instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> map) {
                        var view = JobView.from((Map<String, Object>) map);
                        if (view != null) byId.put(view.jobPostingId(), view);
                    }
                }
            }
            if (observation.data().containsKey("jobPostingId")) {
                var view = JobView.from((Map<String, Object>) observation.data());
                if (view != null) byId.put(view.jobPostingId(), view);
            }
        }
        return List.copyOf(byId.values());
    }

    /**
     * 待确认事项，带上是哪个岗位提出的。
     *
     * <p>优先用 {@code pending_confirmations} 这一轮读到的；这一轮没读就退回会话里记着的，
     * 否则用户刷新之后会以为这些问题消失了。
     */
    @SuppressWarnings("unchecked")
    private static List<PendingView> pending(List<Observation> observations, SessionContext context) {
        for (Observation observation : observations) {
            if (!observation.ok() || !"pending_confirmations".equals(observation.tool())) continue;
            if (observation.data().get("items") instanceof List<?> rows) {
                var items = new ArrayList<PendingView>();
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> map) {
                        var typed = (Map<String, Object>) map;
                        items.add(new PendingView(String.valueOf(typed.get("factKey")),
                            String.valueOf(typed.get("question")), String.valueOf(typed.get("jobPostingId"))));
                    }
                }
                return List.copyOf(items);
            }
        }
        return context.pending().stream()
            .map(item -> new PendingView(item.factKey(), item.question(), String.valueOf(item.jobPostingId())))
            .toList();
    }

    /** @param sessionId 带上它就接着上一轮；为空表示新开一轮 */
    record AgentRunRequest(String question, UUID sessionId) {}

    /**
     * 每一步都外露：调了什么、为什么、被拒的原因、花了多少预算。看不见的拦截等于没拦截。
     *
     * @param budgetUnits 这一步真实花掉的预算单位，含工具内部的逐岗评估
     */
    record TraceStep(String tool, Map<String, String> arguments, String why, boolean accepted, String reason,
                     int budgetUnits) {}

    record ObservationView(String tool, boolean ok, String summary, Map<String, Object> data) {}

    /** 程序渲染的岗位结果。模型改不了这里的任何一个字段。 */
    record JobView(String jobPostingId, String jobTitle, String organizationName, String eligibilityStatus,
                   String tier, String applicationEndsOn, List<String> restrictions) {
        @SuppressWarnings("unchecked")
        static JobView from(Map<String, Object> row) {
            Object id = row.get("jobPostingId");
            if (id == null) return null;
            Object restrictions = row.get("restrictions");
            return new JobView(String.valueOf(id), text(row.get("jobTitle")), text(row.get("organizationName")),
                text(row.get("eligibilityStatus")), text(row.get("tier")), text(row.get("applicationEndsOn")),
                restrictions instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of());
        }
        private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    }

    /** @param jobPostingId 是哪个岗位提出的这个问题。页面要显示它，否则用户不知道在为什么而答 */
    record PendingView(String factKey, String question, String jobPostingId) {}

    /** 工具目录。与发给模型的那一份同源，调用方能据此核对边界，而不是只能相信。 */
    record ToolView(String name, String description, List<ParameterView> parameters) {}

    record ParameterView(String name, boolean required, String description, List<String> allowedValues) {}

    /**
     * @param narrative   叙述通过校验时才有值；被拒时为空，由调用方回落到确定性事实块
     * @param question    追问通过校验时才有值
     * @param violations  叙述或追问被拒的原因
     * @param groundedOn  这段话点名依据的观察序号。空表示它没有点名，或点名的依据不成立
     * @param budgetSpent 消耗的预算单位：工具调用次数 + 内部逐岗评估次数
     * @param jobs        程序渲染的岗位结果，取自工具的结构化数据，不是模型那段话
     */
    record AgentRunResponse(String outcome, String narrative, String question, List<String> violations,
                            List<Integer> groundedOn, int budgetSpent, int budgetLimit,
                            UUID sessionId, String profileVersion, List<JobView> jobs,
                            List<PendingView> pendingConfirmations,
                            List<ToolView> tools, List<TraceStep> trace, List<ObservationView> observations) {}

    static final class PlannerUnavailableException extends RuntimeException {
        PlannerUnavailableException(String message) { super(message); }
    }
}
