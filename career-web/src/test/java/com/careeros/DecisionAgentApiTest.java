package com.careeros;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.AgentQueryService;
import com.careeros.application.AgentSession;
import com.careeros.application.AgentSessionService;
import com.careeros.application.AgentSessionService.Reference;
import com.careeros.application.AgentSessionService.Reference.Outcome;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OpportunityTier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DecisionAgentApiTest {
    private static final UUID CANDIDATE_ID=UUID.randomUUID();
    private static final AgentSession.SessionFilters FILTERS=
        new AgentSession.SessionFilters(OpportunityTier.T1,"杭州",JobFamily.INFORMATION_SYSTEMS,5);
    private static final UUID SESSION_ID=UUID.randomUUID();

    private AgentQueryService service;
    private AgentSessionService sessions;
    private com.careeros.application.CandidateProfileService profiles;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        service=mock(AgentQueryService.class);
        sessions=mock(AgentSessionService.class);
        when(sessions.remember(any(),any(),any(),any())).thenAnswer(call->new AgentSession(
            call.getArgument(0),call.getArgument(1),FILTERS,List.of(),List.of(),"profile-7",Instant.EPOCH));
        profiles=mock(com.careeros.application.CandidateProfileService.class);
        mvc=MockMvcBuilders.standaloneSetup(new DecisionAgentController(service,new com.careeros.application.DecisionExplanationService(),sessions,profiles))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void answersBoundedCareerQuestionWithoutModel() throws Exception {
        when(service.query(eq(CANDIDATE_ID),eq("杭州有哪些稳定的信息化岗位？"),eq(5),any()))
            .thenReturn(new AgentQueryService.AgentResponse("杭州有哪些稳定的信息化岗位？","当前建议关注：信息中心技术岗",List.of(),false,false,"机会决策指数，不是录取概率",List.of(),FILTERS));

        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries",CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"杭州有哪些稳定的信息化岗位？\",\"limit\":5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value("当前建议关注：信息中心技术岗"))
            .andExpect(jsonPath("$.modelPhrased").value(false))
            .andExpect(jsonPath("$.fallbackUsed").value(false))
            .andExpect(jsonPath("$.disclaimer").value("机会决策指数，不是录取概率"))
            .andExpect(jsonPath("$.violations").isArray())
            .andExpect(jsonPath("$.sessionId").exists())
            .andExpect(jsonPath("$.profileVersion").value("profile-7"));
    }

    /** 带上 sessionId 就接着上一轮：会话要延续，而不是每句话都开一轮新的。 */
    @Test void continuesAnExistingSession() throws Exception {
        when(service.query(eq(CANDIDATE_ID),anyString(),eq(5),any()))
            .thenReturn(new AgentQueryService.AgentResponse("还有别的吗","1. 信息中心技术岗",List.of(),false,false,
                "机会决策指数，不是录取概率",List.of(),FILTERS));

        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries",CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"还有别的吗\",\"limit\":5,\"sessionId\":\""+SESSION_ID+"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()));

        verify(sessions).remember(eq(SESSION_ID),eq(CANDIDATE_ID),eq(FILTERS),any());
    }

    /** 叙述被拒时，回落原因必须随回答一起返回——只在服务端知道等于没拦截。 */
    @Test void aRejectedNarrativeSurfacesItsViolations() throws Exception {
        when(service.query(eq(CANDIDATE_ID),eq("杭州有哪些稳定的信息化岗位？"),eq(5),any()))
            .thenReturn(new AgentQueryService.AgentResponse("杭州有哪些稳定的信息化岗位？","1. 信息中心技术岗",List.of(),false,true,
                "机会决策指数，不是录取概率",List.of("叙述包含数值；所有数值必须来自确定性事实块"),FILTERS));

        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries",CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"杭州有哪些稳定的信息化岗位？\",\"limit\":5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fallbackUsed").value(true))
            .andExpect(jsonPath("$.modelPhrased").value(false))
            .andExpect(jsonPath("$.violations[0]").value("叙述包含数值；所有数值必须来自确定性事实块"));
    }

    /** "第二个怎么样"要落到用户屏幕上那一份列表的第二个，不能重新排名。 */
    @Test void anOrdinalIsAnsweredAboutTheJobTheUserPointedAt() throws Exception {
        UUID job=UUID.randomUUID();
        when(sessions.resolveOrdinal(SESSION_ID,2)).thenReturn(new Reference(Outcome.RESOLVED,job));
        when(sessions.profileVersionSeenBy(SESSION_ID)).thenReturn(java.util.Optional.of("profile-7"));
        when(service.describe(eq(CANDIDATE_ID),eq(job),eq("第二个怎么样？"),eq(5),any()))
            .thenReturn(new AgentQueryService.AgentResponse("第二个怎么样？","1. 信息中心技术岗",List.of(),false,false,
                "机会决策指数，不是录取概率",List.of(),FILTERS));

        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries",CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"第二个怎么样？\",\"limit\":5,\"sessionId\":\""+SESSION_ID+"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value("1. 信息中心技术岗"));

        // 没有重新排名，也没有覆盖记下的顺序——否则下一句"第三个"就没了依据。
        verify(service,never()).query(any(),anyString(),anyInt(),any());
        verify(sessions,never()).remember(any(),any(),any(),any());
    }

    /**
     * 那份列表已经不作数了就说不作数。回落去重新排一次名，会拿另一个岗位冒充
     * 用户指的那个，而他看不出来。
     */
    @Test void aStaleListingRefusesTheOrdinalInsteadOfReRanking() throws Exception {
        when(sessions.resolveOrdinal(SESSION_ID,2)).thenReturn(new Reference(Outcome.STALE_LISTING,null));
        when(service.query(eq(CANDIDATE_ID),anyString(),eq(5),any()))
            .thenReturn(new AgentQueryService.AgentResponse("重新排过的列表","1. 另一个岗位",List.of(),false,false,
                "机会决策指数，不是录取概率",List.of(),FILTERS));

        when(sessions.profileVersionSeenBy(SESSION_ID)).thenReturn(java.util.Optional.of("profile-7"));
        when(service.cannotResolve(anyString(),anyString(),anyInt()))
            .thenReturn(new AgentQueryService.AgentResponse("第二个怎么样？","你的资料已经更新，请重新查询后再指定序号。",
                List.of(),false,false,"机会决策指数，不是录取概率",List.of(),FILTERS));

        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries",CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"第二个怎么样？\",\"limit\":5,\"sessionId\":\""+SESSION_ID+"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("重新查询")));

        verify(service,never()).query(any(),anyString(),anyInt(),any());
        verify(service,never()).describe(any(),any(),anyString(),anyInt(),any());
    }

    @Test void anOutOfRangeOrdinalSaysSoWithoutReRanking() throws Exception {
        when(sessions.resolveOrdinal(SESSION_ID,9)).thenReturn(new Reference(Outcome.OUT_OF_RANGE,null));
        when(service.query(eq(CANDIDATE_ID),anyString(),eq(5),any()))
            .thenReturn(new AgentQueryService.AgentResponse("重新排过的列表","1. 另一个岗位",List.of(),false,false,
                "机会决策指数，不是录取概率",List.of(),FILTERS));

        when(sessions.profileVersionSeenBy(SESSION_ID)).thenReturn(java.util.Optional.of("profile-7"));
        when(service.cannotResolve(anyString(),anyString(),anyInt()))
            .thenReturn(new AgentQueryService.AgentResponse("第九个怎么样？","上一份列表里没有第 9 个。",
                List.of(),false,false,"机会决策指数，不是录取概率",List.of(),FILTERS));

        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries",CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"第九个怎么样？\",\"limit\":5,\"sessionId\":\""+SESSION_ID+"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("没有第 9 个")));

        verify(service,never()).query(any(),anyString(),anyInt(),any());
    }

    /** 不是序号的提问照常走普通查询——"第一学历"不是在指第一个岗位。 */
    @Test void aFixedPhraseIsNotTreatedAsAnOrdinal() throws Exception {
        when(service.query(eq(CANDIDATE_ID),eq("第一学历有要求吗"),eq(5),any()))
            .thenReturn(new AgentQueryService.AgentResponse("第一学历有要求吗","当前没有符合条件的岗位。",List.of(),false,false,
                "机会决策指数，不是录取概率",List.of(),FILTERS));

        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries",CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"第一学历有要求吗\",\"limit\":5,\"sessionId\":\""+SESSION_ID+"\"}"))
            .andExpect(status().isOk());

        verify(sessions,never()).resolveOrdinal(any(),anyInt());
        verify(service).query(eq(CANDIDATE_ID),eq("第一学历有要求吗"),eq(5),any());
    }

    @Test void invalidLimitReturnsProblemDetails() throws Exception {
        when(service.query(eq(CANDIDATE_ID),anyString(),eq(50),any())).thenThrow(new IllegalArgumentException("limit must be between 1 and 20"));
        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries",CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"岗位\",\"limit\":50}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
