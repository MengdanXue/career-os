package com.careeros.application.agent;

import com.careeros.application.ToolCallBudget;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 单 Agent v1 的工具面：只读。
 *
 * <p>职责划分是这一版的全部要点：
 * <ul>
 *   <li><b>模型</b>决定查什么、问什么——它只能产出 {@link PlannerStep}。</li>
 *   <li><b>规则</b>给资格结论——工具返回的是既有确定性服务算出来的东西，模型不参与判定。</li>
 *   <li><b>执行器</b>管权限与共享预算——不在注册表里的、会写入的、超预算的，一律拒绝。</li>
 *   <li><b>用户</b>授权业务修改——写入只走用户明确操作的确定性接口，不在这套工具里。</li>
 * </ul>
 *
 * <p>候选人身份由执行器绑定，规划器给不了：否则模型只要报出另一个候选人 ID 就能读到别人的资料。
 */
public final class AgentTooling {
    private AgentTooling() {}

    /**
     * 一次工具调用。
     *
     * <p>刻意不含候选人 ID——那是执行器的事。规划器能决定"查什么"，不能决定"查谁的"。
     */
    public record ToolCall(String tool, Map<String, String> arguments) {
        public ToolCall {
            if (tool == null || tool.isBlank()) throw new IllegalArgumentException("tool is required");
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
        public static ToolCall of(String tool, String... keyValues) {
            if (keyValues.length % 2 != 0) throw new IllegalArgumentException("expected key/value pairs");
            var map = new LinkedHashMap<String, String>();
            for (int index = 0; index < keyValues.length; index += 2) map.put(keyValues[index], keyValues[index + 1]);
            return new ToolCall(tool, map);
        }
        public String argument(String key) { return arguments.get(key); }
    }

    /**
     * 一个参数的定义。
     *
     * <p>这不是文档，是契约：模型首轮就收到它（{@link ModelPlanner} 把整份目录渲染进提示），
     * 工具也用同一份定义校验传进来的参数。两边共用一份，"提示里写着能填 T1，代码却不认"
     * 这类偏差就不会出现。
     *
     * @param allowedValues 枚举型参数的完整取值集合；空集表示自由取值
     */
    public record ToolParameter(String name, boolean required, String description, List<String> allowedValues) {
        public ToolParameter {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            allowedValues = List.copyOf(allowedValues == null ? List.of() : allowedValues);
        }
        public static ToolParameter optional(String name, String description) {
            return new ToolParameter(name, false, description, List.of());
        }
        public static ToolParameter required(String name, String description) {
            return new ToolParameter(name, true, description, List.of());
        }
        public static ToolParameter oneOf(String name, String description, List<String> allowedValues) {
            return new ToolParameter(name, false, description, allowedValues);
        }
    }

    /** 工具目录里的一条。渲染给模型的就是它。 */
    public record ToolSpec(String name, String description, List<ToolParameter> parameters) {
        public ToolSpec {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            parameters = List.copyOf(parameters == null ? List.of() : parameters);
        }
        static ToolSpec of(ReadOnlyTool tool) {
            return new ToolSpec(tool.name(), tool.description(), tool.parameters());
        }
    }

    /**
     * 工具返回的观察。
     *
     * @param summary 给规划器看的简短描述；不含判定词与分数，那些在结构化数据里
     * @param data    结构化结果。规划器据此决定下一步——分支必须由它驱动，而不是写死的顺序
     */
    public record Observation(String tool, boolean ok, String summary, Map<String, Object> data) {
        public Observation {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(summary, "summary");
            data = data == null ? Map.of() : Map.copyOf(data);
        }
        public static Observation ok(String tool, String summary, Map<String, Object> data) {
            return new Observation(tool, true, summary, data);
        }
        public static Observation failed(String tool, String summary) {
            return new Observation(tool, false, summary, Map.of());
        }
        @SuppressWarnings("unchecked")
        public <T> T value(String key, T fallback) {
            Object found = data.get(key);
            return found == null ? fallback : (T) found;
        }
    }

    /**
     * 一次工具调用的上下文。
     *
     * <p>候选人由执行器填进来，工具不从参数里取；<b>共享预算也由执行器填进来</b>，
     * 工具必须拿它去计内部的逐岗评估。一次 {@code search_jobs} 背后可能是几十次评估，
     * 只按"调了一次工具"记一次，预算就形同虚设——模型调三次工具就能触发上百次评估。
     */
    public record ToolContext(UUID candidateId, ToolCall call, ToolCallBudget budget, SessionContext session) {
        public ToolContext {
            Objects.requireNonNull(candidateId, "candidateId");
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(budget, "budget");
            session = session == null ? SessionContext.none() : session;
        }
        public ToolContext(UUID candidateId, ToolCall call, ToolCallBudget budget) {
            this(candidateId, call, budget, SessionContext.none());
        }
        public String argument(String key) { return call.argument(key); }
    }

