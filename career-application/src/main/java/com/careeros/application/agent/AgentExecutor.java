package com.careeros.application.agent;

import com.careeros.application.ToolCallBudget;
import com.careeros.domain.AnswerNarrativeValidator;
import com.careeros.application.agent.AgentTooling.AgentPlanner;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.PlanningState;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.agent.AgentTooling.ToolCall;
import com.careeros.application.agent.AgentTooling.ToolContext;
import com.careeros.application.agent.AgentTooling.ToolSpec;
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
 *   <li><b>共享预算，按真实扇出计。</b> 一次运行里所有工具共用一份额度。一个单位是
 *       "一次工具调用"或"一次逐岗评估"——预算对象直接交给工具，工具内部每评估一个岗位就扣一次。
 *       只按工具调用次数计，模型调三次工具就能触发上百次评估，预算等于没有。</li>
 *   <li><b>步数上限。</b> 规划器可能永远不收敛——它只要一直要求调工具就行。
 *       步数上限保证运行一定结束，即使一次工具都没调（比如它反复要求同一个被拒的工具）。</li>
 *   <li><b>收尾的话也要有依据。</b> FINISH 与 ASK 都要过叙述校验，且一次成功的工具结果都没有时，
 *       FINISH 不许留下任何叙述。</li>
 * </ul>
 *
 * <p>每一步都留在轨迹里：调了什么、为什么、被拒的原因。事后要能看出它是依据结果选的路，
 * 还是在走固定流程——这正是"不要把硬编码流程叫作 Agent"要检验的东西。
 */
public final class AgentExecutor {
    /** 规划器最多被问多少次。到顶即停，无论它还想做什么。 */
    public static final int DEFAULT_MAX_STEPS = 8;

    /** 一次成功的工具结果都没有时，FINISH 的叙述被整段拒绝的理由。 */
    static final String UNGROUNDED_FINISH = "没有任何成功的工具结果，这段收尾叙述没有依据";

