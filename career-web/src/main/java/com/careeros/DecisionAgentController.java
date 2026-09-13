package com.careeros;

import com.careeros.application.AgentQueryService;
import com.careeros.application.AgentSession;
import com.careeros.application.AgentSessionService;
import com.careeros.application.DecisionExplanationService;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/agent-queries")
class DecisionAgentController {
    private final AgentQueryService service;
    private final DecisionExplanationService explanations;
    private final AgentSessionService sessions;

    DecisionAgentController(AgentQueryService service, DecisionExplanationService explanations,
                            AgentSessionService sessions) {
        this.service = service;
        this.explanations = explanations;
        this.sessions = sessions;
    }

    /**
     * 每一轮都把这份列表记进会话。
     *
     * <p>不记的话，下一句"第二个怎么样"只能对着重新排出来的名次作答——排名可能已经变了，
     * 而用户看不出系统换了个岗位在回答。会话与查询同一个事务：返回了序号却没记下顺序，
     * 那些序号就指向不存在的东西。
     */
    @PostMapping
    @Transactional
    AgentResponse query(@PathVariable("candidateId") UUID candidateId, @RequestBody AgentRequest request) {
        int limit = request.limit() == null ? 5 : request.limit();
        var result = service.query(candidateId, request.question(), limit, Instant.now());
        var decisions = result.decisions().stream()
            .map(value -> DecisionApiModels.DecisionResponse.from(value, explanations.explain(value))).toList();
        UUID sessionId = request.sessionId() == null ? UUID.randomUUID() : request.sessionId();
        var session = sessions.remember(sessionId, candidateId, result.filters(), result.decisions());
        return new AgentResponse(result.question(), result.answer(), decisions, result.modelPhrased(),
            result.fallbackUsed(), result.disclaimer(), result.violations(), session.sessionId(),
            session.profileVersion(),
            session.pendingConfirmations().stream()
                .map(item -> new PendingConfirmation(item.factKey(), item.question(), item.jobPostingId())).toList());
    }

    /** {@code sessionId} 为空表示开一轮新会话；带上它则接着上一轮，筛选条件与岗位顺序都延续。 */
    record AgentRequest(String question, Integer limit, UUID sessionId) {}

    record PendingConfirmation(CandidateFactKey factKey, String question, UUID jobPostingId) {}

    /**
     * {@code violations} 是叙述被拒的原因。拦截不外露等于没拦截，所以它随回答一起返回。
     *
     * <p>{@code profileVersion} 是这份列表所依据的资料版本。回答待确认问题时要把它作为
     * 乐观版本检查的基准——用"此刻库里的版本"去比，那个检查恒真。
     */
    record AgentResponse(String question, String answer, List<DecisionApiModels.DecisionResponse> decisions,
                         boolean modelPhrased, boolean fallbackUsed, String disclaimer, List<String> violations,
                         UUID sessionId, String profileVersion, List<PendingConfirmation> pendingConfirmations) {
        AgentResponse {
            decisions = List.copyOf(decisions);
            violations = violations == null ? List.of() : List.copyOf(violations);
            pendingConfirmations = pendingConfirmations == null ? List.of() : List.copyOf(pendingConfirmations);
        }
    }
}
