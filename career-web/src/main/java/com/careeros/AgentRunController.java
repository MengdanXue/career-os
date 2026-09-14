package com.careeros;

import com.careeros.application.agent.AgentExecutor;
import com.careeros.application.agent.AgentTooling.AgentPlanner;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 */
@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/agent-runs")
class AgentRunController {
    private final AgentExecutor executor;
    private final ObjectProvider<AgentPlanner> planner;
    private final boolean dynamicToolsEnabled;

    AgentRunController(AgentExecutor executor, ObjectProvider<AgentPlanner> planner,
                       @Value("${career-os.agent.dynamic-tools.enabled:false}") boolean dynamicToolsEnabled) {
        this.executor = executor;
        this.planner = planner;
        this.dynamicToolsEnabled = dynamicToolsEnabled;
    }

    @PostMapping
    AgentRunResponse run(@PathVariable("candidateId") UUID candidateId, @RequestBody AgentRunRequest request) {
        if (!dynamicToolsEnabled) {
            throw new PlannerUnavailableException("动态工具规划未启用；首阶段仅提供确定性查询和明确的确认操作。");
        }
        var active = planner.getIfAvailable();
        if (active == null) {
            throw new PlannerUnavailableException("没有可用的规划器：模型未启用，且不提供写死的替代流程。");
        }
        var run = executor.run(candidateId, request.question(), active);
        return new AgentRunResponse(
            run.outcome().name(),
            run.narrative(),
            run.question(),
            run.narrativeViolations(),
            run.toolCallsSpent(),
            executor.registeredTools(),
            run.trace().stream().map(entry -> new TraceStep(
                entry.tool(), entry.arguments(), entry.why(), entry.accepted(), entry.reason())).toList(),
            run.observations().stream().map(observation -> new ObservationView(
                observation.tool(), observation.ok(), observation.summary(), observation.data())).toList());
    }

    record AgentRunRequest(String question) {}

    /** 每一步都外露：调了什么、为什么、被拒的原因。看不见的拦截等于没拦截。 */
    record TraceStep(String tool, Map<String, String> arguments, String why, boolean accepted, String reason) {}

    record ObservationView(String tool, boolean ok, String summary, Map<String, Object> data) {}

    /**
     * @param narrative           叙述通过校验时才有值；被拒时为空，由调用方回落到确定性事实块
     * @param narrativeViolations 叙述被拒的原因
     * @param registeredTools     这次运行可用的工具，全部只读——让调用方能核对边界，而不是只能相信
     */
    record AgentRunResponse(String outcome, String narrative, String question, List<String> narrativeViolations,
                            int toolCallsSpent, List<String> registeredTools, List<TraceStep> trace,
                            List<ObservationView> observations) {}

    static final class PlannerUnavailableException extends RuntimeException {
        PlannerUnavailableException(String message) { super(message); }
    }
}
