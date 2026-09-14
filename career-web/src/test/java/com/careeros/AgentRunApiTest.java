package com.careeros;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.AgentSession;
import com.careeros.application.AgentSessionService;
import com.careeros.application.agent.AgentExecutor;
import com.careeros.application.agent.AgentTooling;
import com.careeros.application.agent.AgentTooling.AgentPlanner;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.agent.AgentTooling.ToolCall;
import com.careeros.application.agent.AgentTooling.ToolParameter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentRunApiTest {
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    private static ReadOnlyTool tool(String name, Map<String, Object> data) {
        return new ReadOnlyTool() {
            public String name() { return name; }
            public String description() { return name + " 的说明"; }
            public List<ToolParameter> parameters() {
                return List.of(ToolParameter.oneOf("tier", "机会分层", List.of("T1", "T2", "T3")));
            }
            public Observation invoke(AgentTooling.ToolContext context) {
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

    private static AgentSessionService sessions(AgentSession owned) {
        var sessions = org.mockito.Mockito.mock(AgentSessionService.class);
        org.mockito.Mockito.when(sessions.find(any(), any())).thenReturn(Optional.ofNullable(owned));
        return sessions;
    }

    private static AgentSession session() {
        return new AgentSession(SESSION, CANDIDATE, new AgentSession.SessionFilters(null, null, null, 5),
            List.of(), List.of(), "profile-7", Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static org.springframework.test.web.servlet.MockMvc mvc(AgentPlanner planner) {
        return mvc(planner, sessions(session()));
    }

    private static org.springframework.test.web.servlet.MockMvc mvc(AgentPlanner planner, AgentSessionService s) {
        var executor = new AgentExecutor(List.of(tool("search_jobs", Map.of("count", 1))));
        return MockMvcBuilders.standaloneSetup(new AgentRunController(executor, provider(planner), s))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static String body(String question, UUID sessionId) {
        return "{\"question\":\"" + question + "\""
            + (sessionId == null ? "" : ",\"sessionId\":\"" + sessionId + "\"") + "}";
    }

    /**
     * 没有规划器就明说，不退化成写死的流程。
     *
     * <p>固定顺序的实现摆在这个接口后面，会被当成"Agent 能自己选工具"——
     * 那正是"不要把硬编码流程叫作 Agent"要防的事。
     */
    @Test void withoutAPlannerTheEndpointRefusesInsteadOfFallingBackToAFixedFlow() throws Exception {
        mvc(null).perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("杭州有哪些岗位", null)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("PLANNER_UNAVAILABLE"));
    }

    /** 工具目录要外露到参数一级：调用方能核对边界，而不是只能相信它是只读的。 */
    @Test void theResponseExposesTheToolCatalogueDownToItsParameters() throws Exception {
        mvc(state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("下面按稳定性排序。"))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("杭州有哪些岗位", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tools[0].name").value("search_jobs"))
            .andExpect(jsonPath("$.tools[0].parameters[0].name").value("tier"))
            .andExpect(jsonPath("$.tools[0].parameters[0].allowedValues[0]").value("T1"))
            .andExpect(jsonPath("$.outcome").value("FINISHED"));
    }

    /** 被拒的步骤也要出现在轨迹里——看不见的拦截等于没拦截。 */
    @Test void theTraceShowsRefusedToolRequests() throws Exception {
        mvc(state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("update_profile", "value", "X"), "想改资料")
            : new PlannerStep.Finish("我不能替你修改资料。"))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("改一下我的资料", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trace[0].tool").value("update_profile"))
            .andExpect(jsonPath("$.trace[0].accepted").value(false))
            .andExpect(jsonPath("$.budgetSpent").value(0));
    }

    /** 叙述带判定词时不返回叙述，只给拒绝原因，由调用方回落到确定性事实块。 */
    @Test void aRejectedNarrativeIsNotReturned() throws Exception {
        mvc(state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("这个岗位你是可报的，适配 72 分。"))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("怎么样", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.narrative").doesNotExist())
            .andExpect(jsonPath("$.violations").isNotEmpty());
    }

    /** 预算要带着上限一起报，否则"花了 13"没有参照，看不出还剩多少。 */
    @Test void theResponseReportsTheBudgetAgainstItsLimit() throws Exception {
        mvc(state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("下面按稳定性排序。"))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("杭州有哪些岗位", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.budgetSpent").value(1))
            .andExpect(jsonPath("$.budgetLimit").value(50))
            .andExpect(jsonPath("$.groundedIn").value(1));
    }

    /** 续跑要带上会话记着的资料版本，页面回答待确认问题时用得上。 */
    @Test void continuingASessionReturnsItsProfileVersion() throws Exception {
        mvc(state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("下面按稳定性排序。"))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("还有别的吗", SESSION)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(SESSION.toString()))
            .andExpect(jsonPath("$.profileVersion").value("profile-7"));
    }

    /**
     * 别人的会话 ID 续跑不了。
     *
     * <p>会话 ID 是可猜的 UUID。不核对归属，报出别人的会话 ID 就能沿用别人那一轮的上下文；
     * 而且"不存在"和"不是你的"要返回同一个 404，否则枚举一遍就知道哪些会话存在。
     */
    @Test void aSessionBelongingToSomeoneElseIsNotFound() throws Exception {
        mvc(state -> new PlannerStep.Finish("好的。"), sessions(null))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("还有别的吗", SESSION)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }
}
