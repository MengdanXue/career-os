package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.agent.AgentTooling.AgentPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * 三个规划器的开关不能同时成立。
 *
 * <p>真实模型的那个 bean 挂在 {@code career-os.agent.llm.enabled=true} 上，
 * 回放和脚本的两个挂在 {@code career-os.agent.planner=recorded|scripted} 上——
 * 这是两个**互不相干**的属性，可以同时为真。同时为真时容器里就有两个 AgentPlanner，
 * 而运行接口取 bean 用的是 {@code ObjectProvider.getIfAvailable()}，
 * 多候选时它抛 NoUniqueBeanDefinitionException：<b>每一次运行都 500</b>。
 *
 * <p>这正是"第一次把模型打开"最容易撞上的情形：验收用的 recorded 还留在配置里，
 * 然后把 llm.enabled 打开。而在这一组用例之前，这三个开关一条测试都没有。
 */
class PlannerConfigurationTest {

    /**
     * 先把后果钉住：两个 AgentPlanner 在容器里是什么下场。
     *
     * <p>这一条修复前后都是绿的——它陈述的是 Spring 的行为，不是我们的。
     * 留着是为了让下面几条的"为什么"有出处，而不是靠注释声称。
     */
    @Test void twoPlannerBeansBreakTheRunEndpointAtLookupTime() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("modelPlanner", AgentPlanner.class, () -> state -> null);
            context.registerBean("recordedPlanner", AgentPlanner.class, () -> state -> null);
            context.refresh();

            assertThatThrownBy(() -> context.getBeanProvider(AgentPlanner.class).getIfAvailable())
                .as("控制器就是这么取 planner 的；多候选时它抛异常，不是返回其中一个")
                .isInstanceOf(NoUniqueBeanDefinitionException.class);
        }
    }

    /** 回放规划器不能和真实模型同时启用——要当场说清楚，而不是等第一个请求 500。 */
    @Test void theReplayPlannerRefusesToStartWhenTheRealModelIsAlsoEnabled() {
        var environment = new MockEnvironment().withProperty("career-os.agent.llm.enabled", "true");

        assertThatThrownBy(() -> new RecordedPlannerConfiguration().recordedAcceptancePlanner(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("career-os.agent.llm.enabled")
            .hasMessageContaining("career-os.agent.planner");
    }

    /** 脚本规划器同理。 */
    @Test void theScriptedPlannerRefusesToStartWhenTheRealModelIsAlsoEnabled() {
        var environment = new MockEnvironment().withProperty("career-os.agent.llm.enabled", "true");

        assertThatThrownBy(() -> new ScriptedPlannerConfiguration().scriptedAcceptancePlanner(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("career-os.agent.llm.enabled");
    }

    /** 没开真实模型时照常给出规划器——守护用例，收紧不能把验收路径一起拦掉。 */
    @Test void theAcceptancePlannersStillStartWhenTheModelIsOff() {
        var off = new MockEnvironment().withProperty("career-os.agent.llm.enabled", "false");
        var absent = new MockEnvironment();

        assertThat(new RecordedPlannerConfiguration().recordedAcceptancePlanner(off)).isNotNull();
        assertThat(new RecordedPlannerConfiguration().recordedAcceptancePlanner(absent)).isNotNull();
        assertThatCode(() -> new ScriptedPlannerConfiguration().scriptedAcceptancePlanner(absent))
            .doesNotThrowAnyException();
    }

    /** 取值不是 "true" 的一律当没开，不做花式解析——配置项只认一个明确的开。 */
    @Test void onlyAnExplicitTrueCountsAsTheModelBeingEnabled() {
        var odd = new MockEnvironment().withProperty("career-os.agent.llm.enabled", "yes");

        assertThat(new RecordedPlannerConfiguration().recordedAcceptancePlanner(odd)).isNotNull();
    }
}
