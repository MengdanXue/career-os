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
            return new PlannerStep.Finish("RANKED_LISTING");
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
            : new PlannerStep.Finish("RANKED_LISTING"));

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
            : new PlannerStep.Finish("RANKED_LISTING"));

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
            return last.ok() ? new PlannerStep.Finish("RANKED_LISTING", AgentTooling.Basis.on(1))
                : new PlannerStep.AskUser("RETRY_LATER", AgentTooling.Basis.clarifying());
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
                    ? new PlannerStep.AskUser("BROADEN_SCOPE", AgentTooling.Basis.on(1))
                    : new PlannerStep.CallTool(ToolCall.of("pending_confirmations"), "有岗位，看看还缺什么确认");
            }
            return new PlannerStep.Finish("RANKED_LISTING",
                AgentTooling.Basis.on(1, 2));
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
            state -> new PlannerStep.Finish("RANKED_LISTING",
                AgentTooling.Basis.clarifying()));

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
            : new PlannerStep.Finish("RANKED_LISTING",
                AgentTooling.Basis.on(1)));

        assertThat(run.narrative()).isEqualTo("下面是这个范围内的岗位，按稳定性排序；资格、限制条件和截止日见下方事实块。");
        assertThat(run.textRejected()).isFalse();
    }

    /** 轨迹要能回答"它为什么这么选"，否则事后分不清依据结果还是走固定流程。 */
    @Test void theTraceRecordsWhyEachToolWasChosen() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "查岗位", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "用户问的是岗位，先查岗位库")
            : new PlannerStep.Finish("RANKED_LISTING"));

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
                : new PlannerStep.Finish("RANKED_LISTING");
        });

        assertThat(seen).containsExactly(3, 2, 1);
    }
    // --- 收尾的话要绑到具体依据 ---

    /**
     * 收尾必须点名它依据的是哪一条结果。
     *
     * <p>叙述校验器管的是"别写数字和判定词"，管不了"凭空说话"：
     * 「这些岗位都挺适合你的」一个数字一个判定词都没有，但它背后一条数据也没有。
     */
    @Test void aFinishThatNamesNoBasisKeepsNoNarrative() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "有什么合适的",
            state -> new PlannerStep.Finish("RANKED_LISTING"));

        assertThat(run.outcome()).isEqualTo(Outcome.FINISHED);
        assertThat(run.narrative()).isNull();
        assertThat(run.violations()).contains(AgentExecutor.UNGROUNDED_FINISH);
        assertThat(run.groundedOn()).isEmpty();
    }

    /**
     * "这次运行里有成功的结果"不等于"这段话用了它"。
     *
     * <p>这是上一版判据的漏洞：查了关注清单，然后收尾说岗位怎么样，照样通过——
     * 附近有数据不代表这句话有依据。点名之后，依据是否成立才成了可以核对的事。
     */
    @Test void aSuccessfulObservationElsewhereDoesNotBackAnUnnamedClaim() {
        var executor = new AgentExecutor(List.of(new RecordingTool("watchlist", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "有什么合适的", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("watchlist"), "先看关注")
            // 有一条成功的观察，但这段话没说自己依据它。
            : new PlannerStep.Finish("RANKED_LISTING"));

        assertThat(run.narrative()).isNull();
        assertThat(run.violations()).contains(AgentExecutor.UNGROUNDED_FINISH);
    }

    /** 点名一条不存在的依据，比不点名更糟：它看起来是有出处的。 */
    @Test void aBasisPointingPastTheEndIsRejected() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "有什么合适的", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("RANKED_LISTING", AgentTooling.Basis.on(7)));

        assertThat(run.narrative()).isNull();
        assertThat(run.violations()).anySatisfy(reason ->
            assertThat(reason).contains(AgentExecutor.BASIS_NOT_SUPPORTED).contains("第 7 条"));
    }

    /** 点名一条失败的观察也不成立——"读不到"不是依据。 */
    @Test void aBasisPointingAtAFailedObservationIsRejected() {
        ReadOnlyTool broken = new ReadOnlyTool() {
            public String name() { return "search_jobs"; }
            public String description() { return "坏掉的搜索"; }
            public Observation invoke(AgentTooling.ToolContext context) { throw new IllegalStateException("boom"); }
        };
        var executor = new AgentExecutor(List.of(broken));

        var run = executor.run(CANDIDATE, "查岗位", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("RANKED_LISTING", AgentTooling.Basis.on(1)));

        assertThat(run.narrative()).isNull();
        assertThat(run.violations()).anySatisfy(reason ->
            assertThat(reason).contains(AgentExecutor.BASIS_NOT_SUPPORTED).contains("失败"));
    }

    /** 点名一条成立的依据，同样一句话就能留下，而且依据要报出来。 */
    @Test void aNarrativeBackedByANamedSuccessfulObservationSurvives() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "有什么合适的", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("RANKED_LISTING", AgentTooling.Basis.on(1)));

        assertThat(run.narrative()).isEqualTo("下面是这个范围内的岗位，按稳定性排序；资格、限制条件和截止日见下方事实块。");
        assertThat(run.violations()).isEmpty();
        assertThat(run.groundedOn()).containsExactly(1);
    }

    /** 收尾不能用"纯澄清"当依据——那是追问的选项，不是结束的理由。 */
    @Test void aFinishCannotDeclareItselfClarifyingOnly() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "有什么合适的", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("RANKED_LISTING", AgentTooling.Basis.clarifying()));

        assertThat(run.narrative()).isNull();
        assertThat(run.violations()).contains(AgentExecutor.UNGROUNDED_FINISH);
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
            state -> new PlannerStep.AskUser("这个岗位你是可报的，适配 72 分，要我继续往下看吗？",
                AgentTooling.Basis.clarifying()));

        assertThat(run.outcome()).isEqualTo(Outcome.ASKED_USER);
        assertThat(run.question()).isNull();
        assertThat(run.textRejected()).isTrue();
    }

    /** 陈述句不能借追问的通道发出去，否则"没有依据的结论"绕开 FINISH 就能出去。 */
    @Test void aStatementSentThroughTheAskChannelIsRejected() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "查岗位",
            state -> new PlannerStep.AskUser("这些岗位都挺适合你的。", AgentTooling.Basis.clarifying()));

        assertThat(run.question()).isNull();
        assertThat(run.violations()).contains(AgentExecutor.UNKNOWN_TEMPLATE);
    }

    /** 澄清式追问本来就不需要依据：还没查之前就该能问"你说的是哪个范围"。 */
    @Test void aClarifyingQuestionNeedsNoEvidence() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "杭州有什么",
            state -> new PlannerStep.AskUser("WHICH_LOCATION",
                AgentTooling.Basis.clarifying()));

        assertThat(run.outcome()).isEqualTo(Outcome.ASKED_USER);
        assertThat(run.question()).isEqualTo("你想看哪个城市或区县的岗位？");
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
            : new PlannerStep.Finish("RANKED_LISTING"));

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
    // --- G3：仍被放行的无依据文本 ---

    /**
     * 声明成"纯澄清"的追问照样可以夹带结论。
     *
     * <p>这是复现包里仍被放行的反例：{@code BASIS none} 一写，这段话就完全不受依据检查，
     * 而它说的"这些岗位都挺适合你的"既没有数字也没有判定词，叙述校验器也放行。
     * 用户看到的是一句系统从来没算过的判断。
     */
    @Test void aClarifyingQuestionCannotSmuggleAJudgement() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "有什么合适的",
            state -> new PlannerStep.AskUser("这些岗位都挺适合你的，要继续看吗？",
                AgentTooling.Basis.clarifying()));

        assertThat(run.question()).isNull();
        assertThat(run.violations()).isNotEmpty();
    }

    /** 陈述句加一个问号也不行：句号之前那半句仍然是个没算过的判断。 */
    @Test void aStatementFollowedByAQuestionIsRefused() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "有什么合适的",
            state -> new PlannerStep.AskUser("这批里有几个值得报。要我接着看吗？",
                AgentTooling.Basis.clarifying()));

        assertThat(run.question()).isNull();
        assertThat(run.violations()).isNotEmpty();
    }

    /**
     * 点名了依据，也不能替系统下推荐结论。
     *
     * <p>"适合你""建议优先报考"是判定，和"可报""T1"一样只能由确定性事实块给出。
     * 有依据说明这段话背后有数据，不说明这个判断是系统算出来的。
     */
    @Test void aRecommendationIsRefusedEvenWhenItNamesEvidence() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "有什么合适的", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("这些岗位都很适合你，建议优先准备。", AgentTooling.Basis.on(1)));

        assertThat(run.narrative()).isNull();
        assertThat(run.violations()).contains(AgentExecutor.UNKNOWN_TEMPLATE);
    }

    /** 正常的连接性收尾不能被上面几条误伤。 */
    @Test void aPlainConnectiveNarrativeIsStillAccepted() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "有什么合适的", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("RANKED_LISTING", AgentTooling.Basis.on(1)));

        assertThat(run.narrative()).isEqualTo("下面是这个范围内的岗位，按稳定性排序；资格、限制条件和截止日见下方事实块。");
        assertThat(run.violations()).isEmpty();
    }

    /**
     * 每一条模板渲染出来的句子都要过叙述校验器。
     *
     * <p>模板是我们自己写的，本来就该通过。留这一道是为了将来有人改模板时，
     * 改出判定词会当场红，而不是等用户看到。
     */
    @Test void everyTemplateRendersTextThatPassesTheNarrativeRules() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));
        var validator = new com.careeros.domain.AnswerNarrativeValidator();

        for (String template : executor.closingTemplates()) {
            var run = executor.run(CANDIDATE, "查岗位", state -> state.observations().isEmpty()
                ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
                : new PlannerStep.Finish(template, AgentTooling.Basis.on(1)));
            assertThat(run.narrative()).as("模板 %s 渲染不出句子", template).isNotNull();
            assertThat(validator.validateNarrative(run.narrative()).accepted())
                .as("模板 %s 渲染出来的句子过不了叙述校验：%s", template, run.narrative()).isTrue();
        }
        for (String template : executor.questionTemplates()) {
            var slots = "CONFIRM_FACT".equals(template) ? Map.of("fact", "GENDER") : Map.<String, String>of();
            var run = executor.run(CANDIDATE, "查岗位",
                state -> new PlannerStep.AskUser(template, slots, AgentTooling.Basis.clarifying()));
            assertThat(run.question()).as("模板 %s 渲染不出句子", template).isNotNull();
            assertThat(validator.validateNarrative(run.question()).accepted())
                .as("模板 %s 渲染出来的句子过不了叙述校验：%s", template, run.question()).isTrue();
        }
    }

    /** 模板名不在清单里就没有这句话——模型报一个看起来合理的名字也不行。 */
    @Test void anUnknownTemplateProducesNoUserVisibleText() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "查岗位", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("LOOKS_REASONABLE", AgentTooling.Basis.on(1)));

        assertThat(run.narrative()).isNull();
        assertThat(run.violations()).contains(AgentExecutor.UNKNOWN_TEMPLATE);
    }

    /** 槽位取值只能来自封闭枚举：渲染出一个看不懂的字段名，比不渲染更糟。 */
    @Test void aSlotValueOutsideTheClosedSetProducesNoQuestion() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "查岗位", state -> new PlannerStep.AskUser(
            "CONFIRM_FACT", Map.of("fact", "FAVOURITE_COLOUR"), AgentTooling.Basis.clarifying()));

        assertThat(run.question()).isNull();
        assertThat(run.violations()).contains(AgentExecutor.UNKNOWN_TEMPLATE);
    }

    /** 槽位对得上时照常渲染，标签用中文，不把枚举名摆给用户。 */
    @Test void aConfirmFactQuestionRendersTheHumanLabel() {
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        var run = executor.run(CANDIDATE, "查岗位", state -> new PlannerStep.AskUser(
            "CONFIRM_FACT", Map.of("fact", "GENDER"), AgentTooling.Basis.clarifying()));

        assertThat(run.question()).isEqualTo("要不要现在确认「性别」？");
    }

    // --- G2：复核包里仍被放行的五条原句 ---

    /**
     * 这五条是复核包点名的四类：资格、材料、概率、伪澄清。
     *
     * <p>它们一个数字、一个分层标签都没有，词表也拦不住——上一轮新加的推荐词表拦的是
     * "适合""建议报考"那几种写法，换个说法就绕过去了。靠往词表里加词去追，
     * 永远慢模型一步：能说的句子是无穷的，词表是有限的。
     *
     * <p>所以这一轮不再加词：用户可见的 FINISH／ASK 收束成有限模板，
     * 模型只能选模板和给结构化引用，句子由程序渲染。这五条于是写不出来，
     * 不是被检测出来。
     */
    @Test void theReviewPackSentencesCannotReachTheUser() {
        List<String> refused = List.of(
            // 资格：替系统下了"没有硬门槛"的判断
            "FINISH 你这条线基本没什么硬门槛挡着。\nBASIS 1",
            // 资格：替系统下了"条件都对得上"的判断
            "FINISH 这几个的报考条件你都对得上。\nBASIS 1",
            // 材料：替系统承诺"补齐就能报名"
            "FINISH 你把学历证明补齐就能报名了。\nBASIS 1",
            // 概率：把决策指数说成进面的把握
            "FINISH 以你的条件，进面基本没问题。\nBASIS 1",
            // 伪澄清：判断裹在问句里，声明成纯澄清就不受依据检查
            "ASK 这批里有几个明显更稳妥，要我先讲哪个？\nBASIS none");

        for (String turn : refused) {
            var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));
            var queue = new java.util.ArrayDeque<>(List.of(
                "TOOL search_jobs\nWHY 先查", turn));
            var run = executor.run(CANDIDATE, "有什么合适的",
                new ModelPlanner((protocol, rendered) -> queue.isEmpty() ? "" : queue.poll()));

            assertThat(run.narrative()).as("这句话到了用户面前：%s", turn).isNull();
            assertThat(run.question()).as("这句话到了用户面前：%s", turn).isNull();
        }
    }

    // --- 多轮：上一轮的上下文必须真的被用上 ---

    /**
     * 追问之后用户只答一句"余杭"。
     *
     * <p>这是复核里的旧反例。只把 sessionId 原样回传不算续跑——"余杭"单独看是个残句，
     * 模型没有上一轮限定的杭州，就只能再问一次或者当成一个孤立的新问题；
     * 用户已经答过了，却被再问一遍。
     *
     * <p>这里的规划器只有看得见上一轮范围时才收窄，看不见就只能再追问一次。
     * 断言的是"收窄真的发生了"，不是"sessionId 回来了"。
     */
    @Test void aBareDistrictAnswerNarrowsThePreviousScope() {
        var search = new RecordingTool("search_jobs", Map.of("count", 1));
        var executor = new AgentExecutor(List.of(search));
        AgentTooling.AgentPlanner narrowing = state -> {
            if (!state.observations().isEmpty()) {
                return new PlannerStep.Finish("RANKED_LISTING", AgentTooling.Basis.on(1));
            }
            String previous = state.session().location();
            // 看不见上一轮的范围，就只能再问一次"你说的是哪里"——用户会看到自己刚答过的问题又来一遍。
            if (previous == null || previous.isBlank()) {
                return new PlannerStep.AskUser("WHICH_LOCATION", AgentTooling.Basis.clarifying());
            }
            return new PlannerStep.CallTool(
                ToolCall.of("search_jobs", "location", state.question().strip()),
                "用户在" + previous + "的基础上收窄到" + state.question().strip());
        };

        var continued = executor.run(CANDIDATE, "余杭", narrowing, hangzhouSession());
        var fresh = executor.run(CANDIDATE, "余杭", narrowing);

        assertThat(continued.outcome()).isEqualTo(Outcome.FINISHED);
        assertThat(search.calls).singleElement()
            .satisfies(call -> assertThat(call.argument("location")).isEqualTo("余杭"));
        // 没有上一轮时它只能再问一遍——这正是旧反例里用户遇到的。
        assertThat(fresh.outcome()).isEqualTo(Outcome.ASKED_USER);
    }

    /** 模型要真的看得见上一轮的范围和列表顺序，否则上面那一步无从做起。 */
    @Test void theRenderedStateCarriesThePreviousScopeAndListing() {
        var seen = new ArrayList<String>();
        var executor = new AgentExecutor(List.of(new RecordingTool("search_jobs", Map.of("count", 1))));

        executor.run(CANDIDATE, "余杭", new ModelPlanner((protocol, rendered) -> {
            seen.add(rendered);
            return "FINISH 好的。\nBASIS none";
        }), hangzhouSession());

        assertThat(seen).isNotEmpty();
        assertThat(seen.get(0)).contains("上一轮的范围").contains("杭州");
        assertThat(seen.get(0)).contains("上一轮列表")
            .contains("1. jobId=" + FIRST_JOB).contains("2. jobId=" + SECOND_JOB);
    }

    /**
     * 刷新之后用户说"第二个"，指的是他屏幕上那一份列表的第二个。
     *
     * <p>这是复核里的另一个旧反例。序号只在上一轮记下来的顺序里解析，从不重新排名——
     * 重新排出来的第二个可能是另一个岗位，而用户看不出系统换了对象。
     */
    @Test void theSecondOneResolvesAgainstTheListingTheUserSaw() {
        var assessed = new ArrayList<UUID>();
        var executor = new AgentExecutor(List.of(ReadOnlyTools.jobFacts((candidateId, jobId, now) -> {
            assessed.add(jobId);
            throw new IllegalStateException("这个测试只关心问的是哪个岗位");
        }, java.time.Clock.systemUTC())));

        executor.run(CANDIDATE, "第二个怎么样", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("job_facts", "ordinal", "2"), "用户指的是上一轮的第二个")
            : new PlannerStep.Finish("RANKED_LISTING", AgentTooling.Basis.on(1)), hangzhouSession());

        assertThat(assessed).containsExactly(SECOND_JOB);
    }

    /** 没有上一轮列表时要明说，而不是拿这一轮重新排出来的第二个顶上去。 */
    @Test void anOrdinalWithoutAPreviousListingIsRefusedRatherThanReRanked() {
        var executor = new AgentExecutor(List.of(ReadOnlyTools.jobFacts(
            (candidateId, jobId, now) -> { throw new AssertionError("不该评估任何岗位"); },
            java.time.Clock.systemUTC())));

        var run = executor.run(CANDIDATE, "第二个怎么样", state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("job_facts", "ordinal", "2"), "用户指的是第二个")
            : new PlannerStep.Finish("RANKED_LISTING", AgentTooling.Basis.on(1)));

        assertThat(run.observations()).first().satisfies(observation -> {
            assertThat(observation.ok()).isFalse();
            assertThat(observation.summary()).contains("没有上一轮的列表");
        });
    }

    private static final UUID FIRST_JOB = UUID.randomUUID();
    private static final UUID SECOND_JOB = UUID.randomUUID();

    /** 上一轮：范围限定杭州，列出了两个岗位，还有一项没答。 */
    private static AgentTooling.SessionContext hangzhouSession() {
        return new AgentTooling.SessionContext(UUID.randomUUID(), "杭州", null, null,
            List.of(new AgentTooling.JobRef(1, FIRST_JOB, "信息中心技术岗", "杭州市数字事业中心", null, null),
                new AgentTooling.JobRef(2, SECOND_JOB, "档案管理岗", "杭州市数字事业中心", null, null)),
            List.of(new AgentTooling.PendingRef("POLITICAL_AFFILIATION", "候选人政治面貌尚未确认", FIRST_JOB)),
            "profile-1");
    }
}
