package com.careeros.application.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.agent.AgentExecutor.Outcome;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.agent.AgentTooling.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 先用可控规划器把执行边界测干净，再让模型进来。
 *
 * <p>边界必须在模型之前就成立。用提示词请求模型守规矩不是边界——那是请求。
 */
class AgentExecutorTest {
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final UUID OTHER_CANDIDATE = UUID.randomUUID();

    /** 记录它被谁调用、拿到什么参数。 */
    private static final class RecordingTool implements ReadOnlyTool {
        private final String name;
        private final Map<String, Object> data;
        final List<UUID> invokedFor = new ArrayList<>();
        final List<ToolCall> calls = new ArrayList<>();

        RecordingTool(String name, Map<String, Object> data) { this.name = name; this.data = data; }
        public String name() { return name; }
        public String description() { return name; }
        public Observation invoke(AgentTooling.ToolContext context) {
            invokedFor.add(context.candidateId());
            calls.add(context.call());
            return Observation.ok(name, name + " 返回结果", data);
        }
    }

    // --- 只读：写入工具进不来 ---

    /**
     * 写入不在工具面里，所以模型请求写入时不是"被劝阻"，是没有这个东西。
     * 首版的写入一律走用户明确操作的确定性接口。
     */
    @Test void aWriteToolIsNotAvailableToThePlanner() {
        var search = new RecordingTool("search_jobs", Map.of("count", 1));
        var executor = new AgentExecutor(List.of(search));
        var attempted = new ArrayList<String>();

        var run = executor.run(CANDIDATE, "帮我把政治面貌改成党员", state -> {
            if (state.observations().isEmpty()) {
                attempted.add("update_profile");
                return new PlannerStep.CallTool(ToolCall.of("update_profile", "value", "CPC_MEMBER"), "想直接改资料");
            }
            return new PlannerStep.Finish("已经按公开信息整理如下。");
        });

        assertThat(executor.registeredTools()).containsExactly("search_jobs");
        assertThat(run.outcome()).isEqualTo(Outcome.FINISHED);
        // 请求发生过，但没有被执行；而且拒绝留在轨迹里。
        assertThat(attempted).containsExactly("update_profile");
        assertThat(run.trace()).anySatisfy(entry -> {
            assertThat(entry.tool()).isEqualTo("update_profile");
            assertThat(entry.accepted()).isFalse();
            assertThat(entry.reason()).contains("未注册");
        });
        assertThat(run.budgetSpent()).isZero();
    }

