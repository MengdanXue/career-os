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

    /** 收尾没有点名依据时的拒绝理由。 */
    static final String UNGROUNDED_FINISH = "这段收尾叙述没有点名它依据哪一条工具结果";

    /** 点名的依据不存在，或指向一条失败的观察。 */
    static final String BASIS_NOT_SUPPORTED = "这段话点名的依据不成立：";

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
        return run(candidateId, question, planner, AgentTooling.SessionContext.none());
    }

    /**
     * 带上一轮会话续跑。
     *
     * <p>不带的话，"动态执行"每一轮都是从零开始：用户在追问之后只答一句"余杭"，
     * 模型看不到上一轮限定的是杭州；用户说"第二个"更是无从指认。
     * 会话上下文进 {@link PlanningState}（模型看得到）也进 {@link ToolContext}（工具解析序号用）。
     */
    public AgentRun run(UUID candidateId, String question, AgentPlanner planner,
                        AgentTooling.SessionContext session) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(planner, "planner");
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question is required");

        var budget = ToolCallBudget.of(callBudget);
        var catalogue = catalogue();
        var observations = new ArrayList<Observation>();
        var trace = new ArrayList<TraceEntry>();

        for (int step = 0; step < maxSteps; step++) {
            var state = new PlanningState(question, observations,
                Math.max(0, callBudget - budget.spent()), catalogue, session);
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
                var violations = checkFinish(finish, observations);
                return new AgentRun(Outcome.FINISHED, violations.isEmpty() ? finish.narrative() : null,
                    null, observations, trace, budget.spent(), callBudget, violations,
                    violations.isEmpty() ? finish.basis().observationIndexes() : List.of());
            }
            if (next instanceof PlannerStep.AskUser ask) {
                // 追问和收尾同样是模型直接说给用户听的话，所以走同一套校验。
                // 只校验 FINISH 的话，"ASK 你这条线适配度不错，要继续看吗"就能整句绕过去。
                var violations = checkAsk(ask, observations);
                return new AgentRun(Outcome.ASKED_USER, null, violations.isEmpty() ? ask.question() : null,
                    observations, trace, budget.spent(), callBudget, violations,
                    violations.isEmpty() ? ask.basis().observationIndexes() : List.of());
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
                // 候选人和预算都由这里绑定，规划器给不了；会话上下文也在这里交给工具，
                // 它才能把"第二个"解析回上一轮列表里的那个岗位。
                observation = tool.invoke(new ToolContext(candidateId, call, budget, session));
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
     * <p>除了叙述校验器那套规则，这里多一条：<b>必须点名依据的是哪一条观察，且那几条要成功。</b>
     *
     * <p>此前的判据是"这次运行里存在成功的观察"。那只说明附近有数据，不说明这段话用了它——
     * 查了关注清单，然后就"这些岗位都挺适合你的"，照样通过。不含数字、不含判定词也不等于有依据。
     * 点名之后，依据是否成立就成了可以核对的事：序号必须在范围内，对应的观察必须是成功的。
     */
    private static List<String> checkFinish(PlannerStep.Finish finish, List<Observation> observations) {
        var violations = new ArrayList<>(NARRATIVE.validateNarrative(finish.narrative()).violations());
        // 收尾不接受"纯澄清"：那是追问的选项，不是结束的理由。
        if (!finish.basis().declared() || finish.basis().clarifyingOnly()
            || finish.basis().observationIndexes().isEmpty()) {
            violations.add(UNGROUNDED_FINISH);
            return List.copyOf(violations);
        }
        violations.addAll(checkBasis(finish.basis(), observations));
        return List.copyOf(violations);
    }

    /**
     * 追问的校验。
     *
     * <p>追问允许不依据任何结果——"你说的杭州是指市区还是整个市？"本来就不需要依据，
     * 但要明说它是纯澄清（{@code BASIS none}），而不是含糊过去。
     * 一旦点了依据，就和收尾一样要核对。它同样必须是问句，且不许带数值、判定词或概率说法。
     */
    private static List<String> checkAsk(PlannerStep.AskUser ask, List<Observation> observations) {
        String question = ask.question();
        var violations = new ArrayList<>(NARRATIVE.validateNarrative(question).violations());
        if (question != null && !question.isBlank() && question.indexOf('？') < 0 && question.indexOf('?') < 0) {
            violations.add(ASK_IS_NOT_A_QUESTION);
        }
        if (!ask.basis().declared()) {
            violations.add(UNGROUNDED_FINISH);
            return List.copyOf(violations);
        }
        violations.addAll(checkBasis(ask.basis(), observations));
        return List.copyOf(violations);
    }

    /** 点名的每一条依据都要在范围内，且确实是成功的观察。 */
    private static List<String> checkBasis(AgentTooling.Basis basis, List<Observation> observations) {
        var violations = new ArrayList<String>();
        for (int index : basis.observationIndexes()) {
            if (index < 1 || index > observations.size()) {
                violations.add(BASIS_NOT_SUPPORTED + "没有第 " + index + " 条工具结果");
                continue;
            }
            var observation = observations.get(index - 1);
            if (!observation.ok()) {
                violations.add(BASIS_NOT_SUPPORTED + "第 " + index + " 条是失败的结果（"
                    + observation.tool() + "）");
            }
        }
        return violations;
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
                           int budgetLimit, List<String> violations, List<Integer> basis) {
        public AgentRun {
            observations = List.copyOf(observations == null ? List.of() : observations);
            trace = List.copyOf(trace == null ? List.of() : trace);
            violations = List.copyOf(violations == null ? List.of() : violations);
            basis = List.copyOf(basis == null ? List.of() : basis);
        }
        AgentRun(Outcome outcome, String narrative, String question, List<Observation> observations,
                 List<TraceEntry> trace, int budgetSpent, int budgetLimit, List<String> violations) {
            this(outcome, narrative, question, observations, trace, budgetSpent, budgetLimit, violations, List.of());
        }
        /** 叙述或追问被拒时对应字段为空；调用方回落到确定性事实块。 */
        public boolean textRejected() { return !violations.isEmpty(); }
        /**
         * 这段收尾点名依据的那几条观察。
         *
         * <p>不是"这次运行成功读到过几条"——那个数字回答不了"这句话凭什么这么说"。
         */
        public List<Integer> groundedOn() { return basis; }
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
