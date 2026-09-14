package com.careeros;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.AgentSession;
import com.careeros.application.AgentSessionService;
import com.careeros.application.RepositoryPorts;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 刷新之后要能接着走。
 *
 * <p>会话只活在页面内存里的话，用户按一次 F5，待确认事项、岗位顺序和它们依据的资料版本
 * 全部消失——回答到一半刷新最糟：已经写进去的那条没了着落，用户不知道自己答过没有。
 */
class AgentSessionApiTest {
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();

    private static AgentSession session() {
        return new AgentSession(SESSION, CANDIDATE, new AgentSession.SessionFilters(null, "杭州", null, 5),
            List.of(JOB),
            List.of(new AgentSession.PendingConfirmation(
                CandidateFactKey.POLITICAL_AFFILIATION, "候选人政治面貌尚未确认", JOB)),
            "profile-7", Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static org.springframework.test.web.servlet.MockMvc mvc(AgentSession owned, String currentVersion) {
        var sessions = org.mockito.Mockito.mock(AgentSessionService.class);
        org.mockito.Mockito.when(sessions.find(any(), any())).thenReturn(Optional.ofNullable(owned));
        var candidates = org.mockito.Mockito.mock(RepositoryPorts.CandidateProfiles.class);
        var profile = org.mockito.Mockito.mock(com.careeros.domain.CandidateProfile.class);
        org.mockito.Mockito.lenient().when(profile.profileVersion()).thenReturn(currentVersion);
        org.mockito.Mockito.when(candidates.findById(any())).thenReturn(Optional.of(profile));
        return MockMvcBuilders.standaloneSetup(new AgentSessionController(sessions, candidates))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    /** 刷新后取回来的必须包含待确认事项和提出它的岗位，否则页面只剩一段没有着落的文字。 */
    @Test void aRefreshRecoversThePendingItemsAndTheJobThatAskedForThem() throws Exception {
        mvc(session(), "profile-7")
            .perform(get("/api/v1/candidates/{c}/agent-sessions/{s}", CANDIDATE, SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profileVersion").value("profile-7"))
            .andExpect(jsonPath("$.stale").value(false))
            .andExpect(jsonPath("$.pendingConfirmations[0].factKey").value("POLITICAL_AFFILIATION"))
            .andExpect(jsonPath("$.pendingConfirmations[0].jobPostingId").value(JOB.toString()))
            .andExpect(jsonPath("$.lastJobIdsInOrder[0]").value(JOB.toString()));
    }

    /**
     * 资料在这期间变了，这份会话就不作数了，要说出来。
     *
     * <p>不说的话，页面会拿着旧序号继续问"第二个怎么样"——重新排出来的第二个可能是另一个岗位，
     * 而用户看不出它换了对象。
     */
    @Test void aSessionWhoseProfileMovedOnIsMarkedStale() throws Exception {
        mvc(session(), "profile-9")
            .perform(get("/api/v1/candidates/{c}/agent-sessions/{s}", CANDIDATE, SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stale").value(true));
    }

    /**
     * 别人的会话读不到。
     *
     * <p>待确认事项是资料内容，不是无关紧要的标识。会话 ID 又是可猜的 UUID，
     * 所以"不存在"和"不是你的"要返回同一个 404——分得出来就等于可以枚举。
     */
    @Test void aSessionBelongingToSomeoneElseIsNotFound() throws Exception {
        mvc(null, "profile-7")
            .perform(get("/api/v1/candidates/{c}/agent-sessions/{s}", CANDIDATE, SESSION))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }
}
