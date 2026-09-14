package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.AgentSession;
import com.careeros.application.AgentSessionService;
import com.careeros.application.CandidateProfileService;
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

    private static final UUID FIRST_JOB = UUID.randomUUID();
    private static final UUID SECOND_JOB = UUID.randomUUID();

    private static AgentSession session() {
        return new AgentSession(SESSION, CANDIDATE, new AgentSession.SessionFilters(null, "杭州", null, 5),
            List.of(FIRST_JOB, SECOND_JOB), List.of(), "profile-7", Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static org.springframework.test.web.servlet.MockMvc mvc(AgentPlanner planner) {
        return mvc(planner, sessions(session()));
    }

    private static org.springframework.test.web.servlet.MockMvc mvc(AgentPlanner planner, AgentSessionService s) {
        var executor = new AgentExecutor(List.of(tool("search_jobs", Map.of("count", 1,
            "jobs", List.of(Map.of("jobPostingId", JOB.toString(), "jobTitle", "信息中心技术岗",
                "organizationName", "杭州市数字事业中心", "eligibilityStatus", "NEEDS_CONFIRMATION",
                "tier", "T3", "applicationEndsOn", "2026-12-01"))))));
        var profiles = org.mockito.Mockito.mock(CandidateProfileService.class);
        var profile = org.mockito.Mockito.mock(com.careeros.domain.CandidateProfile.class);
        org.mockito.Mockito.lenient().when(profile.profileVersion()).thenReturn("profile-7");
        org.mockito.Mockito.lenient().when(profiles.facts(any())).thenReturn(
            new CandidateProfileService.CandidateProfileFacts(profile, Map.of(), 0, 0, 0, false));
        return MockMvcBuilders.standaloneSetup(new AgentRunController(executor, provider(planner), s, profiles))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static final UUID JOB = UUID.randomUUID();

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
            : new PlannerStep.Finish("下面按稳定性排序。", AgentTooling.Basis.on(1)))
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
            : new PlannerStep.Finish("我不能替你修改资料。", AgentTooling.Basis.clarifying()))
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
            : new PlannerStep.Finish("这个岗位你是可报的，适配 72 分。", AgentTooling.Basis.on(1)))
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
            : new PlannerStep.Finish("下面按稳定性排序。", AgentTooling.Basis.on(1)))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("杭州有哪些岗位", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.budgetSpent").value(1))
            .andExpect(jsonPath("$.budgetLimit").value(50))
            .andExpect(jsonPath("$.groundedOn[0]").value(1));
    }

    /** 续跑要带上会话记着的资料版本，页面回答待确认问题时用得上。 */
    @Test void continuingASessionReturnsItsProfileVersion() throws Exception {
        mvc(state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("下面按稳定性排序。", AgentTooling.Basis.on(1)))
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
        mvc(state -> new PlannerStep.Finish("好的。", AgentTooling.Basis.clarifying()), sessions(null))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("还有别的吗", SESSION)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }
    /**
     * 岗位结果由程序渲染，不是从模型那段话里抠出来的。
     *
     * <p>让模型复述岗位标题和结论，就等于把事实交给它——它可以写错、写漏、张冠李戴，
     * 而读的人看不出来。这里的字段全部来自工具返回的结构化数据。
     */
    @Test void theJobsAreRenderedFromToolDataRatherThanTheModelText() throws Exception {
        mvc(state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("下面按稳定性排序。", AgentTooling.Basis.on(1)))
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("杭州有哪些岗位", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobs[0].jobPostingId").value(JOB.toString()))
            .andExpect(jsonPath("$.jobs[0].jobTitle").value("信息中心技术岗"))
            .andExpect(jsonPath("$.jobs[0].eligibilityStatus").value("NEEDS_CONFIRMATION"))
            .andExpect(jsonPath("$.jobs[0].applicationEndsOn").value("2026-12-01"));
    }

    /**
     * 续跑要把上一轮真的交给执行器，不是把 sessionId 原样回传。
     *
     * <p>这条断言的是规划器看得见上一轮的范围。只检查 sessionId 回来了，
     * 用户在追问之后只答一句"余杭"照样会被当成一个孤立的新问题。
     */
    @Test void continuingASessionHandsThePreviousScopeToThePlanner() throws Exception {
        var seenLocation = new java.util.ArrayList<String>();
        mvc(state -> {
            seenLocation.add(state.session().location());
            return new PlannerStep.Finish("好的。", AgentTooling.Basis.clarifying());
        }).perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("余杭", SESSION)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(SESSION.toString()));

        assertThat(seenLocation).containsExactly("杭州");
    }

    /** 上一轮的列表顺序也要交过去，"第二个"才有东西可指。 */
    @Test void continuingASessionHandsThePreviousListingToThePlanner() throws Exception {
        var seenJobs = new java.util.ArrayList<java.util.UUID>();
        mvc(state -> {
            state.session().jobsInOrder().forEach(job -> seenJobs.add(job.jobPostingId()));
            return new PlannerStep.Finish("好的。", AgentTooling.Basis.clarifying());
        }).perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("第二个怎么样", SESSION)))
            .andExpect(status().isOk());

        assertThat(seenJobs).containsExactly(FIRST_JOB, SECOND_JOB);
    }

    /**
     * 一轮跑完要把这一轮的新范围、新列表和还没答的问题存回会话。
     *
     * <p>只读入不写回，会话就永远停在第一轮：用户在"余杭"那一轮看到的是新列表，
     * 下一句"第二个"却解析回上一轮的杭州列表——系统一本正经地讲另一个岗位，
     * 而他看不出它换了对象。这是动态面板与旧查询接口最大的差别所在。
     */
    @Test void aRunSavesThisRoundsScopeListingAndPendingItems() throws Exception {
        var sessions = sessions(session());
        mvc(state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs", "location", "余杭"), "收窄到余杭")
            : new PlannerStep.Finish("下面按稳定性排序。", AgentTooling.Basis.on(1)), sessions)
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("余杭", SESSION)))
            .andExpect(status().isOk());

        var savedFilters = org.mockito.ArgumentCaptor.forClass(AgentSession.SessionFilters.class);
        @SuppressWarnings("unchecked")
        var savedJobs = (org.mockito.ArgumentCaptor<List<UUID>>) (org.mockito.ArgumentCaptor<?>)
            org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(sessions).rememberRun(
            org.mockito.ArgumentMatchers.eq(SESSION), org.mockito.ArgumentMatchers.eq(CANDIDATE),
            savedFilters.capture(), savedJobs.capture(), org.mockito.ArgumentMatchers.any());

        assertThat(savedFilters.getValue().location()).isEqualTo("余杭");
        assertThat(savedJobs.getValue()).containsExactly(JOB);
    }

    /**
     * 这一轮没有产生新列表时，不能把上一轮的顺序清掉。
     *
     * <p>用户问"第一个的截止日是哪天"，系统只调了 job_facts；这时候把列表清空，
     * 下一句"第二个"就没有东西可指了——上一轮明明还在屏幕上。
     */
    @Test void aRunWithoutANewListingLeavesThePreviousOrderAlone() throws Exception {
        var sessions = sessions(session());
        mvc(state -> new PlannerStep.AskUser("你想看哪个城市？", AgentTooling.Basis.clarifying()), sessions)
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("再看看", SESSION)))
            .andExpect(status().isOk());

        org.mockito.Mockito.verify(sessions, org.mockito.Mockito.never()).rememberRun(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }

    /** 没带 sessionId 的一轮也要开一轮会话并返回它，否则页面下一句又是从零开始。 */
    @Test void aRunWithoutASessionOpensOneAndReturnsIt() throws Exception {
        var sessions = sessions(null);
        org.mockito.Mockito.when(sessions.rememberRun(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(session());

        mvc(state -> state.observations().isEmpty()
            ? new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先查")
            : new PlannerStep.Finish("下面按稳定性排序。", AgentTooling.Basis.on(1)), sessions)
            .perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content(body("杭州有哪些岗位", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").isNotEmpty());
    }
}
