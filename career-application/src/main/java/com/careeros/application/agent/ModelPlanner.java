package com.careeros.application.agent;

import com.careeros.application.agent.AgentTooling.AgentPlanner;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.PlanningState;
import com.careeros.application.agent.AgentTooling.ToolCall;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型的一段输出解析成一个 {@link PlannerStep}。
 *
 * <p>这一层只做解析，不做授权。能不能调、调几次、查谁的，全部由 {@link AgentExecutor} 决定——
 * 模型请求一个写入工具时，是执行器拒绝它，而不是这里先过滤掉。两件事分开，
 * 才不会出现"解析器漏了一种写法，边界就破了"。
 *
 * <p><b>解析不出来不猜。</b> 模型输出格式不对、工具名为空、动作认不出来，一律当作
 * "这一步无效"，交给执行器按失败处理。把半句话硬凑成一次工具调用，比直接说没听懂危险得多：
 * 凑出来的调用会照常执行，而且看起来完全正常。
 */
public final class ModelPlanner implements AgentPlanner {

    /** 模型每一步要按这个格式回。刻意用行格式而不是 JSON：少一层解析失败的来源。 */
    public static final String PROTOCOL = """
        你是 Career OS 的检索规划器。你只能查资料和提问，不能修改任何东西。

        每次只回一步，格式严格如下，不要加别的内容：

          TOOL <工具名> <参数键>=<值> ...
          WHY <为什么这一步>

        或者

          ASK <要问用户的问题>

        或者

          FINISH <一两句连接性叙述>

        FINISH 的叙述里不准出现任何数字（含中文数字），不准出现"可报／不可报／条件可报／
        待确认／T1／T2／T3"这类判定词，不准把指数说成录取概率或上岸率。
        资格、分数、限制条件由程序渲染并附在你这段话后面，你改不了也删不掉；
        写进去只会导致你这段话被整段丢弃。""";

    private static final Pattern TOOL_LINE = Pattern.compile("^\\s*TOOL\\s+(\\S+)\\s*(.*)$");
    private static final Pattern ASK_LINE = Pattern.compile("^\\s*ASK\\s+(.+)$");
    private static final Pattern FINISH_LINE = Pattern.compile("^\\s*FINISH\\s+(.+)$");
    private static final Pattern WHY_LINE = Pattern.compile("^\\s*WHY\\s+(.+)$");
    private static final Pattern ARGUMENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=(\"[^\"]*\"|\\S+)");

    private final ModelTurn model;

    public ModelPlanner(ModelTurn model) { this.model = Objects.requireNonNull(model, "model"); }

    @Override
    public PlannerStep next(PlanningState state) {
        String raw = model.respond(PROTOCOL, render(state));
        return parse(raw);
    }

    /**
     * 解析一段模型输出。
     *
     * @return 认不出来时返回 {@code null}——执行器会把它当作"没给出下一步"并结束运行，
     *         而不是凑一个工具调用出来。
     */
    public static PlannerStep parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String why = null;
        for (String line : raw.split("\\R")) {
            Matcher whyMatcher = WHY_LINE.matcher(line);
            if (whyMatcher.matches()) why = whyMatcher.group(1).strip();
        }
        for (String line : raw.split("\\R")) {
            Matcher finish = FINISH_LINE.matcher(line);
            if (finish.matches()) return new PlannerStep.Finish(finish.group(1).strip());
            Matcher ask = ASK_LINE.matcher(line);
            if (ask.matches()) return new PlannerStep.AskUser(ask.group(1).strip());
            Matcher tool = TOOL_LINE.matcher(line);
            if (tool.matches()) {
                String name = tool.group(1).strip();
                if (name.isEmpty()) return null;
                return new PlannerStep.CallTool(new ToolCall(name, arguments(tool.group(2))),
                    why == null ? "(模型未说明原因)" : why);
            }
        }
        return null;
    }

    private static Map<String, String> arguments(String rest) {
        var arguments = new LinkedHashMap<String, String>();
        if (rest == null) return arguments;
        Matcher matcher = ARGUMENT.matcher(rest);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            arguments.put(matcher.group(1), value);
        }
        return arguments;
    }

    /** 把当前状态渲染给模型。包含剩余额度，好让它自己收敛而不是撞上限。 */
    static String render(PlanningState state) {
        var text = new StringBuilder();
        text.append("用户问题：").append(state.question()).append('\n');
        text.append("剩余工具调用次数：").append(state.remainingCalls()).append('\n');
        if (state.observations().isEmpty()) {
            text.append("目前还没有任何工具结果。");
            return text.toString();
        }
        text.append("已有的工具结果，按顺序：\n");
        List<AgentTooling.Observation> observations = state.observations();
        for (int index = 0; index < observations.size(); index++) {
            var observation = observations.get(index);
            text.append(index + 1).append(". ").append(observation.tool())
                .append(observation.ok() ? " 成功：" : " 失败：").append(observation.summary()).append('\n');
            if (!observation.data().isEmpty()) text.append("   数据：").append(observation.data()).append('\n');
        }
        return text.toString();
    }

    /** 一次模型往返。做成接口是为了能用录制的输出做回归，不必每次都真的调模型。 */
    @FunctionalInterface
    public interface ModelTurn {
        String respond(String protocol, String state);
    }
}
