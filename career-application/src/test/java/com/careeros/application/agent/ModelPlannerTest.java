package com.careeros.application.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.agent.AgentExecutor.Outcome;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.PlanningState;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.agent.AgentTooling.ToolCall;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 模型输出的解析层。
 *
 * <p>这一层只解析，不授权——能不能调、调谁的、调几次由执行器管。所以这里测的是：
 * 认不出来的输出不会被凑成一次工具调用，以及模型的越权请求照样经由执行器被拒。
 *
 * <p>用录制输出做回归。真实模型样例需要 API key，本环境没有，这些用例不冒充那件事。
 */
class ModelPlannerTest {
    private static final UUID CANDIDATE = UUID.randomUUID();

    private static PlanningState state() { return new PlanningState("杭州有哪些岗位", List.of(), 5); }

    // --- 正常解析 ---

    @Test void parsesAToolStepWithArgumentsAndReason() {
        var step = ModelPlanner.parse("""
            TOOL search_jobs location=杭州 jobFamily=DATA limit=3
            WHY 用户问的是杭州的岗位，先查岗位库""");

        assertThat(step).isInstanceOf(PlannerStep.CallTool.class);
        var call = (PlannerStep.CallTool) step;
        assertThat(call.call().tool()).isEqualTo("search_jobs");
        assertThat(call.call().arguments())
            .containsEntry("location", "杭州").containsEntry("jobFamily", "DATA").containsEntry("limit", "3");
        assertThat(call.why()).contains("先查岗位库");
    }

    @Test void parsesQuotedArgumentsWithSpaces() {
        var step = (PlannerStep.CallTool) ModelPlanner.parse("TOOL search_jobs location=\"杭州 市区\"");
        assertThat(step.call().argument("location")).isEqualTo("杭州 市区");
    }

    @Test void parsesAskAndFinish() {
        assertThat(ModelPlanner.parse("ASK 要不要放宽到整个浙江？")).isInstanceOf(PlannerStep.AskUser.class);
        assertThat(ModelPlanner.parse("FINISH 下面按稳定性排序。")).isInstanceOf(PlannerStep.Finish.class);
    }

    // --- 认不出来就不猜 ---

    /**
     * 格式不对时不能硬凑。凑出来的工具调用会照常执行，而且看起来完全正常——
     * 比直接说没听懂危险得多。
     */
    @Test void unrecognisableOutputIsNotCoercedIntoAToolCall() {
        assertThat(ModelPlanner.parse("我觉得应该先看看杭州的岗位吧")).isNull();
        assertThat(ModelPlanner.parse("")).isNull();
        assertThat(ModelPlanner.parse(null)).isNull();
        assertThat(ModelPlanner.parse("{\"tool\":\"search_jobs\"}")).isNull();
        assertThat(ModelPlanner.parse("TOOL")).isNull();
    }