    /** ASK 不是问句时的拒绝理由。陈述句走 ASK 通道就绕开了"结论要有依据"。 */
    static final String ASK_IS_NOT_A_QUESTION = "追问不是一个问句；结论不能借追问的通道发出";

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
        this.tools = java.util.Collections.unmodifiableMap(registry);
        if (maxSteps < 1) throw new IllegalArgumentException("maxSteps must be positive");
        this.maxSteps = maxSteps;
        this.callBudget = callBudget;
    }

    public AgentRun run(UUID candidateId, String question, AgentPlanner planner) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(planner, "planner");
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question is required");

        var budget = ToolCallBudget.of(callBudget);
        var catalogue = catalogue();
        var observations = new ArrayList<Observation>();
        var trace = new ArrayList<TraceEntry>();

        for (int step = 0; step < maxSteps; step++) {
            var state = new PlanningState(question, observations,
                Math.max(0, callBudget - budget.spent()), catalogue);
            PlannerStep next;
            try {
                next = planner.next(state);
            } catch (RuntimeException failure) {
                // 规划器坏了不该把整次运行变成 500：已经取到的观察仍然有用。
                trace.add(TraceEntry.rejected("(planner)", "规划器抛出异常：" + failure.getClass().getSimpleName()));
                return finished(Outcome.PLANNER_FAILED, null, null, observations, trace, budget);
            }
            if (next == null) {
                trace.add(TraceEntry.rejected("(planner)", "规划器没有给出下一步"));
                return finished(Outcome.PLANNER_FAILED, null, null, observations, trace, budget);
            }
            if (next instanceof PlannerStep.Finish finish) {
                // 叙述在这里就校验，不等渲染阶段。带数值或判定词的叙述不能离开执行器，
                // 否则调用方可能先把它用掉——比如记进日志或直接回给用户。
                var violations = checkFinish(finish.narrative(), observations);
                return new AgentRun(Outcome.FINISHED, violations.isEmpty() ? finish.narrative() : null,
                    null, observations, trace, budget.spent(), callBudget, violations);
            }
            if (next instanceof PlannerStep.AskUser ask) {
                // 追问和收尾同样是模型直接说给用户听的话，所以走同一套校验。
                // 只校验 FINISH 的话，"ASK 你这条线适配度不错，要继续看吗"就能整句绕过去。
                var violations = checkAsk(ask.question());
                return new AgentRun(Outcome.ASKED_USER, null, violations.isEmpty() ? ask.question() : null,
                    observations, trace, budget.spent(), callBudget, violations);
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
                return finished(Outcome.BUDGET_EXHAUSTED, null, null, observations, trace, budget);
            }
            int before = budget.spent();
            Observation observation;
            try {
                // 候选人和预算都由这里绑定，规划器给不了。
                observation = tool.invoke(new ToolContext(candidateId, call, budget));
            } catch (RuntimeException failure) {
                observation = Observation.failed(call.tool(),
                    "这个工具这次没能返回结果：" + failure.getClass().getSimpleName());
            }
            observations.add(observation);
            trace.add(TraceEntry.called(call, why, observation.ok(), budget.spent() - before + 1));
        }
        return finished(Outcome.STEP_LIMIT_REACHED, null, null, observations, trace, budget);
    }

    /**
     * 收尾叙述的校验。
     *
     * <p>除了叙述校验器那套规则，这里多一条：一次成功的工具结果都没有时，FINISH 不许留下叙述。
     * 那种情况下模型说的任何"结论"都是凭空的——它连一条数据都没读到。
     * 不含数字、不含判定词并不等于有依据："这些岗位都挺适合你的"两条都不违反。
     */
    private static List<String> checkFinish(String narrative, List<Observation> observations) {
        var violations = new ArrayList<>(NARRATIVE.validateNarrative(narrative).violations());
        if (observations.stream().noneMatch(Observation::ok)) violations.add(UNGROUNDED_FINISH);
        return List.copyOf(violations);
    }

    /**
     * 追问的校验。
     *
     * <p>追问允许在还没有任何工具结果时发出——"你说的杭州是指市区还是整个市？"本来就不需要依据。
     * 但它必须真的是个问句，并且同样不许带数值、判定词或概率说法。
     */
    private static List<String> checkAsk(String question) {
        var violations = new ArrayList<>(NARRATIVE.validateNarrative(question).violations());
        if (question != null && !question.isBlank() && question.indexOf('？') < 0 && question.indexOf('?') < 0) {
            violations.add(ASK_IS_NOT_A_QUESTION);
        }
        return List.copyOf(violations);
    }

    private AgentRun finished(Outcome outcome, String narrative, String question, List<Observation> observations,
                              List<TraceEntry> trace, ToolCallBudget budget) {
        return new AgentRun(outcome, narrative, question, observations, trace, budget.spent(), callBudget, List.of());
    }

    private List<ToolSpec> catalogue() {
        return tools.values().stream().map(ToolSpec::of).toList();
    }

    public List<String> registeredTools() { return List.copyOf(tools.keySet()); }

    /** 完整的工具目录。模型首轮收到的就是它，调用方也能拿去核对边界。 */
    public List<ToolSpec> toolCatalogue() { return catalogue(); }

    public int budgetLimit() { return callBudget; }

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
     * @param narrative   仅在 FINISHED 且叙述通过校验时有值
     * @param question    仅在 ASKED_USER 且追问通过校验时有值
     * @param budgetSpent 这次运行消耗的预算单位：工具调用次数 + 内部逐岗评估次数
     * @param violations  收尾叙述或追问被拒的原因；非空表示上面那两个字段被清掉了
     */
    public record AgentRun(Outcome outcome, String narrative, String question,
                           List<Observation> observations, List<TraceEntry> trace, int budgetSpent,
                           int budgetLimit, List<String> violations) {
        public AgentRun {
            observations = List.copyOf(observations == null ? List.of() : observations);
            trace = List.copyOf(trace == null ? List.of() : trace);
            violations = List.copyOf(violations == null ? List.of() : violations);
        }
        /** 叙述或追问被拒时对应字段为空；调用方回落到确定性事实块。 */
        public boolean textRejected() { return !violations.isEmpty(); }
        /** 这次回答背后有几条成功的工具结果。零就是没有依据，调用方应当据此收紧展示。 */
        public long groundedIn() { return observations.stream().filter(Observation::ok).count(); }
    }

    /**
     * 一步轨迹。被拒的步骤也要留下来——看不见的拦截等于没拦截。
     *
     * @param budgetUnits 这一步实际花掉的预算单位；被拒的步骤为 0
     */
    public record TraceEntry(String tool, Map<String, String> arguments, String why, boolean accepted,
                             String reason, int budgetUnits) {
        static TraceEntry called(ToolCall call, String why, boolean ok, int budgetUnits) {
            return new TraceEntry(call.tool(), call.arguments(), why, true, ok ? "ok" : "工具返回失败", budgetUnits);
        }
        static TraceEntry rejected(String tool, String reason) {
            return new TraceEntry(tool, Map.of(), null, false, reason, 0);
        }
    }
}
