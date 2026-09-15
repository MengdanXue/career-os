package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.careeros.application.DecisionRankingService;
import com.careeros.application.ToolCallBudget;
import com.careeros.application.agent.AgentExecutor;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.PlanningState;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.personal.JobWatchlistPorts;
import com.careeros.application.personal.JobWatchlistService;
import com.careeros.application.agent.AgentTooling.SessionContext;
import com.careeros.application.agent.AgentTooling.ToolContext;
import com.careeros.application.agent.ModelPlanner;
import com.careeros.application.agent.ReadOnlyTools;
import com.careeros.domain.AnswerNarrativeValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 冻结小样本对照：真实模型在固定输入下会不会依据不同工具结果选择不同下一步。
 *
 * <p><b>这是本轮唯一还缺的证据。</b> 回放规划器与脚本规划器都不是模型——
 * 前者的输出事先写死，后者的分支事先写死，它们证明的是"路径接通、边界守得住"，
 * 不是"模型会选工具"。把它们当成模型证据，就是把硬编码流程叫作 Agent。
 *
 * <p>四条约束是刻意的：
 * <ul>
 *   <li><b>目录从实际注册表生成。</b> 手写一份目录就是在测一份文档，不是测线上那份。
 *       这里的目录来自真实的 {@link ReadOnlyTools} 与 {@link AgentExecutor}，
 *       提示里写着能填什么，工具就按什么校验。</li>
 *   <li><b>动作和参数必须有效。</b> 只看"它回了点什么"没有意义。模型选的工具要在注册表里，
 *       参数要真的能过工具自己的校验；收尾要点名依据。每个样本都过同一套检查。</li>
 *   <li><b>判据与测试名一致。</b> 名字说"大多数通过"，断言就得是大多数，不能是"至少一条通过"。</li>
 *   <li><b>模型调用有明确时限。</b> 走与线上同一条 {@code boundedTurn}：超时、失败、
 *       线程池满都返回空串，测试看到的是"这一步没有计划"，而不是挂在那里。</li>
 * </ul>
 *
 * <p>输入不走数据库：要测的是模型的选择，不是数据层。状态用 {@link PlanningState} 手工冻结，
 * 温度置零，所以同一份输入可以反复比对。
 *
 * <p>默认不跑：{@code @Tag("llm-integration")} 被 surefire 排除。跑法：
 * <pre>
 *   OPENAI_API_KEY=...              # 供应商的 key
 *   CAREER_OS_AI_BASE_URL=...       # OpenAI 兼容端点，例如 https://api.deepseek.com
 *   CAREER_OS_AI_MODEL=...          # 例如 deepseek-chat
 *   mvn -P llm-integration -pl career-web test -Dtest=ModelPlannerLiveSampleTest
 * </pre>
 */
@Tag("llm-integration")
class ModelPlannerLiveSampleTest {

