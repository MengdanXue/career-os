package com.careeros;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.AgentQueryService;
import com.careeros.application.AgentSession;
import com.careeros.application.AgentSessionService;
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
    private MockMvc mvc;

    @BeforeEach void setUp() {
        service=mock(AgentQueryService.class);
        sessions=mock(AgentSessionService.class);
        when(sessions.remember(any(),any(),any(),any())).thenAnswer(call->new AgentSession(
            call.getArgument(0),call.getArgument(1),FILTERS,List.of(),List.of(),"profile-7",Instant.EPOCH));
        mvc=MockMvcBuilders.standaloneSetup(new DecisionAgentController(service,new com.careeros.application.DecisionExplanationService(),sessions))
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

    @Test void invalidLimitReturnsProblemDetails() throws Exception {
        when(service.query(eq(CANDIDATE_ID),anyString(),eq(50),any())).thenThrow(new IllegalArgumentException("limit must be between 1 and 20"));
        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries",CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"岗位\",\"limit\":50}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
