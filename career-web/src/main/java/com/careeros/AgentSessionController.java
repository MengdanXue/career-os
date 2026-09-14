package com.careeros;

import com.careeros.application.AgentSession;
import com.careeros.application.AgentSessionService;
import com.careeros.application.RepositoryPorts;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * 取回一轮会话，用于刷新后接着走。
 *
 * <p>没有这个接口，会话只活在页面内存里：用户按一次 F5，待确认事项、岗位顺序、
 * 以及那些问题所依据的资料版本全部消失，他只能重新查一遍。更糟的是回答到一半刷新——
 * 已经写进去的那条没了着落，用户不知道自己答过没有。
 *
 * <p><b>要核对归属。</b> 会话 ID 是可猜的 UUID。不核对的话，报出别人的会话 ID
 * 就能读到别人的待确认事项——那是资料内容，不是无关紧要的标识。
 * 不存在与不属于本人返回同一个 404，不泄露"存在但不是你的"。
 *
 * <p><b>会顺带说明这份会话还作不作数。</b> 资料版本变了，那份列表的序号和那些问题
 * 都不再对应当前结论；页面据此提示重新查询，而不是拿着旧列表继续。
 */
@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/agent-sessions")
class AgentSessionController {
    private final AgentSessionService sessions;
    private final RepositoryPorts.CandidateProfiles candidates;

    AgentSessionController(AgentSessionService sessions, RepositoryPorts.CandidateProfiles candidates) {
        this.sessions = sessions;
        this.candidates = candidates;
    }

    @GetMapping("/{sessionId}")
    SessionView get(@PathVariable("candidateId") UUID candidateId, @PathVariable("sessionId") UUID sessionId) {
        var session = sessions.find(candidateId, sessionId).orElseThrow(() ->
            new AgentSessionService.SessionNotFoundException("没有这轮会话，或它不属于当前候选人。"));
        String current = candidates.findById(candidateId).map(profile -> profile.profileVersion()).orElse(null);
        return SessionView.from(session, session.matchesProfileVersion(current));
    }

    /**
     * @param stale 资料已变，这份列表与这些问题不再对应当前结论。页面要提示重新查询，
     *              不能拿旧序号继续问"第二个怎么样"
     */
    record SessionView(UUID sessionId, String profileVersion, boolean stale, List<UUID> lastJobIdsInOrder,
                       List<PendingItem> pendingConfirmations, String updatedAt) {
        static SessionView from(AgentSession session, boolean fresh) {
            return new SessionView(session.sessionId(), session.profileVersion(), !fresh,
                session.lastJobIdsInOrder(),
                session.pendingConfirmations().stream()
                    .map(item -> new PendingItem(item.factKey(), item.question(), item.jobPostingId())).toList(),
                session.updatedAt().toString());
        }
    }

    /** @param jobPostingId 是哪个岗位提出的这个问题。页面要显示它，否则用户不知道在为什么而答 */
    record PendingItem(CandidateFactKey factKey, String question, UUID jobPostingId) {}
}
