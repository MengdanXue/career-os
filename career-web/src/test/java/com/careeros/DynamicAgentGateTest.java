package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.careeros.application.DecisionExplanationService;
import com.careeros.application.DecisionIntelligenceService;
import com.careeros.application.DecisionRankingService;
import com.careeros.application.agent.AgentExecutor;
import com.careeros.application.agent.AgentTooling.AgentPlanner;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DynamicAgentGateTest {
    private ApplicationContextRunner configuration() {
        return new ApplicationContextRunner()
            .withUserConfiguration(DecisionAgentConfiguration.class, ScriptedPlannerConfiguration.class)
            .withBean(DecisionRankingService.class, () -> mock(DecisionRankingService.class))
            .withBean(DecisionIntelligenceService.class, () -> mock(DecisionIntelligenceService.class))
            .withBean(DecisionExplanationService.class, DecisionExplanationService::new)
            .withBean(ChatClient.Builder.class, () -> {
                var builder = mock(ChatClient.Builder.class);
                when(builder.build()).thenReturn(mock(ChatClient.class));
                return builder;
            });
    }

    @Test void legacyLlmFlagDoesNotEnableDynamicPlanning() {
        configuration().withPropertyValues("career-os.agent.llm.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(AgentPlanner.class));
    }

    @Test void scriptedPlannerNeedsTheAcceptanceProfileAndBothExplicitGates() {
        configuration().withPropertyValues("career-os.agent.planner=scripted",
                "career-os.agent.dynamic-tools.enabled=true", "career-os.agent.acceptance.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(AgentPlanner.class));
        configuration().withPropertyValues("spring.profiles.active=agent-acceptance", "career-os.agent.planner=scripted",
                "career-os.agent.acceptance.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(AgentPlanner.class));
        configuration().withPropertyValues("spring.profiles.active=agent-acceptance", "career-os.agent.planner=scripted",
                "career-os.agent.dynamic-tools.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(AgentPlanner.class));
    }

    @Test void theAcceptanceFixtureCanStillBeEnabledOnlyByAllExplicitSettings() {
        configuration().withPropertyValues("spring.profiles.active=agent-acceptance", "career-os.agent.planner=scripted",
                "career-os.agent.dynamic-tools.enabled=true", "career-os.agent.acceptance.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(AgentPlanner.class));
    }

    @Test void endpointDefaultsClosedEvenIfAnotherConfigurationProvidesAPlanner() {
        var executor = mock(AgentExecutor.class);
        var planner = mock(AgentPlanner.class);
        new ApplicationContextRunner().withUserConfiguration(AgentRunController.class)
            .withBean(AgentExecutor.class, () -> executor).withBean(AgentPlanner.class, () -> planner)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThatThrownBy(() -> context.getBean(AgentRunController.class).run(UUID.randomUUID(),
                    new AgentRunController.AgentRunRequest("杭州的岗位")))
                    .isInstanceOf(AgentRunController.PlannerUnavailableException.class);
                verifyNoInteractions(executor, planner);
            });
    }
}
