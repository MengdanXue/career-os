package com.careeros.application.agent;

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

    /** 一个只读工具。实现应当直接转调主干既有服务，不在这一层重新实现判定。 */
    public interface ReadOnlyTool {
        String name();
        String description();
        Observation invoke(UUID candidateId, ToolCall call);
    }

    /** 规划器的下一步。封闭类型：它只能选这三样，写入不在其中。 */
    public sealed interface PlannerStep {
        /** 调一个工具。{@code why} 会记进轨迹，便于事后看它为什么这么选。 */
        record CallTool(ToolCall call, String why) implements PlannerStep {}
        /** 结束并给出连接性叙述；叙述仍要过 AnswerNarrativeValidator。 */
        record Finish(String narrative) implements PlannerStep {}
        /** 向用户追问。追问不是写入，不需要授权。 */
        record AskUser(String question) implements PlannerStep {}
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
     * @param remainingCalls 还剩多少次工具调用额度。让规划器看得见，它才可能自己收敛
     */
    public record PlanningState(String question, List<Observation> observations, int remainingCalls) {
        public PlanningState {
            Objects.requireNonNull(question, "question");
            observations = List.copyOf(observations == null ? List.of() : observations);
        }
        public Observation last() {
            return observations.isEmpty() ? null : observations.get(observations.size() - 1);
        }
    }
}