    /** 未注册的工具不消耗预算——否则模型乱报工具名就能把额度耗光。 */
    @Test void aRejectedToolDoesNotSpendBudget() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of())), 4, 2);

        var run = executor.run(CANDIDATE, "随便问问", state -> state.observations().size() < 2
            ? new PlannerStep.CallTool(ToolCall.of("nope_" + state.observations().size()), "乱报")
            : new PlannerStep.Finish("好的。"));

        assertThat(run.budgetSpent()).isZero();
        assertThat(run.outcome()).isEqualTo(Outcome.FINISHED);
    }

    // --- 归属：候选人由执行器绑定 ---

    /**
     * 规划器给不了候选人身份。这不是靠"别传"约定，是 ToolCall 里根本没有这个字段；
     * 即使塞进参数里也不会被用到——否则报出别人的 ID 就能读到别人的资料。
     */
    @Test void thePlannerCannotChooseWhoseDataIsRead() {
        var search = new RecordingTool("search_jobs", Map.of("count", 1));
        var executor = new AgentExecutor(List.of(search));

        executor.run(CANDIDATE, "查岗位", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(
                ToolCall.of("search_jobs", "candidateId", OTHER_CANDIDATE.toString()), "试图换个候选人")
            : new PlannerStep.Finish("好的。"));

        assertThat(search.invokedFor).containsExactly(CANDIDATE);
        assertThat(search.invokedFor).doesNotContain(OTHER_CANDIDATE);
    }

    // --- 共享预算 ---

    /** 预算是一次运行共用的，不是每个工具各有一份。 */
    @Test void theBudgetIsSharedAcrossDifferentTools() {
        var first = new RecordingTool("search_jobs", Map.of());
        var second = new RecordingTool("watchlist", Map.of());
        var executor = new AgentExecutor(List.of(first, second), 8, 2);

        var run = executor.run(CANDIDATE, "查一圈", state -> switch (state.observations().size()) {
            case 0 -> new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查岗位");
            case 1 -> new PlannerStep.CallTool(ToolCall.of("watchlist"), "再看关注");
            default -> new PlannerStep.CallTool(ToolCall.of("search_jobs"), "再查一次");
        });

        assertThat(run.outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
        assertThat(run.budgetSpent()).isEqualTo(2);
        assertThat(first.calls).hasSize(1);
        assertThat(second.calls).hasSize(1);
    }

    /** 预算用完要说出来，不能让规划器以为工具本身没有结果。 */
    @Test void exhaustingTheBudgetIsReportedRatherThanLookingLikeAnEmptyResult() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of())), 8, 1);

        var run = executor.run(CANDIDATE, "查一圈",
            state -> new PlannerStep.CallTool(ToolCall.of("search_jobs"), "一直查"));

        assertThat(run.outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
        assertThat(run.observations().getLast().ok()).isFalse();
        assertThat(run.observations().getLast().summary()).contains("预算");
    }

    // --- 一定会结束 ---

    /** 规划器永远不收敛时，步数上限保证运行结束。 */
    @Test void aPlannerThatNeverFinishesIsStoppedByTheStepLimit() {
        var calls = new AtomicInteger();
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of())), 3, 100);

        var run = executor.run(CANDIDATE, "无限循环", state -> {
            calls.incrementAndGet();
            return new PlannerStep.CallTool(ToolCall.of("search_jobs"), "再来一次");
        });

        assertThat(run.outcome()).isEqualTo(Outcome.STEP_LIMIT_REACHED);
        assertThat(calls).hasValue(3);
    }

    /** 规划器抛异常不该把整次运行变成崩溃：已经取到的观察仍然有用。 */
    @Test void aFailingPlannerEndsTheRunWithWhatWasAlreadyObserved() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 2))));

        var run = executor.run(CANDIDATE, "查岗位", state -> {
            if (state.observations().isEmpty()) {
                return new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查");
            }
            throw new IllegalStateException("planner blew up");
        });

        assertThat(run.outcome()).isEqualTo(Outcome.PLANNER_FAILED);
        assertThat(run.observations()).hasSize(1);
    }

    /** 工具本身失败不能让整次运行崩掉，规划器要能看到这次失败并改走别的路。 */
    @Test void aFailingToolBecomesAnObservationThePlannerCanReactTo() {
        ReadOnlyTool broken = new ReadOnlyTool() {
            public String name() { return "search_jobs"; }
            public String description() { return "坏掉的搜索"; }
            public Observation invoke(AgentTooling.ToolContext context) { throw new IllegalStateException("boom"); }
        };
        var executor = new AgentExecutor(List.of(broken));

        var run = executor.run(CANDIDATE, "查岗位", state -> {
            var last = state.last();
            if (last == null) return new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查");
            return last.ok() ? new PlannerStep.Finish("查到了。")
                : new PlannerStep.AskUser("岗位库这次读不到，要不要换个条件再试？");
        });

        assertThat(run.outcome()).isEqualTo(Outcome.ASKED_USER);
        assertThat(run.question()).contains("读不到");
    }

    // --- 不是硬编码流程：分支由工具结果驱动 ---

    /**
     * 同一个规划器，喂给它不同的工具结果，它要走出不同的下一步。
     *
     * <p>这条是"不要把硬编码流程叫作 Agent"的最小判据。流程写死的实现在这两种输入下
     * 会调用同一串工具；依据结果选择的实现不会。
     */
    @Test void thePlannerTakesADifferentNextStepForADifferentToolResult() {
        AgentTooling.AgentPlanner planner = state -> {
            var last = state.last();
            if (last == null) return new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先看有没有岗位");
            if (last.tool().equals("search_jobs")) {
                int count = last.value("count", 0);
                // 有岗位就去看待确认事项；一个都没有就去问用户放宽条件，而不是继续往下查。
                return count == 0
                    ? new PlannerStep.AskUser("这个范围内没有岗位，要不要放宽城市或职位类别？")
                    : new PlannerStep.CallTool(ToolCall.of("pending_confirmations"), "有岗位，看看还缺什么确认");
            }
            return new PlannerStep.Finish("下面按稳定性排序，其中一处仍需你补充材料后才能定。");
        };

        var withJobs = new AgentExecutor(List.of(
            new RecordingTool("search_jobs", Map.of("count", 3)),
            new RecordingTool("pending_confirmations", Map.of("count", 1))));
        var empty = new AgentExecutor(List.of(
            new RecordingTool("search_jobs", Map.of("count", 0)),
            new RecordingTool("pending_confirmations", Map.of("count", 1))));

        var runWithJobs = withJobs.run(CANDIDATE, "杭州有哪些岗位", planner);
        var runEmpty = empty.run(CANDIDATE, "杭州有哪些岗位", planner);

        assertThat(runWithJobs.outcome()).isEqualTo(Outcome.FINISHED);
        assertThat(runWithJobs.trace()).extracting(AgentExecutor.TraceEntry::tool)
            .containsExactly("search_jobs", "pending_confirmations");

        assertThat(runEmpty.outcome()).isEqualTo(Outcome.ASKED_USER);
        assertThat(runEmpty.trace()).extracting(AgentExecutor.TraceEntry::tool)
            .containsExactly("search_jobs");
        assertThat(runEmpty.question()).contains("放宽");
    }

    // --- 叙述不能带判定，执行器就地校验 ---

    /**
     * 带判定词或数值的叙述不能离开执行器。等到渲染阶段再拦已经晚了——
     * 调用方可能先把它用掉（记日志、直接回给用户）。
     */
    @Test void aFinishNarrativeCarryingVerdictsIsRejectedBeforeItLeaves() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "查岗位",
            state -> new PlannerStep.Finish("这个岗位你是可报的，适配 72 分，放心投。"));

        assertThat(run.outcome()).isEqualTo(Outcome.FINISHED);
        assertThat(run.narrative()).isNull();
        assertThat(run.textRejected()).isTrue();
        assertThat(run.violations()).isNotEmpty();
    }

    /** 合格的连接性叙述照常放行——前提是背后真的读到了东西。 */
    @Test void aPlainConnectiveNarrativeSurvives() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "查岗位", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("下面按稳定性排序，其中一处仍需你补充材料后才能定。"));

        assertThat(run.narrative()).isEqualTo("下面按稳定性排序，其中一处仍需你补充材料后才能定。");
        assertThat(run.textRejected()).isFalse();
    }

    /** 轨迹要能回答"它为什么这么选"，否则事后分不清依据结果还是走固定流程。 */
    @Test void theTraceRecordsWhyEachToolWasChosen() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "查岗位", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "用户问的是岗位，先查岗位库")
            : new PlannerStep.Finish("好的。"));

        assertThat(run.trace()).singleElement().satisfies(entry -> {
            assertThat(entry.accepted()).isTrue();
            assertThat(entry.why()).isEqualTo("用户问的是岗位，先查岗位库");
        });
    }

    /** 规划器看得见剩余额度，才有可能自己收敛而不是撞上限。 */
    @Test void thePlannerCanSeeHowMuchBudgetIsLeft() {
        var seen = new ArrayList<Integer>();
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of())), 8, 3);

        executor.run(CANDIDATE, "查岗位", state -> {
            seen.add(state.remainingBudget());
            return state.observations().size() < 2
                ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "查")
                : new PlannerStep.Finish("好的。");
        });

        assertThat(seen).containsExactly(3, 2, 1);
    }
    // --- 收尾的话也要有依据 ---

    /**
     * 一次成功的工具结果都没有，收尾叙述就没有依据，整段丢弃。
     *
     * <p>叙述校验器管的是"别写数字和判定词"，管不了"凭空说话"：
     * 「这些岗位都挺适合你的」一个数字一个判定词都没有，但它背后一条数据也没有。
     * 这两件事必须分开拦，只靠校验器会漏掉后一种。
     */
    @Test void aFinishWithNothingRetrievedKeepsNoNarrative() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "有什么合适的",
            state -> new PlannerStep.Finish("这些岗位都挺适合你的，可以先从前面几个看起。"));

        assertThat(run.outcome()).isEqualTo(Outcome.FINISHED);
        assertThat(run.narrative()).isNull();
        assertThat(run.violations()).contains(AgentExecutor.UNGROUNDED_FINISH);
        assertThat(run.groundedIn()).isZero();
    }

    /** 工具全都失败也算没有依据——"读不到"不是"没有"。 */
    @Test void aFinishAfterOnlyFailedToolsIsAlsoUngrounded() {
        ReadOnlyTool broken = new ReadOnlyTool() {
            public String name() { return "search_jobs"; }
            public String description() { return "坏掉的搜索"; }
            public Observation invoke(AgentTooling.ToolContext context) { throw new IllegalStateException("boom"); }
        };
        var executor = new AgentExecutor(List.of(broken));

        var run = executor.run(CANDIDATE, "查岗位", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("这个范围里目前没有值得看的机会。"));

        assertThat(run.narrative()).isNull();
        assertThat(run.violations()).contains(AgentExecutor.UNGROUNDED_FINISH);
    }

    /** 拿到一条真结果之后，同样一句话就能留下。 */
    @Test void theSameNarrativeSurvivesOnceSomethingWasActuallyRead() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "有什么合适的", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("下面按稳定性排序，先看前面几个。"));

        assertThat(run.narrative()).isEqualTo("下面按稳定性排序，先看前面几个。");
        assertThat(run.violations()).isEmpty();
        assertThat(run.groundedIn()).isEqualTo(1);
    }

    /**
     * 追问和收尾走同一套校验。
     *
     * <p>只校验 FINISH 的话，判定词换成疑问句就整句溜出去了——用户看到的是同一段话，
     * 末尾多了个问号而已。
     */
    @Test void anAskCarryingAVerdictIsRejectedJustLikeANarrative() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "查岗位",
            state -> new PlannerStep.AskUser("这个岗位你是可报的，适配 72 分，要我继续往下看吗？"));

        assertThat(run.outcome()).isEqualTo(Outcome.ASKED_USER);
        assertThat(run.question()).isNull();
        assertThat(run.textRejected()).isTrue();
    }

    /** 陈述句不能借追问的通道发出去，否则"没有依据的结论"绕开 FINISH 就能出去。 */
    @Test void aStatementSentThroughTheAskChannelIsRejected() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "查岗位",
            state -> new PlannerStep.AskUser("这些岗位都挺适合你的。"));

        assertThat(run.question()).isNull();
        assertThat(run.violations()).contains(AgentExecutor.ASK_IS_NOT_A_QUESTION);
    }

    /** 澄清式追问本来就不需要依据：还没查之前就该能问"你说的是哪个范围"。 */
    @Test void aClarifyingQuestionNeedsNoEvidence() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "杭州有什么",
            state -> new PlannerStep.AskUser("你说的杭州是指市区，还是整个杭州市？"));

        assertThat(run.outcome()).isEqualTo(Outcome.ASKED_USER);
        assertThat(run.question()).contains("市区");
        assertThat(run.violations()).isEmpty();
    }

    // --- 预算按真实扇出计，不是按工具调用次数 ---

    /**
     * 工具内部的逐岗评估要扣同一份预算。
     *
     * <p>只按"调了一次工具"记一个单位的话，一次 search_jobs 背后的几十次评估全是白嫖的——
     * 模型调三次工具就能触发上百次评估，预算写了等于没写。
     */
    @Test void aToolsInternalPerJobAssessmentsAreChargedToTheSharedBudget() {
        var executor = new AgentExecutor(List.of(fansOutTo(4, "search_jobs")), 8, 20);

        var run = executor.run(CANDIDATE, "查岗位", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("下面按稳定性排序。"));

        // 一次调用本身算一个单位，内部四次评估再算四个。
        assertThat(run.budgetSpent()).isEqualTo(5);
        assertThat(run.trace()).singleElement()
            .satisfies(entry -> assertThat(entry.budgetUnits()).isEqualTo(5));
    }

    /** 扇出把预算吃完之后，下一次调用就该被拒——共享的意思就是一个人花完了别人也没有。 */
    @Test void aLargeFanOutExhaustsTheBudgetForLaterCalls() {
        var executor = new AgentExecutor(List.of(fansOutTo(6, "search_jobs"), fansOutTo(1, "watchlist")), 8, 7);

        var run = executor.run(CANDIDATE, "查一圈", state -> switch (state.observations().size()) {
            case 0 -> new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查岗位");
            default -> new PlannerStep.CallTool(ToolCall.of("watchlist"), "再看关注");
        });

        assertThat(run.outcome()).isEqualTo(Outcome.BUDGET_EXHAUSTED);
        assertThat(run.observations().getLast().summary()).contains("预算");
    }

    /** 一个每次调用都在内部评估若干岗位的工具。 */
    private static ReadOnlyTool fansOutTo(int jobs, String name) {
        return new ReadOnlyTool() {
            public String name() { return name; }
            public String description() { return name; }
            public Observation invoke(AgentTooling.ToolContext context) {
                int assessed = 0;
                for (int index = 0; index < jobs; index++) {
                    if (!context.budget().tryConsume()) break;
                    assessed++;
                }
                return Observation.ok(name, name + " 评估了若干岗位", Map.of("count", assessed));
            }
        };
    }
}
