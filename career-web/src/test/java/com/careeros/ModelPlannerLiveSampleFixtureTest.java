package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.agent.AgentExecutor;
import com.careeros.application.agent.AgentTooling;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.PlanningState;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.agent.AgentTooling.SessionContext;
import com.careeros.application.agent.AgentTooling.ToolCall;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 冻结样本那个类<b>至少要能加载起来</b>。
 *
 * <p>为什么单独立一条：{@code ModelPlannerLiveSampleTest} 带着
 * {@code @Tag("llm-integration")}，默认被 surefire 排除，所以它坏了没有任何人会知道。
 * 它确实坏过——静态字段里给 {@code ReadOnlyTools.watchlist} 传了 null，
 * 那个方法对 null 直接抛 NPE，于是整个类的静态初始化就炸了：
 * 四条样本一条都到不了 {@code assumeTrue}，全部报 ExceptionInInitializerError。
 * 这个毛病一直带到冻结版本 6dc7f69，是在准备联网跑样本时才被发现的。
 *
 * <p>"默认不跑"等于"从没验过"。这一条不需要 key、不需要出网，只做一件事：
 * 把那个类初始化起来，并核对它的工具目录还是线上那四个。
 * 目录少一个工具，发给模型的那份就和线上不一样，样本量的就不再是线上那套工具面。
 */
class ModelPlannerLiveSampleFixtureTest {

    @Test void theFrozenSampleClassLoadsWithoutABlowUp() {
        assertThatCode(() -> Class.forName(ModelPlannerLiveSampleTest.class.getName(), true,
            ModelPlannerLiveSampleTest.class.getClassLoader()))
            .as("冻结样本类连静态初始化都过不去，四条样本一条也跑不了")
            .doesNotThrowAnyException();
    }

    @Test void theSampleToolCatalogueIsTheSameFourToolsAsProduction() {
        assertThat(ModelPlannerLiveSampleTest.toolNamesForFixtureCheck())
            .containsExactly("search_jobs", "job_facts", "pending_confirmations", "watchlist");
    }

    // --- 判据本身要能被测：它藏在一个默认不跑的类里，写松了没人会知道 ---

    private static final UUID CANDIDATE = UUID.randomUUID();

    /** 冻结样本那份状态：一次 search_jobs，查完了，jobCount 个岗位。 */
    private static PlanningState state(int jobCount, List<ReadOnlyTool> tools) {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("count", jobCount);
        data.put("complete", true);
        data.put("notAssessed", 0);
        data.put("jobs", List.of());
        var observation = Observation.ok("search_jobs",
            jobCount == 0 ? "这个范围内没有岗位。" : "找到 " + jobCount + " 个岗位。", data);
        return new PlanningState("杭州有哪些岗位", List.of(observation), 5,
            new AgentExecutor(tools).toolCatalogue(), SessionContext.none());
    }

    private static ReadOnlyTool throwing(String name, RuntimeException failure) {
        return new ReadOnlyTool() {
            public String name() { return name; }
            public String description() { return name; }
            public Observation invoke(AgentTooling.ToolContext context) { throw failure; }
        };
    }

    /**
     * 工具炸了不能算"参数没问题"。
     *
     * <p>原来这里 catch 的是 {@code RuntimeException}，catch 到就 return——理由是
     * 假的 job_facts 评估器会刻意打断下游。可那一句同时把<b>所有</b>异常都算成了通过：
     * search_jobs 里一个 NPE、目录渲染里一个 IndexOutOfBounds，样本照样绿。
     * 判据要容忍的是"我们自己埋的那个停止点"，不是"任何炸法"。
     */
    @Test void aPlainRuntimeExceptionFromAToolIsAFailureNotAPass() {
        var tools = List.of(throwing("search_jobs", new RuntimeException("工具内部炸了")));
        var executor = new AgentExecutor(tools);
        var step = new PlannerStep.CallTool(ToolCall.of("search_jobs", "location", "杭州"), "先查");

        assertThatThrownBy(() -> FrozenSampleChecks.assertUsable(
            "普通异常", step, state(2, tools), tools, executor, CANDIDATE))
            .as("工具抛了一个普通 RuntimeException，判据却算它通过")
            .isInstanceOf(AssertionError.class);
    }

    /** 我们自己埋的停止点仍然要放过去——守护用例，收紧不能把它一起拦掉。 */
    @Test void theDedicatedSampleStopIsStillTolerated() {
        var tools = List.of(throwing("search_jobs",
            new FrozenSampleChecks.SampleStop("样本只检查参数是否有效")));
        var executor = new AgentExecutor(tools);
        var step = new PlannerStep.CallTool(ToolCall.of("search_jobs", "location", "杭州"), "先查");

        assertThatCode(() -> FrozenSampleChecks.assertUsable(
            "专用停止", step, state(2, tools), tools, executor, CANDIDATE))
            .doesNotThrowAnyException();
    }

    /**
     * 模板名合法、BASIS 也在范围内，不等于这一步在生产里成立。
     *
     * <p>一次查完了、一个岗位都没查到的 search_jobs，撑不起"还有资料项没有确认"——
     * 那句话要的是一份非空的待确认清单。执行器在线上会拒掉它，
     * 而样本判据只查模板名在不在清单里、BASIS 有没有越界，两项都过，于是算它通过。
     * 判据必须走执行器那一份适用条件，不能自己另立一套松的。
     */
    @Test void anIrrelevantButLegalTemplateOnAnEmptySearchIsAFailure() {
        var tools = ModelPlannerLiveSampleTest.toolsForFixtureCheck();
        var executor = new AgentExecutor(tools);
        var step = new PlannerStep.Finish("PENDING_FIRST", AgentTooling.Basis.on(1));

        assertThatThrownBy(() -> FrozenSampleChecks.assertUsable(
            "空搜索", step, state(0, tools), tools, executor, CANDIDATE))
            .as("空搜索撑不起「还有资料项没有确认」，判据却算它通过")
            .isInstanceOf(AssertionError.class);
    }

    /** 空搜索选对了模板照常通过——守护用例。 */
    @Test void theTemplateThatDoesFitAnEmptySearchStillPasses() {
        var tools = ModelPlannerLiveSampleTest.toolsForFixtureCheck();
        var executor = new AgentExecutor(tools);
        var step = new PlannerStep.Finish("NOTHING_IN_SCOPE", AgentTooling.Basis.on(1));

        assertThatCode(() -> FrozenSampleChecks.assertUsable(
            "空搜索", step, state(0, tools), tools, executor, CANDIDATE))
            .doesNotThrowAnyException();
    }
}
