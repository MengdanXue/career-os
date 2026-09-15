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
 *   <li><b>选的那条模板要站得住。</b> 句子由程序渲染之后，能出错的只剩"选错了哪一条"——
 *       查到了三个岗位照样能选"这个范围内没有找到岗位"，读起来毫无破绽。
 *       所以每条模板声明它断言了什么，执行器拿点名的那几条结果去核。</li>
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

    /** 模板名不在封闭清单里，或槽位对不上时的拒绝理由。 */
    static final String UNKNOWN_TEMPLATE = "这不是一条可用的模板；用户可见的话只能从固定模板里选";

    /** 模板本身的断言与点名的结果对不上时的拒绝理由。 */
    static final String TEMPLATE_NOT_APPLICABLE = "这条模板的适用条件不成立：";

    /** 要确认的那一项没有绑定到真实的待确认项、提出它的岗位与资料版本。 */
    static final String UNBOUND_CONFIRMATION = "这条确认没有绑定到实际的待确认项：";

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
                trace.add(TraceEntry.rejected("(planner)", "规划器抛出异常：" + failure.getClass().getSimpleName(), 0));
                return finished(Outcome.PLANNER_FAILED, null, null, observations, trace, budget);
            }
            if (next == null) {
                trace.add(TraceEntry.rejected("(planner)", "规划器没有给出下一步", 0));
                return finished(Outcome.PLANNER_FAILED, null, null, observations, trace, budget);
            }
            if (next instanceof PlannerStep.Finish finish) {
                // 句子由这里渲染，模型只给模板名和结构化引用。它写不出自由句子，
                // 所以也写不出"你这条线基本没什么硬门槛挡着"这类没人算过的判断。
                String rendered = renderClosing(finish);
                var violations = checkFinish(finish, rendered, observations);
                return new AgentRun(Outcome.FINISHED, violations.isEmpty() ? rendered : null,
                    null, observations, trace, budget.spent(), callBudget, violations,
                    violations.isEmpty() ? finish.basis().observationIndexes() : List.of());
            }
            if (next instanceof PlannerStep.AskUser ask) {
                String rendered = renderQuestion(ask);
                var checked = checkAsk(ask, rendered, observations, session);
                return new AgentRun(Outcome.ASKED_USER, null, checked.ok() ? rendered : null,
                    observations, trace, budget.spent(), callBudget, checked.violations(),
                    checked.ok() ? ask.basis().observationIndexes() : List.of(),
                    checked.ok() ? checked.binding() : null);
            }
            var call = ((PlannerStep.CallTool) next).call();
            String why = ((PlannerStep.CallTool) next).why();
            var tool = tools.get(call.tool());
            if (tool == null) {
                // 不在注册表里就是不存在。写入工具正是靠"不注册"被挡住的。
                observations.add(Observation.failed(call.tool(), "没有这个工具，或者它不在只读工具面里。"));
                trace.add(TraceEntry.rejected(call.tool(), "未注册的工具", observations.size()));
                continue;
            }
            if (!budget.tryConsume()) {
                observations.add(Observation.failed(call.tool(), "本次调用预算已用完。"));
                trace.add(TraceEntry.rejected(call.tool(), "超出共享调用预算", observations.size()));
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
            trace.add(TraceEntry.called(call, why, observation.ok(), budget.spent() - before + 1,
                observations.size()));
        }
        return finished(Outcome.STEP_LIMIT_REACHED, null, null, observations, trace, budget);
    }

    /** 把收尾模板渲染成用户看得到的那句话；模板名或槽位对不上时返回 null。 */
    private static String renderClosing(PlannerStep.Finish finish) {
        try {
            return AnswerTemplates.Closing.valueOf(finish.template()).render(finish.slots());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String renderQuestion(PlannerStep.AskUser ask) {
        try {
            return AnswerTemplates.Question.valueOf(ask.template()).render(ask.slots());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * 收尾的校验。
     *
     * <p>模板渲染不出来就没有这句话——模型报了一个不存在的模板名，或者槽位对不上。
     *
     * <p>依据仍要点名，而且点到的必须是成功的观察。此前的判据是"这次运行里存在成功的观察"：
     * 那只说明附近有数据，不说明这段话用了它——查了关注清单，然后收尾说岗位怎么样，照样通过。
     *
     * <p>渲染出来的句子还要过一遍叙述校验器。模板是我们自己写的，本来就该通过；
     * 留着这一道是为了将来有人改模板时，改出判定词会当场红，而不是等用户看到。
     */
    private static List<String> checkFinish(PlannerStep.Finish finish, String rendered,
                                            List<Observation> observations) {
        var violations = new ArrayList<String>();
        if (rendered == null) {
            violations.add(UNKNOWN_TEMPLATE);
            return List.copyOf(violations);
        }
        violations.addAll(NARRATIVE.validateNarrative(rendered).violations());
        // 收尾不接受"纯澄清"：那是追问的选项，不是结束的理由。
        if (!finish.basis().declared() || finish.basis().clarifyingOnly()
            || finish.basis().observationIndexes().isEmpty()) {
            violations.add(UNGROUNDED_FINISH);
            return List.copyOf(violations);
        }
        var required = AnswerTemplates.Closing.valueOf(finish.template()).requires();
        var basisViolations = checkBasis(finish.basis(), observations, required.acceptsFailures());
        violations.addAll(basisViolations);
        if (basisViolations.isEmpty()) {
            // 依据存在且成立之后，还要问一句：这条模板断言的东西，这几条结果支不支持。
            violations.addAll(checkApplicability(required, cited(finish.basis(), observations)));
        }
        return List.copyOf(violations);
    }

    /**
     * 追问的校验。
     *
     * <p>追问允许不依据任何结果——"你说的杭州是指市区还是整个市？"本来就不需要依据，
     * 但要明说它是纯澄清（{@code BASIS none}），而不是含糊过去。
     * 一旦点了依据，就和收尾一样要核对。
     *
     * <p><b>但有的追问里带着断言。</b>"这个范围内没有找到岗位，要不要放宽城市或职位类别？"
     * ——一次查询都没做过的时候，这个断言凭空成立，而用户会以为系统查过了。
     * 所以模板的适用条件对追问同样生效，只有真正的澄清那一档（{@code Evidence.NONE}）才不用。
     *
     * <p>确认类追问再多一道：问的那一项必须真的在等着被确认，并且说得出是哪个岗位提的、
     * 在哪一版资料下问的。三样齐了才返回 {@link AgentTooling.FactBinding}。
     */
    private static CheckedQuestion checkAsk(PlannerStep.AskUser ask, String rendered,
                                            List<Observation> observations,
                                            AgentTooling.SessionContext session) {
        var violations = new ArrayList<String>();
        if (rendered == null) {
            violations.add(UNKNOWN_TEMPLATE);
            return new CheckedQuestion(List.copyOf(violations), null);
        }
        violations.addAll(NARRATIVE.validateNarrative(rendered).violations());
        if (!ask.basis().declared()) {
            violations.add(UNGROUNDED_FINISH);
            return new CheckedQuestion(List.copyOf(violations), null);
        }
        var template = AnswerTemplates.Question.valueOf(ask.template());
        var basisViolations = checkBasis(ask.basis(), observations, template.requires().acceptsFailures());
        violations.addAll(basisViolations);
        if (!basisViolations.isEmpty()) return new CheckedQuestion(List.copyOf(violations), null);

        var cited = cited(ask.basis(), observations);
        violations.addAll(checkApplicability(template.requires(), cited));
        // 确认类追问的适用条件不是"读到过一份清单"，而是"问的这一项真的在等着被确认"，
        // 并且要说得出是哪个岗位提的、在哪一版资料下问的。
        AgentTooling.FactBinding binding = null;
        if (template == AnswerTemplates.Question.CONFIRM_FACT) {
            binding = bindConfirmation(ask.slots().get("fact"), cited, session, violations);
        }
        return new CheckedQuestion(List.copyOf(violations), binding);
    }

    /** 追问的校验结果：被拒的理由，以及成立时它绑定到的那一项。 */
    private record CheckedQuestion(List<String> violations, AgentTooling.FactBinding binding) {
        boolean ok() { return violations.isEmpty(); }
    }

    /** 这段话点名的那几条观察。到这里它们都已经确认存在且成功。 */
    private static List<Observation> cited(AgentTooling.Basis basis, List<Observation> observations) {
        var cited = new ArrayList<Observation>();
        for (int index : basis.observationIndexes()) {
            if (index >= 1 && index <= observations.size()) cited.add(observations.get(index - 1));
        }
        return List.copyOf(cited);
    }

    /**
     * 模板自己的适用条件。
     *
     * <p>这一道拦的不是措辞——措辞早就由程序渲染了，能出错的只剩"选错了哪一条模板"。
     * 选错的那句话读起来完全正常："这个范围内没有找到岗位。"查到了三个也能这么说，
     * 叙述校验器挑不出毛病。只有拿它断言的东西去对点名的结果，才看得出它在说一件没发生的事。
     */
    private static List<String> checkApplicability(AnswerTemplates.Evidence required,
                                                   List<Observation> cited) {
        if (required.holdsFor(cited)) return List.of();
        return List.of(TEMPLATE_NOT_APPLICABLE + required.unmet());
    }

    /**
     * 把"要不要现在确认某一项"绑定到真实的待确认项。
     *
     * <p>三样缺一不可：哪一项、哪个岗位提的、在哪一版资料下问的。
     * 少了第一样，系统会凭空问一句用户答了也没有归属的话——没有哪个岗位在等它，
     * 也没有哪条结论会因此改变；少了第三样，他的回答会被记到一份说不清是哪一版的资料上，
     * 确认写入那个乐观版本检查就恒真，等于没检查。
     *
     * <p>来源有两处：这一轮刚读到的待确认清单，或上一轮记下来还没答的那些。
     * 两处都没有就是不成立——不拿"附近读到过一份清单"顶上去。
     */
    @SuppressWarnings("unchecked")
    private static AgentTooling.FactBinding bindConfirmation(String fact, List<Observation> cited,
                                                             AgentTooling.SessionContext session,
                                                             List<String> violations) {
        String factKey = fact == null ? null : fact.strip().toUpperCase();
        if (factKey == null || factKey.isEmpty()) {
            violations.add(UNBOUND_CONFIRMATION + "没有说清要确认哪一项");
            return null;
        }
        String askingJob = null;
        for (Observation observation : cited) {
            if (!AnswerTemplates.PENDING_CONFIRMATIONS.equals(observation.tool())) continue;
            if (!(observation.data().get("items") instanceof List<?> rows)) continue;
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map
                    && factKey.equals(String.valueOf(((Map<String, Object>) map).get("factKey")))) {
                    askingJob = String.valueOf(((Map<String, Object>) map).get("jobPostingId"));
                }
            }
        }
        if (askingJob == null) {
            askingJob = session.pending().stream()
                .filter(item -> factKey.equalsIgnoreCase(item.factKey()))
                .map(item -> item.jobPostingId() == null ? null : item.jobPostingId().toString())
                .findFirst().orElse(null);
        }
        if (askingJob == null) {
            violations.add(UNBOUND_CONFIRMATION + "「" + factKey
                + "」不在这一轮读到的待确认清单里，也不在上一轮记下的待确认项里");
            return null;
        }
        UUID jobPostingId;
        try {
            jobPostingId = UUID.fromString(askingJob);
        } catch (RuntimeException invalid) {
            violations.add(UNBOUND_CONFIRMATION + "说不出是哪个岗位在等「" + factKey + "」这一项");
            return null;
        }
        String profileVersion = session.profileVersion();
        if (profileVersion == null || profileVersion.isBlank()) {
            violations.add(UNBOUND_CONFIRMATION
                + "没有可绑定的资料版本，答案会被记到一份说不清是哪一版的资料上");
            return null;
        }
        return new AgentTooling.FactBinding(factKey, jobPostingId, profileVersion);
    }

    /**
     * 点名的每一条依据都要在范围内，且确实是成功的观察。
     *
     * <p>唯一的例外是"这次读不到，要不要稍后再试？"：它要的依据<b>就是那次失败</b>，
     * 拿成功的读取反而撑不起它。所以这一档由模板自己声明（{@code acceptsFailures}），
     * 不是在这里对工具名或文案做特判——放开一个口子不等于放开所有，
     * 别的模板照旧不许拿失败的结果当依据。
     */
    private static List<String> checkBasis(AgentTooling.Basis basis, List<Observation> observations,
                                           boolean failuresAreEvidence) {
        var violations = new ArrayList<String>();
        for (int index : basis.observationIndexes()) {
            if (index < 1 || index > observations.size()) {
                violations.add(BASIS_NOT_SUPPORTED + "没有第 " + index + " 条工具结果");
                continue;
            }
            var observation = observations.get(index - 1);
            if (!observation.ok() && !failuresAreEvidence) {
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

    /** 用户可见的收尾模板名。与发给模型的提示同源，调用方不必另抄一份。 */
    public List<String> closingTemplates() {
        return java.util.Arrays.stream(AnswerTemplates.Closing.values()).map(Enum::name).toList();
    }

    /** 用户可见的追问模板名。 */
    public List<String> questionTemplates() {
        return java.util.Arrays.stream(AnswerTemplates.Question.values()).map(Enum::name).toList();
    }

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
                           int budgetLimit, List<String> violations, List<Integer> basis,
                           AgentTooling.FactBinding confirming) {
        public AgentRun {
            observations = List.copyOf(observations == null ? List.of() : observations);
            trace = List.copyOf(trace == null ? List.of() : trace);
            violations = List.copyOf(violations == null ? List.of() : violations);
            basis = List.copyOf(basis == null ? List.of() : basis);
        }
        AgentRun(Outcome outcome, String narrative, String question, List<Observation> observations,
                 List<TraceEntry> trace, int budgetSpent, int budgetLimit, List<String> violations,
                 List<Integer> basis) {
            this(outcome, narrative, question, observations, trace, budgetSpent, budgetLimit, violations,
                basis, null);
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
     * @param observationIndex 这一步对应第几条观察，从 1 开始；规划器层面的条目为 0。
     *        <b>不要靠"数到第几个被接受的"去对位</b>：被拒的调用同样会留下一条失败观察，
     *        那样数会错位——第一步请求了不存在的工具、第二步查询成功，对位之后读到的
     *        是第一步那条失败观察，于是这一轮被判成"没查出新列表"，范围和顺序都不会存回去。
     */
    public record TraceEntry(String tool, Map<String, String> arguments, String why, boolean accepted,
                             String reason, int budgetUnits, int observationIndex) {
        static TraceEntry called(ToolCall call, String why, boolean ok, int budgetUnits, int observationIndex) {
            return new TraceEntry(call.tool(), call.arguments(), why, true, ok ? "ok" : "工具返回失败",
                budgetUnits, observationIndex);
        }
        static TraceEntry rejected(String tool, String reason, int observationIndex) {
            return new TraceEntry(tool, Map.of(), null, false, reason, 0, observationIndex);
        }
        /** 这一步对应第几条观察，从 1 开始；规划器层面的条目没有观察，为 0。 */
        public boolean hasObservation() { return observationIndex > 0; }
    }
}
