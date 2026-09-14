package com.careeros;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.AgentQueryService;
import com.careeros.application.AgentSession;
import com.careeros.application.AgentSessionService;
import com.careeros.application.AgentSessionService.Reference;
import com.careeros.application.AgentSessionService.Reference.Outcome;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OpportunityTier;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.CandidateFacts.CandidateFactStatus;
import com.careeros.domain.*;
import static com.careeros.domain.DomainEnums.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        when(sessions.requireOwned(CANDIDATE_ID,SESSION_ID)).thenReturn(new AgentSession(
            SESSION_ID,CANDIDATE_ID,FILTERS,List.of(),List.of(),"profile-7",Instant.EPOCH));
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
        when(sessions.resolveOrdinal(CANDIDATE_ID,SESSION_ID,2)).thenReturn(new Reference(Outcome.RESOLVED,job));
        when(sessions.profileVersionSeenBy(CANDIDATE_ID,SESSION_ID)).thenReturn(java.util.Optional.of("profile-7"));
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
        when(sessions.resolveOrdinal(CANDIDATE_ID,SESSION_ID,2)).thenReturn(new Reference(Outcome.STALE_LISTING,null));
        when(service.query(eq(CANDIDATE_ID),anyString(),eq(5),any()))
            .thenReturn(new AgentQueryService.AgentResponse("重新排过的列表","1. 另一个岗位",List.of(),false,false,
                "机会决策指数，不是录取概率",List.of(),FILTERS));

        when(sessions.profileVersionSeenBy(CANDIDATE_ID,SESSION_ID)).thenReturn(java.util.Optional.of("profile-7"));
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
        when(sessions.resolveOrdinal(CANDIDATE_ID,SESSION_ID,9)).thenReturn(new Reference(Outcome.OUT_OF_RANGE,null));
        when(service.query(eq(CANDIDATE_ID),anyString(),eq(5),any()))
            .thenReturn(new AgentQueryService.AgentResponse("重新排过的列表","1. 另一个岗位",List.of(),false,false,
                "机会决策指数，不是录取概率",List.of(),FILTERS));

        when(sessions.profileVersionSeenBy(CANDIDATE_ID,SESSION_ID)).thenReturn(java.util.Optional.of("profile-7"));
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

        verify(sessions,never()).resolveOrdinal(any(),any(),anyInt());
        verify(service).query(eq(CANDIDATE_ID),eq("第一学历有要求吗"),eq(5),any());
    }

    @Test void invalidLimitReturnsProblemDetails() throws Exception {
        when(service.query(eq(CANDIDATE_ID),anyString(),eq(50),any())).thenThrow(new IllegalArgumentException("limit must be between 1 and 20"));
        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries",CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"岗位\",\"limit\":50}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void allGetAndPostPathsRejectAForeignSessionBeforeReadingOrRanking() throws Exception {
        UUID foreign = UUID.randomUUID();
        when(sessions.requireOwned(foreign, SESSION_ID))
            .thenThrow(new AgentSessionService.SessionNotFoundException("session not found"));
        mvc.perform(get("/api/v1/candidates/{candidateId}/agent-queries/{sessionId}", foreign, SESSION_ID))
            .andExpect(status().isNotFound());
        for (String question : List.of("杭州有哪些岗位", "第二个怎么样")) {
            mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries", foreign)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"" + question + "\",\"sessionId\":\"" + SESSION_ID + "\"}"))
                .andExpect(status().isNotFound());
        }
        verifyNoInteractions(service, profiles);
        verify(sessions, never()).resolveOrdinal(any(), any(), anyInt());
        verify(sessions, never()).remember(any(), any(), any(), any());
        verify(sessions, never()).rememberDescription(any(), any(), any());
    }

    @Test void anOrdinalWithoutASavedSessionDoesNotInventANewRanking() throws Exception {
        when(service.cannotResolve(anyString(), anyString(), anyInt()))
            .thenReturn(new AgentQueryService.AgentResponse("第二个怎么样", "请先查询列表后再指定序号。",
                List.of(), false, false, "机会决策指数，不是录取概率", List.of(), FILTERS));
        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries", CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"第二个怎么样\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").doesNotExist())
            .andExpect(jsonPath("$.pendingConfirmations").isEmpty());
        verify(service, never()).query(any(), anyString(), anyInt(), any());
        verify(service, never()).describe(any(), any(), anyString(), anyInt(), any());
        verifyNoInteractions(sessions, profiles);
    }

    @Test void sessionReadReturnsVersionComparisonWithoutRecomputingOrMutating() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var saved = new AgentSession(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(first, second),
            List.of(new AgentSession.PendingConfirmation(CandidateFactKey.GENDER, "性别待确认", second)),
            "profile-7", Instant.EPOCH);
        when(sessions.requireOwned(CANDIDATE_ID, SESSION_ID)).thenReturn(saved);
        for (String version : List.of("profile-7", "profile-8")) {
            var profile = new CandidateProfile(CANDIDATE_ID, "候选人", PartialDate.month(1997, 4),
                EducationLevel.MASTER, Set.of("计算机"), 2027, 0, Set.of(), List.of("杭州"), Set.of(), version);
            when(profiles.facts(CANDIDATE_ID)).thenReturn(new com.careeros.application.CandidateProfileService.CandidateProfileFacts(
                profile, Map.of(CandidateFactKey.GENDER, CandidateFactStatus.CONFIRMED), 1, 0, 0, false));
            mvc.perform(get("/api/v1/candidates/{candidateId}/agent-queries/{sessionId}", CANDIDATE_ID, SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVersion").value("profile-7"))
                .andExpect(jsonPath("$.currentProfileVersion").value(version))
                .andExpect(jsonPath("$.stale").value(!version.equals("profile-7")))
                .andExpect(jsonPath("$.jobIdsInOrder[0]").value(first.toString()))
                .andExpect(jsonPath("$.jobIdsInOrder[1]").value(second.toString()))
                .andExpect(jsonPath("$.pendingConfirmations[0].answered").value(false));
        }
        verifyNoInteractions(service);
        verify(sessions, never()).remember(any(), any(), any(), any());
        verify(sessions, never()).rememberDescription(any(), any(), any());
        verify(sessions, never()).advanceProfileVersion(any(), any(), any(), any());
    }

    @Test void focusedExplanationReturnsOnlyItsApplicablePendingItemsAndKeepsTheListing() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var decision = decision(second);
        var focused = new AgentSession(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(first, second), List.of(
            new AgentSession.PendingConfirmation(CandidateFactKey.GENDER, "首个岗位的性别要求", first),
            new AgentSession.PendingConfirmation(CandidateFactKey.POLITICAL_AFFILIATION, "政治面貌待确认", second)),
            "profile-7", Instant.EPOCH);
        when(sessions.resolveOrdinal(CANDIDATE_ID, SESSION_ID, 2)).thenReturn(new Reference(Outcome.RESOLVED, second));
        when(service.describe(eq(CANDIDATE_ID), eq(second), anyString(), eq(5), any()))
            .thenReturn(new AgentQueryService.AgentResponse("第二个怎么样", "确定性说明", List.of(decision),
                false, false, "机会决策指数，不是录取概率", List.of(), FILTERS));
        when(sessions.rememberDescription(CANDIDATE_ID, SESSION_ID, List.of(decision))).thenReturn(focused);

        mvc.perform(post("/api/v1/candidates/{candidateId}/agent-queries", CANDIDATE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"第二个怎么样\",\"sessionId\":\"" + SESSION_ID + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pendingConfirmations.length()").value(1))
            .andExpect(jsonPath("$.pendingConfirmations[0].factKey").value("POLITICAL_AFFILIATION"))
            .andExpect(jsonPath("$.pendingConfirmations[0].jobPostingId").value(second.toString()))
            .andExpect(jsonPath("$.profileVersion").value("profile-7"));
        verify(sessions).rememberDescription(CANDIDATE_ID, SESSION_ID, List.of(decision));
        verify(sessions, never()).remember(any(), any(), any(), any());
        verify(service, never()).query(any(), anyString(), anyInt(), any());
    }

    private static com.careeros.application.DecisionPorts.DecisionBundle decision(UUID jobId) {
        UUID org = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        String fingerprint = "a".repeat(64);
        var evidence = List.of(UUID.randomUUID());
        var job = new JobPosting(jobId, eventId, org, "J-1", "信息技术岗位",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, "杭州", 1,
            EducationLevel.BACHELOR, Set.of(), Set.of(), null, null, null, Set.of(), "",
            "https://example.gov.cn/job", evidence);
        var organization = new Organization(org, "信息中心", OrganizationType.PUBLIC_INSTITUTION,
            "市级", "浙江", "杭州", null, null, "https://example.gov.cn");
        var event = new RecruitmentEvent(eventId, "公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 9, 1), job.sourceUrl(),
            EmploymentType.ESTABLISHMENT, evidence);
        var eligibility = new EligibilityAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId,
            EligibilityStatus.NEEDS_CONFIRMATION, Map.of(), evidence, "test", now, "profile-7", fingerprint);
        var fit = new FitAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, List.of(), "test", "profile-7", fingerprint, now);
        var stability = new StabilityAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, List.of(), "test", "profile-7", fingerprint, now);
        var assessment = new DecisionAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, eligibility.id(), fit.id(),
            stability.id(), EligibilityStatus.NEEDS_CONFIRMATION, OpportunityTier.T1, RecommendationStatus.REVIEW,
            fit.score(), stability.score(), 0, "test", "profile-7", fingerprint, now);
        return new com.careeros.application.DecisionPorts.DecisionBundle(eligibility, fit, stability, assessment,
            new com.careeros.application.DecisionPorts.JobContext(job, organization, event, fingerprint, true));
    }
}
