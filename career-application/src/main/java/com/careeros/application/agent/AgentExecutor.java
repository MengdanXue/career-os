package com.careeros.application.agent;

import com.careeros.application.ToolCallBudget;
import com.careeros.domain.AnswerNarrativeValidator;
import com.careeros.application.agent.AgentTooling.AgentPlanner;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.PlanningState;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.agent.AgentTooling.ToolCall;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 执行规划器的决定，并守住它能做什么。
 *
 * <p>边界在这里，不在提示词里。模型能提出任何请求，但：
 *
 * <ul>
 *   <li><b>只读。</b> 注册表里只有只读工具；写入工具连注册的机会都没有。带写入语义的请求
 *       在这一层被拒绝，不是被劝阻。</li>
 *   <li><b>候选人由执行器绑定。</b> 规划器的 {@link ToolCall} 里没有候选人字段；
 *       即使它在参数里塞一个，也会被忽略——否则报出别人的 ID 就能读到别人的资料。</li>
 *   <li><b>共享预算。</b> 一次运行里所有工具调用共用一份额度，不是每个工具各有一份；
 *       用完就拒绝，并明说用完了。</li>
 *   <li><b>步数上限。</b> 规划器可能永远不收敛——它只要一直要求调工具就行。
 *       步数上限保证运行一定结束，即使一次工具都没调（比如它反复要求同一个被拒的工具）。</li>
 * </ul>
 *
 * <p>每一步都留在轨迹里：调了什么、为什么、被拒的原因。事后要能看出它是依据结果选的路，
 * 还是在走固定流程——这正是"不要把硬编码流程叫作 Agent"要检验的东西。
 */
public final class AgentExecutor {
    /** 规划器最多被问多少次。到顶即停，无论它还想做什么。 */
    public static final int DEFAULT_MAX_STEPS = 8;

    private static final AnswerNarrativeValidator NARRATIVE = new AnswerNarrativeValidator();

    private final Map<String, ReadOnlyTool> tools;
    private final int maxSteps;
    private final int callBudget;

    public AgentExecutor(List<ReadOnlyTool> tools) {
        this(tools, DEFAULT_MAX_STEPS, ToolCallBudget.DEFAULT_LIMIT);
    }

    public AgentExecutor(List<ReadOnlyTool> tools, int maxSteps, int callBudget) {
        var registry = new LinkedHashMap<String, ReadOnlyTool>();
        for (ReadOnlyTool tool : tools == null ? List.<ReadOnlyTool>of() : tools) {
            registry.put(tool.name(), tool);
        }
        this.tools = Map.copyOf(registry);
        if (maxSteps < 1) throw new IllegalArgumentException("maxSteps must be positive");
        this.maxSteps = maxSteps;
        this.callBudget = callBudget;
    }

    public AgentRun run(UUID candidateId, String question, AgentPlanner planner) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(planner, "planner");
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question is required");

        var budget = ToolCallBudget.of(callBudget);
        var observations = new ArrayList<Observation>();
        var trace = new ArrayList<TraceEntry>();