    /**
     * 上一轮会话留下的东西。
     *
     * <p>没有它，"动态执行"每一轮都是从零开始：用户在追问之后只答一句"余杭"，
     * 模型看不到上一轮限定的是杭州、按什么排的序、问过哪些待确认，只能把"余杭"当成一个孤立的问题。
     * 用户说"第二个"更是无从指认——重新排一次名次得到的第二个可能是另一个岗位。
     *
     * @param jobsInOrder 上一轮列表的顺序。序号只在这里解析，永不重新排名
     * @param pending     上一轮问过、还没答的资料项
     */
    public record SessionContext(UUID sessionId, String location, String tier, String jobFamily,
                                 List<JobRef> jobsInOrder, List<PendingRef> pending, String profileVersion) {
        public SessionContext {
            jobsInOrder = List.copyOf(jobsInOrder == null ? List.of() : jobsInOrder);
            pending = List.copyOf(pending == null ? List.of() : pending);
        }
        public static SessionContext none() {
            return new SessionContext(null, null, null, null, List.of(), List.of(), null);
        }
        public boolean present() { return sessionId != null; }
        /** 把"第 N 个"解析回岗位。越界返回空——不猜，也不重新排名。 */
        public java.util.Optional<JobRef> atOrdinal(int ordinal) {
            if (ordinal < 1 || ordinal > jobsInOrder.size()) return java.util.Optional.empty();
            return java.util.Optional.of(jobsInOrder.get(ordinal - 1));
        }
    }

    /** 上一轮列表里的一个岗位。 */
    public record JobRef(int ordinal, UUID jobPostingId, String jobTitle, String organizationName,
                         String eligibilityStatus, String tier) {}

    /** 上一轮还没答的一个待确认项，以及是哪个岗位提出的。 */
    public record PendingRef(String factKey, String question, UUID jobPostingId) {}

    /** 一个只读工具。实现应当直接转调主干既有服务，不在这一层重新实现判定。 */
    public interface ReadOnlyTool {
        String name();
        String description();
        /** 这个工具认得的参数。模型首轮就看到它，工具也按它校验——同一份定义。 */
        default List<ToolParameter> parameters() { return List.of(); }
        Observation invoke(ToolContext context);
    }

    /**
     * 收尾文字所依据的观察。
     *
     * <p>光有"这次运行成功读到过东西"不算依据——那只说明附近有数据，不说明这段话用了它。
     * 所以收尾要点名：依据第几条观察。执行器再核对那几条确实存在、确实成功。
     *
     * @param observationIndexes 渲染给模型的观察序号，从 1 开始
     * @param clarifyingOnly     纯澄清：不依据任何结果。只有追问可以是这种
     */
    public record Basis(List<Integer> observationIndexes, boolean clarifyingOnly) {
        public Basis {
            observationIndexes = List.copyOf(observationIndexes == null ? List.of() : observationIndexes);
        }
        public static Basis on(int... indexes) {
            var list = new java.util.ArrayList<Integer>();
            for (int index : indexes) list.add(index);
            return new Basis(list, false);
        }
        public static Basis clarifying() { return new Basis(List.of(), true); }
        /** 既没点名依据，也没声明是纯澄清。收尾不接受这种。 */
        public static Basis none() { return new Basis(List.of(), false); }
        public boolean declared() { return clarifyingOnly || !observationIndexes.isEmpty(); }
    }

    /** 规划器的下一步。封闭类型：它只能选这三样，写入不在其中。 */
    public sealed interface PlannerStep {
        /** 调一个工具。{@code why} 会记进轨迹，便于事后看它为什么这么选。 */
        record CallTool(ToolCall call, String why) implements PlannerStep {}
        /** 结束并给出连接性叙述；叙述要过 AnswerNarrativeValidator，依据要点到具体观察。 */
        record Finish(String narrative, Basis basis) implements PlannerStep {
            public Finish(String narrative) { this(narrative, Basis.none()); }
        }
        /** 向用户追问。追问不是写入，不需要授权，但同样不许夹带无依据的结论。 */
        record AskUser(String question, Basis basis) implements PlannerStep {
            public AskUser(String question) { this(question, Basis.none()); }
        }
    }

    /**
     * 规划器。
     *
     * <p>做成接口是为了先用可控实现把执行边界测干净，再换成模型——
     * 边界要在模型进来之前就成立，而不是靠提示词请求模型守规矩。
     */
    @FunctionalInterface
    public interface AgentPlanner {
        PlannerStep next(PlanningState state);
    }

    /**
     * 规划器能看到的东西。
     *
     * @param observations 到目前为止的工具结果，按调用顺序
     * @param remainingBudget 还剩多少预算单位。让规划器看得见，它才可能自己收敛
     * @param session 上一轮会话留下的范围、列表顺序与待确认项。续跑靠它，不靠模型记忆
     * @param tools 这次运行可用的工具目录。<b>第一轮就必须是完整的</b>：
     *              模型不知道有哪些工具、每个工具收什么参数时，它只能猜工具名，
     *              于是每一步都被执行器拒绝，看起来像"模型不会用工具"，实际是从没告诉过它
     */
    public record PlanningState(String question, List<Observation> observations, int remainingBudget,
                                List<ToolSpec> tools, SessionContext session) {
        public PlanningState {
            Objects.requireNonNull(question, "question");
            observations = List.copyOf(observations == null ? List.of() : observations);
            tools = List.copyOf(tools == null ? List.of() : tools);
            session = session == null ? SessionContext.none() : session;
        }
        public PlanningState(String question, List<Observation> observations, int remainingBudget) {
            this(question, observations, remainingBudget, List.of(), SessionContext.none());
        }
        public PlanningState(String question, List<Observation> observations, int remainingBudget,
                             List<ToolSpec> tools) {
            this(question, observations, remainingBudget, tools, SessionContext.none());
        }
        public Observation last() {
            return observations.isEmpty() ? null : observations.get(observations.size() - 1);
        }
    }
}
