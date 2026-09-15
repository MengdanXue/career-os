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

          ASK <模板名> [槽位=值]
          BASIS <依据的结果序号，用逗号分隔；纯澄清写 none>

        或者

          FINISH <模板名>
          BASIS <依据的结果序号，用逗号分隔>

        一次只给一个动作。同一段里同时出现 TOOL 和 FINISH（或给了两个 TOOL）会被整段作废，
        因为无法判断你要做哪一个。**每一行都必须是上面这几种之一**：多写一句解释、
        多加一段说明，整段都会作废——不是被忽略，是整个这一步都不算数。

        只能调下面列出的工具，参数也只能用下面列出的键；工具名或参数名不在表里会被直接拒绝，
        取值不在允许范围里也会被拒绝，不会退化成"不加这个筛选"。

        FINISH 和 ASK 不写自由句子，只写模板名。用户看到的那句话由程序按模板渲染，
        你改不了它的措辞——资格、材料、把握这类判断只能来自程序算出来的事实块。
        FINISH 必须用 BASIS 点名它依据的是上面第几条结果，而且那几条必须是成功的结果。
        ASK 只是问清楚用户想要什么时写 BASIS none；依据某条结果时同样要点名。
        BASIS 只接受 none 或正整数序号，负数、小数都读不出来，整段会作废。

        资格、分数、限制条件由程序渲染并附在这句话后面，你改不了也删不掉。""";

    // --- 完整的行语法。每一行都要落在某条产生式上，落不上就整段作废。 ---

    private static final Pattern TOOL_LINE = Pattern.compile("^\\s*TOOL\\s+(\\S+)\\s*(.*)$");
    private static final Pattern ASK_LINE = Pattern.compile("^\\s*ASK\\s+(\\S+)\\s*(.*)$");
    private static final Pattern FINISH_LINE = Pattern.compile("^\\s*FINISH\\s+(\\S+)\\s*(.*)$");
    private static final Pattern WHY_LINE = Pattern.compile("^\\s*WHY\\s+(.+)$");
    private static final Pattern BASIS_LINE = Pattern.compile("^\\s*BASIS\\s+(.+)$");

    /** 一个参数记号：{@code 键=值}，值可以用引号包住以容纳空格。参数段必须整段由它组成。 */
    private static final Pattern ARGUMENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=(\"[^\"]*\"|\\S+)");

    /**
     * BASIS 的取值语法：要么是"没有依据"这个词本身，要么是一串正整数。
     *
     * <p>写成语法而不是"串里出现过某个字"。此前靠 {@code contains} 判断，
     * {@code BASIS 1，无其他依据} 就被读成"不依据任何结果"；取数字靠"把数字挑出来"，
     * {@code BASIS -1} 于是变成了第 1 条。负号和小数点根本没进过语法——
     * 按语法解析就不会有这种事：读不出来就是读不出来，不会读成别的东西。
     */
    private static final Pattern BASIS_CLARIFYING = Pattern.compile("^(?:none|NONE|无|澄清)$");
    private static final Pattern BASIS_INDEXES = Pattern.compile("^\\d+(?:[\\s,，、]+\\d+)*$");

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
        } else {
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
        }
        text.append("\nFINISH 只能用下面这些模板名：\n");
        for (AnswerTemplates.Closing template : AnswerTemplates.Closing.values()) {
            text.append("  - ").append(template.name()).append('\n');
        }
        text.append("ASK 只能用下面这些模板名：\n");
        for (AnswerTemplates.Question template : AnswerTemplates.Question.values()) {
            text.append("  - ").append(template.name());
            if (!template.slots().isEmpty()) {
                text.append("（需要 ").append(String.join("、", template.slots())).append("=…）");
            }
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * 把模型的一段输出整段解析成一步计划。
     *
     * <p><b>整段看，每一行都要落在语法上。</b> 此前是"扫到认得的行就用，其余忽略"，
     * 三处会静默出错：非协议行被忽略——模型在 TOOL 之外还写了一句"这次只看事业编"，
     * 系统当没看见照样执行，用户拿到的结果比他要求的宽；同时出现 TOOL 和 FINISH 时按位置挑一个，
     * 等于替模型决定它要做哪件事；重复的 TOOL 只执行第一个。
     *
     * <p>现在的规矩只有一条：<b>读不懂就整段作废</b>，交给执行器按"没给出下一步"处理。
     * 宁可这一步没有计划，也不按自己认得的那部分去理解它。
     *
     * @return 认不出来、给了多个动作、或有任何一行落不到语法上时返回 {@code null}
     */
    public static PlannerStep parse(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String action = null;
        String why = null;
        String basisValue = null;
        for (String line : raw.split("\\R")) {
            if (line.isBlank()) continue;
            if (TOOL_LINE.matcher(line).matches()
                || ASK_LINE.matcher(line).matches()
                || FINISH_LINE.matcher(line).matches()) {
                // 两个动作，两种读法都说得通，所以哪种都不能选。
                if (action != null) return null;
                action = line;
                continue;
            }
            Matcher whyLine = WHY_LINE.matcher(line);
            if (whyLine.matches()) {
                if (why != null) return null;
                why = whyLine.group(1).strip();
                continue;
            }
            Matcher basisLine = BASIS_LINE.matcher(line);
            if (basisLine.matches()) {
                if (basisValue != null) return null;
                basisValue = basisLine.group(1).strip();
                continue;
            }
            // 落不到任何一条产生式上。忽略它等于只按自己认得的那部分理解模型这一步。
            return null;
        }
        if (action == null) return null;

        var basis = basis(basisValue);
        if (basis == null) return null;

        Matcher finish = FINISH_LINE.matcher(action);
        if (finish.matches()) {
            var slots = arguments(finish.group(2));
            return slots == null ? null : new PlannerStep.Finish(finish.group(1).strip(), slots, basis);
        }
        Matcher ask = ASK_LINE.matcher(action);
        if (ask.matches()) {
            var slots = arguments(ask.group(2));
            return slots == null ? null : new PlannerStep.AskUser(ask.group(1).strip(), slots, basis);
        }
        Matcher tool = TOOL_LINE.matcher(action);
        String name = tool.matches() ? tool.group(1).strip() : "";
        if (name.isEmpty()) return null;
        var arguments = arguments(tool.group(2));
        if (arguments == null) return null;
        return new PlannerStep.CallTool(new ToolCall(name, arguments),
            why == null ? "(模型未说明原因)" : why);
    }

    /**
     * 解析 TOOL / ASK / FINISH 行上的参数段。
     *
     * <p><b>没被解释掉的部分不能静默消失。</b> 此前这里是"把认得的记号挑出来，其余忽略"：
     * {@code TOOL search_jobs 杭州} 于是变成一次没有任何筛选的全量查询，
     * 然后把全国的岗位当成杭州的答复给用户。比取值不认识更隐蔽——那种至少还留下一个失败观察。
     *
     * <p>同一个键给两个值也不再取最后一个：模型自相矛盾的一步被悄悄解释成其中一种，
     * 用户拿到的答复对应的是他从没要求过的范围。
     *
     * @return 整段都是合法记号且键不重复时返回参数表；否则返回 {@code null}，整段作废
     */
    private static Map<String, String> arguments(String rest) {
        var arguments = new LinkedHashMap<String, String>();
        if (rest == null || rest.isBlank()) return arguments;
        Matcher matcher = ARGUMENT.matcher(rest);
        int consumedTo = 0;
        while (matcher.find()) {
            // 记号之间只允许空白。别的东西说明这一段有没被解释的内容。
            if (!rest.substring(consumedTo, matcher.start()).isBlank()) return null;
            consumedTo = matcher.end();
            String value = matcher.group(2);
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if (arguments.put(matcher.group(1), value) != null) return null;
        }
        return rest.substring(consumedTo).isBlank() ? arguments : null;
    }

    /**
     * 解析 BASIS 的取值。
     *
     * @return 解析结果；{@code null} 表示这串落不到语法上，整段作废
     */
    private static AgentTooling.Basis basis(String value) {
        if (value == null) return AgentTooling.Basis.none();
        if (BASIS_CLARIFYING.matcher(value).matches()) return AgentTooling.Basis.clarifying();
        if (!BASIS_INDEXES.matcher(value).matches()) return null;
        var indexes = new ArrayList<Integer>();
        Matcher number = Pattern.compile("\\d+").matcher(value);
        while (number.find()) indexes.add(Integer.parseInt(number.group()));
        return new AgentTooling.Basis(indexes, false);
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
        if (session.hasOpenTask()) {
            // 用户这一句常常只是个残句（"余杭"）。不把原来那件事摆出来，
            // 模型只能把它当成一个孤立的新问题，用户就得从头再说一遍。
            text.append("上一轮还没办完的事：").append(session.openTask()).append('\n');
            if (session.pendingQuestion() != null && !session.pendingQuestion().isBlank()) {
                text.append("系统当时问他：").append(session.pendingQuestion())
                    .append("（用户这一句多半是在回答它）\n");
            }
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