package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.PlanningState;
import com.careeros.application.agent.AgentTooling.ToolParameter;
import com.careeros.application.agent.AgentTooling.ToolSpec;
import com.careeros.application.agent.ModelPlanner;
import com.careeros.domain.AnswerNarrativeValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * <p>输入刻意不走数据库：这里要测的是模型的选择，不是数据层。
 * 状态用 {@link PlanningState} 手工冻结，工具目录与线上同源（同一套 {@link ToolSpec}），
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

    /** 与线上注册表同源的工具目录。提示里写着能填什么，代码就认什么。 */
    private static final List<ToolSpec> TOOLS = List.of(
        new ToolSpec("search_jobs", "按城市、职位类别、机会分层查岗位，返回排好序的一页结果。", List.of(
            ToolParameter.optional("location", "城市或地区名，按包含匹配，例如 杭州"),
            ToolParameter.oneOf("tier", "机会分层", List.of("T1", "T2", "T3", "EXCLUDED")),
            ToolParameter.optional("limit", "返回几个岗位，1 到 10，默认 5"))),
        new ToolSpec("job_facts", "取一个岗位的硬资格、限制条件与截止日。", List.of(
            ToolParameter.required("jobId", "岗位的 UUID，取自 search_jobs 或关注清单的结果"))),
        new ToolSpec("pending_confirmations", "列出还需要用户确认的资料项，以及是哪个岗位提出的。", List.of()),
        new ToolSpec("watchlist", "列出用户关注的岗位，以及自上次查看以来的变化。", List.of()));

    private static final String QUESTION = "杭州有哪些岗位";

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
        return new ModelPlanner((protocol, state) ->
            client.prompt().system(protocol).user(state).call().content());
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

        System.out.println("[frozen-sample] 有岗位 -> " + describe(withJobs));
        System.out.println("[frozen-sample] 无岗位 -> " + describe(empty));

        assertThat(withJobs).isNotNull();
        assertThat(empty).isNotNull();
        assertThat(describe(withJobs)).isNotEqualTo(describe(empty));
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

        PlannerStep next = planner.next(new PlanningState(QUESTION, List.of(refused), 5, TOOLS));

        System.out.println("[frozen-sample] 被拒之后 -> " + describe(next));
        assertThat(next).isNotNull();
        if (next instanceof PlannerStep.CallTool call) {
            String tier = call.call().argument("tier");
            assertThat(tier == null || List.of("T1", "T2", "T3", "EXCLUDED").contains(tier.toUpperCase()))
                .as("被拒之后又报了一次非法分层：%s", tier).isTrue();
        }
    }

    /** 预算将尽时要自己收敛，而不是一直要求调工具直到撞上限。 */
    @Test void theModelConvergesWhenTheBudgetIsNearlyGone() {
        var planner = planner();

        PlannerStep next = planner.next(state(2, 1));

        System.out.println("[frozen-sample] 预算剩一个 -> " + describe(next));
        assertThat(next)
            .as("剩余预算只够一次调用时仍要求调工具")
            .isNotInstanceOf(PlannerStep.CallTool.class);
    }

    /**
     * 收尾文字的违规率。提示词与叙述校验器不一致时，回答会次次回落到事实块，
     * 用户看到的东西变差，而测试全绿——所以这条要用真实模型量一次。
     */
    @Test void mostClosingTextsPassTheNarrativeRules() {
        var planner = planner();
        var texts = new ArrayList<String>();
        for (PlanningState state : List.of(state(2, 5), state(0, 5), state(2, 1))) {
            PlannerStep step = planner.next(state);
            if (step instanceof PlannerStep.Finish finish) texts.add(finish.narrative());
            if (step instanceof PlannerStep.AskUser ask) texts.add(ask.question());
        }
        assumeTrue(!texts.isEmpty(), "这批样本里模型一次都没有收尾，没有可量的文字");

        long violating = texts.stream().filter(text -> !NARRATIVE.validateNarrative(text).accepted()).count();
        texts.forEach(text -> System.out.println("[frozen-sample] 收尾文字：" + text
            + " -> " + NARRATIVE.validateNarrative(text).violations()));

        assertThat(violating)
            .as("收尾文字几乎全部违规，说明提示词和叙述校验器已经对不上")
            .isLessThan(texts.size());
    }

    /** 冻结的规划状态。除了工具结果与剩余预算，其余完全相同。 */
    private static PlanningState state(int jobCount, int remainingBudget) {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("count", jobCount);
        data.put("totalAvailable", jobCount);
        data.put("complete", true);
        data.put("jobs", jobCount == 0 ? List.of() : List.of(
            Map.of("jobPostingId", "c0000000-0000-4000-8000-000000000001",
                "jobTitle", "信息中心技术岗", "organizationName", "杭州市数字事业中心",
                "eligibilityStatus", "NEEDS_CONFIRMATION", "tier", "T3"),
            Map.of("jobPostingId", "c0000000-0000-4000-8000-000000000002",
                "jobTitle", "档案管理岗", "organizationName", "杭州市数字事业中心",
                "eligibilityStatus", "NEEDS_CONFIRMATION", "tier", "T3")));
        var observation = Observation.ok("search_jobs",
            jobCount == 0 ? "这个范围内没有岗位。" : "找到 " + jobCount + " 个岗位。", data);
        return new PlanningState(QUESTION, List.of(observation), remainingBudget, TOOLS);
    }

    /** 把一步规划压成可比较的一行。比的是"选了什么"，不是措辞。 */
    private static String describe(PlannerStep step) {
        if (step instanceof PlannerStep.CallTool call) {
            return "TOOL " + call.call().tool() + " " + new java.util.TreeMap<>(call.call().arguments());
        }
        if (step instanceof PlannerStep.Finish) return "FINISH";
        if (step instanceof PlannerStep.AskUser) return "ASK";
        return "(none)";
    }
}