    /** 解析不出下一步时，运行要干净地结束，而不是崩溃或空转。 */
    @Test void anUnparseableTurnEndsTheRunInsteadOfLooping() {
        var executor = new AgentExecutor(List.of(tool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "随便说点什么",
            new ModelPlanner((protocol, rendered) -> "这个问题挺复杂的，我想想。"));

        assertThat(run.outcome()).isEqualTo(Outcome.PLANNER_FAILED);
        assertThat(run.budgetSpent()).isZero();
    }

    // --- 整段解析，不是看到第一行认识的就返回 ---

    /**
     * 多行的收尾叙述要完整保留。
     *
     * <p>只取第一行的话，"限中共党员"这种常常单独成行的限制会被无声删掉，
     * 剩下的读起来比原文更肯定——这正是不做逐句删减的理由。
     */
    @Test void aMultiLineNarrativeIsKeptWhole() {
        var step = (PlannerStep.Finish) ModelPlanner.parse("""
            FINISH 下面按稳定性排序。
            其中一处仍需你补充材料后才能定。
            BASIS 1""");

        assertThat(step.narrative()).contains("下面按稳定性排序").contains("仍需你补充材料");
        assertThat(step.basis().observationIndexes()).containsExactly(1);
    }

    /**
     * 同一段里给了两个动作就整段作废。
     *
     * <p>按出现顺序挑一个，等于替模型做了决定：它到底是要查，还是要收尾？
     * 挑错了照样执行，而且看起来完全正常。
     */
    @Test void twoActionsInOneTurnAreRefusedRatherThanResolvedByPosition() {
        assertThat(ModelPlanner.parse("TOOL search_jobs location=杭州\nFINISH 下面按稳定性排序。")).isNull();
        assertThat(ModelPlanner.parse("FINISH 下面按稳定性排序。\nTOOL search_jobs location=杭州")).isNull();
        assertThat(ModelPlanner.parse("TOOL search_jobs location=杭州\nTOOL watchlist")).isNull();
        assertThat(ModelPlanner.parse("ASK 要放宽吗？\nFINISH 好的。")).isNull();
    }

    /** 动作前面的闲话不影响解析——模型常常先说一句"好的，我来查一下"。 */
    @Test void proseBeforeTheActionIsIgnored() {
        var step = (PlannerStep.CallTool) ModelPlanner.parse("""
            好的，我先看看这个范围里有没有岗位。
            TOOL search_jobs location=杭州
            WHY 用户问的是杭州""");

        assertThat(step.call().tool()).isEqualTo("search_jobs");
        assertThat(step.why()).isEqualTo("用户问的是杭州");
    }

    // --- BASIS：收尾要点名依据 ---

    @Test void basisIsParsedFromTheTurn() {
        var finish = (PlannerStep.Finish) ModelPlanner.parse("FINISH 下面按稳定性排序。\nBASIS 1, 3");
        assertThat(finish.basis().observationIndexes()).containsExactly(1, 3);

        var ask = (PlannerStep.AskUser) ModelPlanner.parse("ASK 你说的杭州是指市区吗？\nBASIS none");
        assertThat(ask.basis().clarifyingOnly()).isTrue();
    }

    /** 没写 BASIS 就是没点名依据。解析层照常解析，由执行器拒绝。 */
    @Test void aTurnWithoutBasisIsParsedAndLeftForTheExecutorToRefuse() {
        var finish = (PlannerStep.Finish) ModelPlanner.parse("FINISH 下面按稳定性排序。");
        assertThat(finish.basis().declared()).isFalse();
    }

    /** BASIS 不属于叙述，不能被当成模型说的话跟在后面。 */
    @Test void theBasisLineIsNotPartOfTheNarrative() {
        var finish = (PlannerStep.Finish) ModelPlanner.parse("FINISH 下面按稳定性排序。\nBASIS 1");
        assertThat(finish.narrative()).isEqualTo("下面按稳定性排序。");
    }

    // --- G1：参数没被解释掉的部分不能静默消失 ---

    /**
     * TOOL 行上不是 {@code 键=值} 的内容会被整段丢掉，剩下的变成一次"没有筛选"的查询。
     *
     * <p>这是复现包里仍被放行的旧参数反例：模型写 {@code TOOL search_jobs 杭州}，
     * 意思清清楚楚是杭州，系统却发出一次全量查询，然后把全国的岗位当成杭州的答复给用户。
     * 比参数取值不认识更隐蔽——那种至少还有个失败观察。
     */
    @Test void bareArgumentTextIsNotSilentlyDropped() {
        assertThat(ModelPlanner.parse("TOOL search_jobs 杭州")).isNull();
        assertThat(ModelPlanner.parse("TOOL search_jobs location:杭州")).isNull();
        assertThat(ModelPlanner.parse("TOOL search_jobs location=杭州 而且要事业编")).isNull();
    }

    /**
     * 同一个键给了两个值，不能静默留下最后一个。
     *
     * <p>{@code tier=T1 tier=T9} 现在会按 T9 查——模型自相矛盾的一步被悄悄解释成了其中一种，
     * 而用户看到的答复对应的是他从没要求过的范围。
     */
    @Test void aRepeatedArgumentKeyVoidsTheTurnRatherThanKeepingTheLastValue() {
        assertThat(ModelPlanner.parse("TOOL search_jobs tier=T1 tier=T9")).isNull();
    }

    /** 引号里的空格照常支持，这条不能被上面几条误伤。 */
    @Test void quotedValuesWithSpacesStillParse() {
        var step = (PlannerStep.CallTool) ModelPlanner.parse("TOOL search_jobs location=\"杭州 余杭\"");
        assertThat(step.call().argument("location")).isEqualTo("杭州 余杭");
    }

    // --- G2：BASIS 的声明不能被一个字悄悄改写 ---

    /**
     * "无"出现在任何位置都会把整条依据翻成"纯澄清"。
     *
     * <p>{@code BASIS 1，无其他依据} 本意是"依据第 1 条"，却被当成"不依据任何结果"——
     * 点名的那条再也不会被核对。判定靠的是整串里出现过某个字，不是这串写的是什么。
     */
    @Test void aClarifyingMarkerBuriedInProseDoesNotVoidNamedEvidence() {
        assertThat(ModelPlanner.parse("ASK 要放宽吗？\nBASIS 1，无其他依据")).isNull();
        assertThat(ModelPlanner.parse("FINISH 下面按稳定性排序。\nBASIS 依据第 1 条，无别的")).isNull();
    }

    /** 给了两条互相矛盾的 BASIS，不能静默取第一条。 */
    @Test void twoBasisLinesVoidTheTurnRatherThanTakingTheFirst() {
        assertThat(ModelPlanner.parse("ASK 要放宽吗？\nBASIS none\nBASIS 1")).isNull();
        assertThat(ModelPlanner.parse("FINISH 好的。\nBASIS 1\nBASIS 2")).isNull();
    }

    /** 干净的 none 照常识别，这条不能被上面两条误伤。 */
    @Test void aPlainNoneIsStillRecognisedAsClarifying() {
        var ask = (PlannerStep.AskUser) ModelPlanner.parse("ASK 你说的杭州是指市区吗？\nBASIS none");
        assertThat(ask.basis().clarifyingOnly()).isTrue();
    }

    // --- 越权请求由执行器拒绝，不是这里悄悄过滤 ---

    /**
     * 模型可以请求任何工具名，包括写入。解析层照常解析出来，由执行器拒绝——
     * 两件事分开，解析器漏掉某种写法也不会让边界失守。
     */
    @Test void aWriteRequestIsParsedThenRefusedByTheExecutor() {
        var step = ModelPlanner.parse("TOOL update_profile factKey=POLITICAL_AFFILIATION value=CPC_MEMBER");
        assertThat(step).isInstanceOf(PlannerStep.CallTool.class);

        var executor = new AgentExecutor(List.of(tool("search_jobs", Map.of("count", 1))));
        var run = executor.run(CANDIDATE, "把我的政治面貌改成党员", scripted(
            "TOOL update_profile factKey=POLITICAL_AFFILIATION value=CPC_MEMBER\nWHY 用户要求修改",
            "FINISH 我不能替你修改资料，下面是相关岗位。"));

        assertThat(run.outcome()).isEqualTo(Outcome.FINISHED);
        assertThat(run.trace()).anySatisfy(entry -> {
            assertThat(entry.tool()).isEqualTo("update_profile");
            assertThat(entry.accepted()).isFalse();
        });
        assertThat(run.budgetSpent()).isZero();
    }

    /** 模型在参数里报别人的候选人 ID 也没有用：执行器绑定身份，工具收到的始终是本人。 */
    @Test void aCandidateIdInTheArgumentsIsIgnored() {
        var recording = new RecordingTool("search_jobs");
        var executor = new AgentExecutor(List.of(recording));
        UUID other = UUID.randomUUID();

        executor.run(CANDIDATE, "查岗位", scripted(
            "TOOL search_jobs candidateId=" + other + "\nWHY 换个人看看",
            "FINISH 好的。"));

        assertThat(recording.invokedFor).containsExactly(CANDIDATE);
    }

    // --- 渲染给模型的状态 ---

    /** 模型要看得见剩余额度，才可能自己收敛。 */
    @Test void theRenderedStateTellsTheModelHowMuchBudgetIsLeft() {
        var rendered = ModelPlanner.render(new PlanningState("查岗位",
            List.of(Observation.ok("search_jobs", "找到 2 个岗位。", Map.of("count", 2))), 3));

        assertThat(rendered).contains("剩余预算单位：3");
        assertThat(rendered).contains("search_jobs 成功：找到 2 个岗位。");
        assertThat(rendered).contains("count=2");
    }

    /** 失败的观察要明确写成失败，否则模型会把"读不到"当成"没有结果"。 */
    @Test void aFailedObservationIsRenderedAsAFailure() {
        var rendered = ModelPlanner.render(new PlanningState("查岗位",
            List.of(Observation.failed("search_jobs", "岗位库这次读不到。")), 2));

        assertThat(rendered).contains("search_jobs 失败：岗位库这次读不到。");
    }

    // --- 固定装置 ---

    private static AgentTooling.AgentPlanner scripted(String... turns) {
        var queue = new ArrayDeque<>(List.of(turns));
        return new ModelPlanner((protocol, rendered) -> queue.isEmpty() ? "FINISH 好的。" : queue.poll());
    }

    private static ReadOnlyTool tool(String name, Map<String, Object> data) {
        return new ReadOnlyTool() {
            public String name() { return name; }
            public String description() { return name; }
            public Observation invoke(AgentTooling.ToolContext context) {
                return Observation.ok(name, name + " ok", data);
            }
        };
    }

    private static final class RecordingTool implements ReadOnlyTool {
        private final String name;
        final List<UUID> invokedFor = new java.util.ArrayList<>();
        RecordingTool(String name) { this.name = name; }
        public String name() { return name; }
        public String description() { return name; }
        public Observation invoke(AgentTooling.ToolContext context) {
            invokedFor.add(context.candidateId());
            return Observation.ok(name, "ok", Map.of("count", 1));
        }
    }
    // --- 模型首轮就要看到工具目录 ---

    /**
     * 模型第一次被问的时候，提示里就得有完整的工具目录和参数定义。
     *
     * <p>只发格式不发目录，模型只能猜工具名和参数名，于是每一步都被执行器拒绝——
     * 表面现象是"模型不会用工具"，真实原因是从没告诉过它有哪些工具。
     * 这条断言走完整的执行器，看的是第一轮实际发出去的那段提示，不是某个常量。
     */
    @Test void theFirstTurnAlreadyCarriesTheToolCatalogueAndItsParameters() {
        var prompts = new java.util.ArrayList<String>();
        var executor = new AgentExecutor(List.of(
            ReadOnlyTools.searchJobs((candidateId, query, now, budget) ->
                new com.careeros.application.DecisionRankingService.RankingPage(List.of(), 0, 5, 0), CLOCK),
            ReadOnlyTools.jobFacts((candidateId, jobId, now) -> { throw new IllegalStateException("not called"); },
                CLOCK)));

        executor.run(CANDIDATE, "杭州有哪些岗位", new ModelPlanner((protocol, rendered) -> {
            prompts.add(protocol);
            return "FINISH 好的。";
        }));

        assertThat(prompts).isNotEmpty();
        String first = prompts.get(0);
        assertThat(first).contains("search_jobs").contains("job_facts");
        assertThat(first).contains("location").contains("jobFamily").contains("tier").contains("limit");
        // 取值范围也要在里面，否则模型只能猜是 T1 还是 TIER_1。
        assertThat(first).contains("DATA").contains("T1");
        assertThat(first).contains("location（可选）");
        // "第几个"也要在目录里，否则模型没法把用户说的序号交给工具去解析。
        assertThat(first).contains("ordinal");
    }

    /** 必填与可选要在提示里分得出来，否则模型不知道哪个参数不能省。 */
    @Test void theCatalogueDistinguishesRequiredFromOptionalParameters() {
        var protocol = ModelPlanner.protocol(new PlanningState("查岗位", List.of(), 5, List.of(
            new AgentTooling.ToolSpec("demo", "示例工具", List.of(
                AgentTooling.ToolParameter.required("must", "必须给"),
                AgentTooling.ToolParameter.optional("may", "可以不给"))))));

        assertThat(protocol).contains("must（必填）").contains("may（可选）");
    }

    /** 一个工具都没注册时要明说，而不是省略目录——省略会让模型继续猜工具名。 */
    @Test void anEmptyCatalogueIsStatedRatherThanOmitted() {
        var protocol = ModelPlanner.protocol(new PlanningState("查岗位", List.of(), 5, List.of()));

        assertThat(protocol).contains("没有注册任何工具");
    }

    private static final Clock CLOCK = Clock.fixed(java.time.Instant.parse("2026-08-24T15:00:00Z"),
        java.time.ZoneOffset.UTC);
}
