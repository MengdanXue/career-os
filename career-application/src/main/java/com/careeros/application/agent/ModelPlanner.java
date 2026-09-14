package com.careeros.application.agent;

import com.careeros.application.agent.AgentTooling.AgentPlanner;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.PlanningState;
import com.careeros.application.agent.AgentTooling.ToolCall;
import java.util.ArrayList;
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

    /**
     * 格式说明。刻意用行格式而不是 JSON：少一层解析失败的来源。
     *
     * <p>这只是格式部分。<b>真正发给模型的提示由 {@link #protocol(PlanningState)} 拼出来，
     * 后面接的是这次运行的完整工具目录与每个参数的定义。</b> 只发格式不发目录，模型只能猜工具名，
     * 于是每一步都被执行器拒绝——看起来像"模型不会用工具"，实际是从没告诉过它有哪些工具。
     */
    public static final String PROTOCOL = """
        你是 Career OS 的检索规划器。你只能查资料和提问，不能修改任何东西。

        每次只回一步，格式严格如下，不要加别的内容：

          TOOL <工具名> <参数键>=<值> ...
          WHY <为什么这一步>

        或者

          ASK <要问用户的问题>
          BASIS <依据的结果序号，用逗号分隔；纯澄清写 none>

        或者

          FINISH <一两句连接性叙述>
          BASIS <依据的结果序号，用逗号分隔>

        一次只给一个动作。同一段里同时出现 TOOL 和 FINISH（或给了两个 TOOL）会被整段作废，
        因为无法判断你要做哪一个。叙述可以写多行，会完整保留。

        只能调下面列出的工具，参数也只能用下面列出的键；工具名或参数名不在表里会被直接拒绝，
        取值不在允许范围里也会被拒绝，不会退化成"不加这个筛选"。
        ASK 必须是一个问句。
        FINISH 必须用 BASIS 点名它依据的是上面第几条结果，而且那几条必须是成功的结果；
        点不出来就说明这段收尾没有依据，会被整段丢弃。
        ASK 依据某条结果时同样要点名；只是问清楚用户想要什么、不依据任何结果时写 BASIS none。

        FINISH 和 ASK 的文字里都不准出现任何数字（含中文数字），不准出现"可报／不可报／条件可报／
        待确认／T1／T2／T3"这类判定词，不准把指数说成录取概率或上岸率。
        资格、分数、限制条件由程序渲染并附在你这段话后面，你改不了也删不掉；
        写进去只会导致你这段话被整段丢弃。""";

    private static final Pattern TOOL_LINE = Pattern.compile("^\\s*TOOL\\s+(\\S+)\\s*(.*)$");
    private static final Pattern ASK_LINE = Pattern.compile("^\\s*ASK\\s+(.+)$");
    private static final Pattern FINISH_LINE = Pattern.compile("^\\s*FINISH\\s+(.+)$");
    private static final Pattern WHY_LINE = Pattern.compile("^\\s*WHY\\s+(.+)$");
    private static final Pattern BASIS_LINE = Pattern.compile("^\\s*BASIS\\s+(.+)$");
    private static final Pattern ARGUMENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=(\"[^\"]*\"|\\S+)");

    /** BASIS 里表示"纯澄清，不依据任何结果"的写法。 */
    private static final List<String> CLARIFYING_MARKERS = List.of("none", "NONE", "无", "澄清");

    private final ModelTurn model;

    public ModelPlanner(ModelTurn model) { this.model = Objects.requireNonNull(model, "model"); }

    @Override
    public PlannerStep next(PlanningState state) {
        String raw = model.respond(protocol(state), render(state));
        return parse(raw);
    }

    /**
     * 拼出这一轮真正发给模型的提示：格式说明 + 这次运行的工具目录。
     *
     * <p>目录来自执行器的注册表，不是另写一份文档——提示里写着能填 T1、代码却不认，
     * 这种偏差在同一份定义下不会出现。
     */
    static String protocol(PlanningState state) {
        var text = new StringBuilder(PROTOCOL);
        text.append("\n\n可用工具（只读，全部只能查，不能改）：\n");
        if (state.tools().isEmpty()) {
            // 说清楚"一个都没有"，不是省略不提：省略会让模型以为目录只是没写出来，继续猜工具名。
            text.append("（本次运行没有注册任何工具。不要提出 TOOL 调用。）\n");
            return text.toString();
        }
        for (AgentTooling.ToolSpec tool : state.tools()) {
            text.append("\n- ").append(tool.name()).append("：").append(tool.description()).append('\n');
            if (tool.parameters().isEmpty()) {
                text.append("  参数：无\n");
                continue;
            }
            text.append("  参数：\n");
            for (AgentTooling.ToolParameter parameter : tool.parameters()) {
                text.append("    - ").append(parameter.name())
                    .append(parameter.required() ? "（必填）" : "（可选）")
                    .append("：").append(parameter.description());
                if (!parameter.allowedValues().isEmpty()) {
                    text.append("；只能取 ").append(String.join("、", parameter.allowedValues()));
                }
                text.append('\n');
            }
        }
        return text.toString();
    }

    /**
     * 把模型的一段输出整段解析成一步计划。
     *
     * <p><b>整段看，不是看到第一行认识的就返回。</b> 逐行扫描有三处会静默出错：
     * 多行的 FINISH 叙述被截成第一行（限制条件常常正好在第二行）；
     * 同时出现 TOOL 和 FINISH 时按出现顺序选一个，等于替模型做了决定；
     * 重复的 TOOL 只执行第一个，其余无声消失。这些都看不出异常。
     *
     * <p>所以：收集整段里出现的全部动作；出现<b>不止一个动作</b>就整段作废，
     * 交给执行器按"没给出下一步"处理。宁可这一步没有计划，也不替它猜。
     * FINISH / ASK 的文字一直取到下一个动作行或结尾，多行叙述完整保留。
     *
     * @return 认不出来、或同时给了多个动作时返回 {@code null}——执行器会结束运行，
     *         而不是凑一个工具调用出来。
     */
    public static PlannerStep parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] lines = raw.split("\\R");

        var actions = new ArrayList<Integer>();
        for (int index = 0; index < lines.length; index++) {
            if (isAction(lines[index])) actions.add(index);
        }
        // 一个动作都没有，或给了不止一个动作：两种都不是"这一步要做什么"的答案。
        if (actions.size() != 1) return null;

        int at = actions.get(0);
        String line = lines[at];
        // 动作行之后、下一个动作行之前的所有内容都属于这一个动作。
        String trailing = String.join("\n", java.util.Arrays.copyOfRange(lines, at + 1, lines.length));

        Matcher finish = FINISH_LINE.matcher(line);
        if (finish.matches()) {
            return new PlannerStep.Finish(continued(finish.group(1), trailing), basis(trailing));
        }
        Matcher ask = ASK_LINE.matcher(line);
        if (ask.matches()) {
            return new PlannerStep.AskUser(continued(ask.group(1), trailing), basis(trailing));
        }
        Matcher tool = TOOL_LINE.matcher(line);
        if (tool.matches()) {
            String name = tool.group(1).strip();
            if (name.isEmpty()) return null;
            return new PlannerStep.CallTool(new ToolCall(name, arguments(tool.group(2))), why(trailing));
        }
        return null;
    }

    private static boolean isAction(String line) {
        return TOOL_LINE.matcher(line).matches()
            || ASK_LINE.matcher(line).matches()
            || FINISH_LINE.matcher(line).matches();
    }

    /**
     * 把动作行之后的续行接回叙述。
     *
     * <p>WHY / BASIS 是协议自己的字段，不属于叙述；其余非空行都是模型接着说的话。
     * 只取第一行的话，"限中共党员"这种常常单独成行的限制会被无声删掉，
     * 剩下的读起来比原文更肯定。
     */
    private static String continued(String head, String trailing) {
        var text = new StringBuilder(head.strip());
        for (String line : trailing.split("\\R")) {
            if (line.isBlank() || WHY_LINE.matcher(line).matches() || BASIS_LINE.matcher(line).matches()) continue;
            text.append('\n').append(line.strip());
        }
        return text.toString().strip();
    }

    private static String why(String trailing) {
        for (String line : trailing.split("\\R")) {
            Matcher matcher = WHY_LINE.matcher(line);
            if (matcher.matches()) return matcher.group(1).strip();
        }
        return "(模型未说明原因)";
    }

    /** 解析 BASIS：收尾这段话依据的是第几条观察，或者明说它只是澄清。 */
    private static AgentTooling.Basis basis(String trailing) {
        for (String line : trailing.split("\\R")) {
            Matcher matcher = BASIS_LINE.matcher(line);
            if (!matcher.matches()) continue;
            String value = matcher.group(1).strip();
            if (CLARIFYING_MARKERS.stream().anyMatch(value::contains)) return AgentTooling.Basis.clarifying();
            var indexes = new ArrayList<Integer>();
            Matcher number = Pattern.compile("\\d+").matcher(value);
            while (number.find()) indexes.add(Integer.parseInt(number.group()));
            return new AgentTooling.Basis(indexes, false);
        }
        return AgentTooling.Basis.none();
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

    /**
     * 把当前状态渲染给模型。包含剩余额度，好让它自己收敛而不是撞上限；
     * 也包含上一轮会话，好让它接得上——用户追问之后只答一句"余杭"，
     * 模型要看得见上一轮限定的是杭州，才知道这是在收窄范围而不是另起一问。
     */
    static String render(PlanningState state) {
        var text = new StringBuilder();
        text.append("用户问题：").append(state.question()).append('\n');
        appendSession(text, state.session());
        text.append("剩余预算单位：").append(state.remainingBudget())
            .append("（一次工具调用算一个单位，工具内部每评估一个岗位再算一个）\n");
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

    /** 上一轮留下的范围、列表顺序与待确认项。没有会话就明说没有，不留空白让模型猜。 */
    private static void appendSession(StringBuilder text, AgentTooling.SessionContext session) {
        if (!session.present()) {
            text.append("这是新的一轮，没有上一轮的范围或列表。\n");
            return;
        }
        text.append("上一轮的范围：")
            .append(describeFilters(session))
            .append('\n');
        if (session.jobsInOrder().isEmpty()) {
            text.append("上一轮没有列出岗位。\n");
        } else {
            text.append("上一轮列表（用户说\"第几个\"指的就是这个顺序，不要重新排名）：\n");
            for (AgentTooling.JobRef job : session.jobsInOrder()) {
                text.append("  ").append(job.ordinal()).append(". jobId=").append(job.jobPostingId());
                // 标题只有在会话里真的存了的时候才写。编一个占位标题会让模型以为它知道这是什么岗位。
                if (job.jobTitle() != null && !job.jobTitle().isBlank()) {
                    text.append("  ").append(job.jobTitle());
                    if (job.organizationName() != null && !job.organizationName().isBlank()) {
                        text.append("（").append(job.organizationName()).append("）");
                    }
                }
                text.append('\n');
            }
            text.append("  要它们的标题和结论，用 job_facts（可以直接给 ordinal）。\n");
        }
        if (!session.pending().isEmpty()) {
            text.append("上一轮问过、用户还没答的资料项：\n");
            for (AgentTooling.PendingRef item : session.pending()) {
                text.append("  - ").append(item.factKey()).append("：").append(item.question()).append('\n');
            }
        }
    }

    private static String describeFilters(AgentTooling.SessionContext session) {
        var parts = new ArrayList<String>();
        if (session.location() != null && !session.location().isBlank()) parts.add("地点=" + session.location());
        if (session.jobFamily() != null && !session.jobFamily().isBlank()) parts.add("职位类别=" + session.jobFamily());
        if (session.tier() != null && !session.tier().isBlank()) parts.add("机会分层=" + session.tier());
        return parts.isEmpty() ? "（没有加过筛选）" : String.join("，", parts);
    }

    /** 一次模型往返。做成接口是为了能用录制的输出做回归，不必每次都真的调模型。 */
    @FunctionalInterface
    public interface ModelTurn {
        String respond(String protocol, String state);
    }
}