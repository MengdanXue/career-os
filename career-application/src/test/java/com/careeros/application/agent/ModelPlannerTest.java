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
        // 必填与可选要分得出来：jobId 是必填的，筛选参数都是可选的。
        assertThat(first).contains("jobId（必填）").contains("location（可选）");
    }

    /** 一个工具都没注册时要明说，而不是省略目录——省略会让模型继续猜工具名。 */
    @Test void anEmptyCatalogueIsStatedRatherThanOmitted() {
        var protocol = ModelPlanner.protocol(new PlanningState("查岗位", List.of(), 5, List.of()));

        assertThat(protocol).contains("没有注册任何工具");
    }

    private static final Clock CLOCK = Clock.fixed(java.time.Instant.parse("2026-08-24T15:00:00Z"),
        java.time.ZoneOffset.UTC);
}