    private static final AnswerNarrativeValidator NARRATIVE = new AnswerNarrativeValidator();
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final UUID FIRST_JOB = UUID.randomUUID();
    private static final UUID SECOND_JOB = UUID.randomUUID();
    private static final String QUESTION = "杭州有哪些岗位";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);

    /** 与线上同一批工具。目录、参数定义和参数校验都从这里来，不另写一份。 */
    private static final List<ReadOnlyTool> TOOLS = List.of(
        ReadOnlyTools.searchJobs((candidateId, query, now, budget) ->
            new DecisionRankingService.RankingPage(List.of(), 0, 5, 0), CLOCK),
        ReadOnlyTools.jobFacts((candidateId, jobId, now) -> {
            throw new IllegalStateException("样本只检查参数是否有效，不需要真的评估");
        }, CLOCK),
        ReadOnlyTools.pendingConfirmations((candidateId, budget) ->
            new ReadOnlyTools.PendingList(List.of(), true)),
        // 关注清单要给一个真的服务：ReadOnlyTools.watchlist 对 null 是直接抛 NPE 的。
        // 这里原来传 null，于是整个类的静态初始化就炸了——四条样本一条都到不了 assumeTrue，
        // 全部报 ExceptionInInitializerError。因为这个类默认被 surefire 排除，从来没人跑过它，
        // 也就一直没人发现。"默认不跑"等于"从没验过"。
        ReadOnlyTools.watchlist(emptyWatchlist(), CLOCK));

    /**
     * 一份空的关注清单服务。
     *
     * <p>样本要验的是模型怎么选工具，不是关注清单本身，所以端口给的是空实现。
     * 但它必须是个真对象——工具目录是从这批工具生成的，少一个工具，
     * 发给模型的目录就和线上不一样，样本量的就不再是线上那套工具面。
     */
    private static JobWatchlistService emptyWatchlist() {
        return new JobWatchlistService(new JobWatchlistPorts.Watchlist() {
            public List<JobWatchlistPorts.WatchedJob> findByCandidate(UUID candidateId) { return List.of(); }
            public java.util.Optional<JobWatchlistPorts.WatchedJob> find(UUID candidateId, UUID jobPostingId) {
                return java.util.Optional.empty();
            }
            public JobWatchlistPorts.WatchedJob save(JobWatchlistPorts.WatchedJob entry) { return entry; }
            public void remove(UUID candidateId, UUID jobPostingId) {}
        }, (candidateId, jobId, now) -> {
            throw new IllegalStateException("样本只检查参数是否有效，不需要真的评估");
        }, CLOCK);
    }

    private static final AgentExecutor EXECUTOR = new AgentExecutor(TOOLS);

    /**
     * 给 {@code ModelPlannerLiveSampleFixtureTest} 用：这个类默认被排除，坏了没人知道。
     * 那一条不需要 key 也不需要出网，只把这个类加载起来、核对目录还是线上那四个。
     */
    static List<String> toolNamesForFixtureCheck() { return EXECUTOR.registeredTools(); }

    /** 模板清单从执行器取，和发给模型的那份同源——不另抄一份。 */
    private static final List<String> CLOSING_TEMPLATES = EXECUTOR.closingTemplates();
    private static final List<String> QUESTION_TEMPLATES = EXECUTOR.questionTemplates();

    /** 模型往返走与线上同一条有界通道，测试不会挂在一次不返回的调用上。 */
    private static final java.util.concurrent.ThreadPoolExecutor POOL =
        DecisionAgentConfiguration.boundedModelTurnPool();

    @AfterAll static void shutdown() { POOL.shutdownNow(); }

    private static ModelPlanner planner() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is not configured");
        String baseUrl = System.getenv().getOrDefault("CAREER_OS_AI_BASE_URL", "https://api.openai.com");
        String modelName = System.getenv().getOrDefault("CAREER_OS_AI_MODEL", "gpt-5-mini");
        var api = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl).build();
        var model = OpenAiChatModel.builder()
            .openAiApi(api)
            // 温度置零：冻结样本要能反复比对，采样噪声会把"模型选了别的路"和"这次抽到别的词"混在一起。
            .defaultOptions(OpenAiChatOptions.builder().model(modelName).temperature(0.0).build())
            .build();
        var client = ChatClient.builder(model).build();
        return new ModelPlanner((protocol, state) -> DecisionAgentConfiguration.boundedTurn(
            POOL, DecisionAgentConfiguration.MODEL_TURN_DEADLINE,
            () -> client.prompt().system(protocol).user(state).call().content()));
    }

    /**
     * 最小判据：同一个问题、同一份工具目录，只有工具结果不同，下一步必须不同。
     *
     * <p>写死流程的实现在这两种输入下会调同一串工具。这条不通过，就没有理由把它叫作 Agent。
     */
    @Test void theModelTakesADifferentNextStepWhenTheToolResultDiffers() {
        var planner = planner();

        PlannerStep withJobs = planner.next(state(2, 5));
        PlannerStep empty = planner.next(state(0, 5));

        report("有岗位", withJobs);
        report("无岗位", empty);
        assertUsable("有岗位", withJobs, 1);
        assertUsable("无岗位", empty, 1);
        assertThat(describe(withJobs))
            .as("两种工具结果得到同一个下一步，那就是在走固定流程")
            .isNotEqualTo(describe(empty));
    }

    /**
     * 非法参数被拒之后，模型要改用合法取值，而不是把同一个非法调用再报一遍。
     *
     * <p>反复撞同一堵墙说明它没在读观察——那就只是在重复固定动作。
     */
    @Test void theModelCorrectsAnIllegalArgumentInsteadOfRepeatingIt() {
        var planner = planner();
        var refused = Observation.failed("search_jobs",
            "参数「tier」的取值「T9」不在允许的范围里。可选：T1、T2、T3、EXCLUDED。");

        PlannerStep next = planner.next(new PlanningState(QUESTION, List.of(refused), 5,
            EXECUTOR.toolCatalogue(), SessionContext.none()));

        report("被拒之后", next);
        // 有效性检查本身就覆盖了"又报了一次 T9"：那样的调用过不了工具的参数校验。
        assertUsable("被拒之后", next, 0);
    }

    /** 预算将尽时要自己收敛，而不是一直要求调工具直到撞上限。 */
    @Test void theModelConvergesWhenTheBudgetIsNearlyGone() {
        var planner = planner();

        PlannerStep next = planner.next(state(2, 1));

        report("预算剩一个", next);
        assertThat(next)
            .as("剩余预算只够一次调用时仍要求调工具")
            .isNotInstanceOf(PlannerStep.CallTool.class);
        assertUsable("预算剩一个", next, 1);
    }

    /**
     * 模型选的模板要和工具结果对得上。
     *
     * <p>原来这一条量的是"模型写的收尾有多少违规"。现在句子由程序按模板渲染，模型写不出句子，
     * 那个比例量的就不再是模型的行为了——留着它只会给出一个恒为零的好看数字。
     *
     * <p>换成真正还由模型决定的那件事：一个岗位都没查到时，它不能选"下面是这个范围内的岗位"。
     * 选错模板和写错句子一样会让用户看到一句不成立的话，只是这次错在选择上，可以直接核对。
     */
    @Test void theModelPicksAClosingTemplateThatMatchesTheToolResult() {
        var planner = planner();

        PlannerStep empty = planner.next(state(0, 5));

        report("无岗位", empty);
        assertUsable("无岗位", empty, 1);
        if (empty instanceof PlannerStep.Finish finish) {
            assertThat(finish.template())
                .as("一个岗位都没查到，却选了讲岗位列表的模板")
                .isNotIn("RANKED_LISTING", "SINGLE_JOB", "WATCHLIST_STATE");
        }
    }

    /**
     * 这一步必须是能真的执行的：工具在注册表里、参数过得了工具自己的校验；
     * 收尾要点名依据，而且点到的必须是成功的观察。
     *
     * @param successfulObservations 这份冻结状态里有几条成功的观察，用来核对 BASIS 的范围
     */
    private static void assertUsable(String label, PlannerStep step, int successfulObservations) {
        assertThat(step).as("%s：模型没有给出可解析的一步", label).isNotNull();
        if (step instanceof PlannerStep.CallTool call) {
            String name = call.call().tool();
            assertThat(EXECUTOR.registeredTools())
                .as("%s：选了一个不存在的工具 %s", label, name).contains(name);
            var tool = TOOLS.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
            Observation observation;
            try {
                observation = tool.invoke(new ToolContext(CANDIDATE, call.call(),
                    ToolCallBudget.standard(), session()));
            } catch (RuntimeException blewUp) {
                // 下游被这里刻意打断（job_facts 的假评估器），参数本身没问题。
                return;
            }
            assertThat(observation.ok())
                .as("%s：参数过不了工具自己的校验——%s", label, observation.summary()).isTrue();
            return;
        }
        if (step instanceof PlannerStep.Finish finish) {
            // 模板名必须在封闭清单里，否则这句话渲染不出来。
            assertThat(CLOSING_TEMPLATES).as("%s：收尾用了不存在的模板 %s", label, finish.template())
                .contains(finish.template());
            assertThat(finish.basis().observationIndexes())
                .as("%s：收尾没有点名依据", label).isNotEmpty();
            assertThat(finish.basis().observationIndexes())
                .as("%s：收尾点名的依据超出了这份状态里的成功结果", label)
                .allMatch(index -> index >= 1 && index <= successfulObservations);
            return;
        }
        var ask = (PlannerStep.AskUser) step;
        assertThat(QUESTION_TEMPLATES).as("%s：追问用了不存在的模板 %s", label, ask.template())
            .contains(ask.template());
        assertThat(ask.basis().declared()).as("%s：追问既没点名依据也没声明是纯澄清", label).isTrue();
    }

    private static void report(String label, PlannerStep step) {
        System.out.println("[frozen-sample] " + label + " -> " + describe(step));
    }

    /** 冻结的规划状态。除了工具结果与剩余预算，其余完全相同；目录来自真实注册表。 */
    private static PlanningState state(int jobCount, int remainingBudget) {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("count", jobCount);
        data.put("totalAvailable", jobCount);
        data.put("complete", true);
        data.put("jobs", jobCount == 0 ? List.of() : List.of(
            Map.of("jobPostingId", FIRST_JOB.toString(),
                "jobTitle", "信息中心技术岗", "organizationName", "杭州市数字事业中心",
                "eligibilityStatus", "NEEDS_CONFIRMATION", "tier", "T3"),
            Map.of("jobPostingId", SECOND_JOB.toString(),
                "jobTitle", "档案管理岗", "organizationName", "杭州市数字事业中心",
                "eligibilityStatus", "NEEDS_CONFIRMATION", "tier", "T3")));
        var observation = Observation.ok("search_jobs",
            jobCount == 0 ? "这个范围内没有岗位。" : "找到 " + jobCount + " 个岗位。", data);
        return new PlanningState(QUESTION, List.of(observation), remainingBudget,
            EXECUTOR.toolCatalogue(), session());
    }

    private static SessionContext session() {
        return new SessionContext(UUID.randomUUID(), "杭州", null, null,
            List.of(new com.careeros.application.agent.AgentTooling.JobRef(
                    1, FIRST_JOB, "信息中心技术岗", "杭州市数字事业中心", null, null),
                new com.careeros.application.agent.AgentTooling.JobRef(
                    2, SECOND_JOB, "档案管理岗", "杭州市数字事业中心", null, null)),
            List.of(), "profile-1");
    }

    /** 把一步规划压成可比较的一行。比的是"选了什么"，不是措辞。 */
    private static String describe(PlannerStep step) {
        if (step instanceof PlannerStep.CallTool call) {
            return "TOOL " + call.call().tool() + " " + new java.util.TreeMap<>(call.call().arguments());
        }
        if (step instanceof PlannerStep.Finish finish) {
            return "FINISH " + finish.template() + " basis=" + finish.basis().observationIndexes();
        }
        if (step instanceof PlannerStep.AskUser ask) {
            return "ASK " + ask.template()
                + (ask.basis().clarifyingOnly() ? " clarifying" : " basis=" + ask.basis().observationIndexes());
        }
        return "(none)";
    }
}