        for (int step = 0; step < maxSteps; step++) {
            var state = new PlanningState(question, observations, Math.max(0, callBudget - budget.spent()));
            PlannerStep next;
            try {
                next = planner.next(state);
            } catch (RuntimeException failure) {
                // 规划器坏了不该把整次运行变成 500：已经取到的观察仍然有用。
                trace.add(TraceEntry.rejected("(planner)", "规划器抛出异常：" + failure.getClass().getSimpleName()));
                return new AgentRun(Outcome.PLANNER_FAILED, null, null, observations, trace, budget.spent());
            }
            if (next == null) {
                trace.add(TraceEntry.rejected("(planner)", "规划器没有给出下一步"));
                return new AgentRun(Outcome.PLANNER_FAILED, null, null, observations, trace, budget.spent());
            }
            if (next instanceof PlannerStep.Finish finish) {
                // 叙述在这里就校验，不等渲染阶段。带数值或判定词的叙述不能离开执行器，
                // 否则调用方可能先把它用掉——比如记进日志或直接回给用户。
                var checked = NARRATIVE.validateNarrative(finish.narrative());
                return new AgentRun(Outcome.FINISHED, checked.accepted() ? finish.narrative() : null,
                    null, observations, trace, budget.spent(), checked.violations());
            }
            if (next instanceof PlannerStep.AskUser ask) {
                return new AgentRun(Outcome.ASKED_USER, null, ask.question(), observations, trace, budget.spent());
            }
            var call = ((PlannerStep.CallTool) next).call();
            String why = ((PlannerStep.CallTool) next).why();
            var tool = tools.get(call.tool());
            if (tool == null) {
                // 不在注册表里就是不存在。写入工具正是靠"不注册"被挡住的。
                observations.add(Observation.failed(call.tool(), "没有这个工具，或者它不在只读工具面里。"));
                trace.add(TraceEntry.rejected(call.tool(), "未注册的工具"));
                continue;
            }
            if (!budget.tryConsume()) {
                observations.add(Observation.failed(call.tool(), "本次调用预算已用完。"));
                trace.add(TraceEntry.rejected(call.tool(), "超出共享调用预算"));
                return new AgentRun(Outcome.BUDGET_EXHAUSTED, null, null, observations, trace, budget.spent());
            }
            Observation observation;
            try {
                // 候选人由这里绑定，规划器给不了。
                observation = tool.invoke(candidateId, call);
            } catch (RuntimeException failure) {
                observation = Observation.failed(call.tool(),
                    "这个工具这次没能返回结果：" + failure.getClass().getSimpleName());
            }
            observations.add(observation);
            trace.add(TraceEntry.called(call, why, observation.ok()));
        }
        return new AgentRun(Outcome.STEP_LIMIT_REACHED, null, null, observations, trace, budget.spent());
    }

    public List<String> registeredTools() { return List.copyOf(tools.keySet()); }

    public enum Outcome {
        /** 规划器自己收敛了，给出了叙述。 */
        FINISHED,
        /** 规划器决定向用户追问。 */
        ASKED_USER,
        /** 共享调用预算用完。 */
        BUDGET_EXHAUSTED,
        /** 规划器一直不收敛，步数到顶。 */
        STEP_LIMIT_REACHED,
        /** 规划器本身出错或没给出下一步。 */
        PLANNER_FAILED
    }

    /**
     * @param narrative 仅在 FINISHED 时有值；它还要过叙述校验器，这里不做判定
     * @param question  仅在 ASKED_USER 时有值
     */
    public record AgentRun(Outcome outcome, String narrative, String question,
                           List<Observation> observations, List<TraceEntry> trace, int toolCallsSpent,
                           List<String> narrativeViolations) {
        public AgentRun {
            observations = List.copyOf(observations == null ? List.of() : observations);
            trace = List.copyOf(trace == null ? List.of() : trace);
            narrativeViolations = List.copyOf(narrativeViolations == null ? List.of() : narrativeViolations);
        }
        AgentRun(Outcome outcome, String narrative, String question, List<Observation> observations,
                 List<TraceEntry> trace, int toolCallsSpent) {
            this(outcome, narrative, question, observations, trace, toolCallsSpent, List.of());
        }
        /** 叙述被拒时 {@code narrative} 为空；调用方回落到确定性事实块。 */
        public boolean narrativeRejected() { return !narrativeViolations.isEmpty(); }
    }

    /** 一步轨迹。被拒的步骤也要留下来——看不见的拦截等于没拦截。 */
    public record TraceEntry(String tool, Map<String, String> arguments, String why, boolean accepted, String reason) {
        static TraceEntry called(ToolCall call, String why, boolean ok) {
            return new TraceEntry(call.tool(), call.arguments(), why, true, ok ? "ok" : "工具返回失败");
        }
        static TraceEntry rejected(String tool, String reason) {
            return new TraceEntry(tool, Map.of(), null, false, reason);
        }
    }
}
