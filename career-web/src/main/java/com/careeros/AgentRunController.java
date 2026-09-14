package com.careeros;

import com.careeros.application.AgentSessionService;
import com.careeros.application.agent.AgentExecutor;
import com.careeros.application.agent.AgentTooling.AgentPlanner;
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
 * <p><b>带 sessionId 的请求要核对归属。</b> 会话 ID 是可猜的 UUID；不核对就等于
 * 报出别人的会话 ID 就能沿用别人那一轮的上下文。
 */
@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/agent-runs")
class AgentRunController {
    private final AgentExecutor executor;
    private final ObjectProvider<AgentPlanner> planner;
    private final AgentSessionService sessions;

    AgentRunController(AgentExecutor executor, ObjectProvider<AgentPlanner> planner, AgentSessionService sessions) {
        this.executor = executor;
        this.planner = planner;
        this.sessions = sessions;
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
        var run = executor.run(candidateId, request.question(), active);
        return new AgentRunResponse(
            run.outcome().name(),
            run.narrative(),
            run.question(),
            run.violations(),
            run.budgetSpent(),
            run.budgetLimit(),
            run.groundedIn(),
            session == null ? null : session.sessionId(),
            session == null ? null : session.profileVersion(),
            executor.toolCatalogue().stream().map(tool -> new ToolView(tool.name(), tool.description(),
                tool.parameters().stream().map(parameter -> new ParameterView(parameter.name(),
                    parameter.required(), parameter.description(), parameter.allowedValues())).toList())).toList(),
            run.trace().stream().map(entry -> new TraceStep(
                entry.tool(), entry.arguments(), entry.why(), entry.accepted(), entry.reason(),
                entry.budgetUnits())).toList(),
            run.observations().stream().map(observation -> new ObservationView(
                observation.tool(), observation.ok(), observation.summary(), observation.data())).toList());
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

    /** 工具目录。与发给模型的那一份同源，调用方能据此核对边界，而不是只能相信。 */
    record ToolView(String name, String description, List<ParameterView> parameters) {}

    record ParameterView(String name, boolean required, String description, List<String> allowedValues) {}

    /**
     * @param narrative   叙述通过校验时才有值；被拒时为空，由调用方回落到确定性事实块
     * @param question    追问通过校验时才有值
     * @param violations  叙述或追问被拒的原因
     * @param budgetSpent 消耗的预算单位：工具调用次数 + 内部逐岗评估次数
     * @param groundedIn  这次回答背后有几条成功的工具结果。零表示没有依据
     * @param tools       这次运行可用的工具，全部只读
     */
    record AgentRunResponse(String outcome, String narrative, String question, List<String> violations,
                            int budgetSpent, int budgetLimit, long groundedIn, UUID sessionId, String profileVersion,
                            List<ToolView> tools, List<TraceStep> trace, List<ObservationView> observations) {}

    static final class PlannerUnavailableException extends RuntimeException {
        PlannerUnavailableException(String message) { super(message); }
    }
}
