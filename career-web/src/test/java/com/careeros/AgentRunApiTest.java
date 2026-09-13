package com.careeros;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.agent.AgentExecutor;
import com.careeros.application.agent.AgentTooling.AgentPlanner;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.agent.AgentTooling.ToolCall;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentRunApiTest {
    private static final UUID CANDIDATE = UUID.randomUUID();

    private static ReadOnlyTool tool(String name, Map<String, Object> data) {
        return new ReadOnlyTool() {
            public String name() { return name; }
            public String description() { return name; }
            public Observation invoke(UUID candidateId, ToolCall call) {
                return Observation.ok(name, name + " ok", data);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentPlanner> provider(AgentPlanner planner) {
        var provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(planner);
        return provider;
    }

    private static org.springframework.test.web.servlet.MockMvc mvc(AgentPlanner planner) {
        var executor = new AgentExecutor(List.of(tool("search_jobs", Map.of("count", 1))));
        return MockMvcBuilders.standaloneSetup(new AgentRunController(executor, provider(planner)))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    /**
     * 没有规划器就明说，不退化成写死的流程。
     *
     * <p>固定顺序的实现摆在这个接口后面，会被当成"Agent 能自己选工具"——
     * 那正是"不要把硬编码流程叫作 Agent"要防的事。
     */
    @Test void withoutAPlannerTheEndpointRefusesInsteadOfFallingBackToAFixedFlow() throws Exception {
        mvc(null).perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"杭州有哪些岗位\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("PLANNER_UNAVAILABLE"));
    }

    /** 可用工具要外露：调用方能核对边界，而不是只能相信它是只读的。 */
    @Test void theResponseExposesWhichToolsWereAvailable() throws Exception {
        mvc(state -> new PlannerStep.Finish("下面按稳定性排序。"))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"杭州有哪些岗位\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registeredTools[0]").value("search_jobs"))
            .andExpect(jsonPath("$.outcome").value("FINISHED"));
    }

    /** 被拒的步骤也要出现在轨迹里——看不见的拦截等于没拦截。 */
    @Test void theTraceShowsRefusedToolRequests() throws Exception {
        mvc(state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("update_profile", "value", "X"), "想改资料")
            : new PlannerStep.Finish("我不能替你修改资料。"))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"改一下我的资料\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trace[0].tool").value("update_profile"))
            .andExpect(jsonPath("$.trace[0].accepted").value(false))
            .andExpect(jsonPath("$.toolCallsSpent").value(0));
    }

    /** 叙述带判定词时不返回叙述，只给拒绝原因，由调用方回落到确定性事实块。 */
    @Test void aRejectedNarrativeIsNotReturned() throws Exception {
        mvc(state -> new PlannerStep.Finish("这个岗位你是可报的，适配 72 分。"))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"怎么样\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.narrative").doesNotExist())
            .andExpect(jsonPath("$.narrativeViolations").isNotEmpty());
    }
}
