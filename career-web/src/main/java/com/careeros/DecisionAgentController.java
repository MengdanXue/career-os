package com.careeros;

import com.careeros.application.AgentQueryService;
import com.careeros.application.AgentSession;
import com.careeros.application.AgentSessionService;
import com.careeros.application.DecisionExplanationService;
import com.careeros.application.AgentSessionService.Reference.Outcome;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.OrdinalReference;
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
    private final com.careeros.application.CandidateProfileService profiles;

    DecisionAgentController(AgentQueryService service, DecisionExplanationService explanations,
                            AgentSessionService sessions,
                            com.careeros.application.CandidateProfileService profiles) {
        this.service = service;
        this.explanations = explanations;
        this.sessions = sessions;
        this.profiles = profiles;
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
        var byOrdinal = resolveOrdinal(candidateId, request, limit);
        if (byOrdinal != null) return byOrdinal;
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

    /**
     * 取回一轮会话的当前状态。
     *
     * <p>浏览器一刷新，待确认问题就只存在于上一次查询的响应里，用户没法接着回答。
     * 这个只读接口让刷新之后还能把问题和当前资料版本取回来，不必重新提问一遍。
     */
    @GetMapping("/{sessionId}")
    AgentSessionResponse session(
        @PathVariable("candidateId") UUID candidateId,
        @PathVariable("sessionId") UUID sessionId
    ) {
        var session = sessions.find(candidateId, sessionId).orElseThrow(() ->
            new AgentSessionService.SessionNotFoundException("session not found: " + sessionId));
        // 会话记的是"这一轮问过什么"。刷新之后若原样回放，已经答过的问题会被再问一遍，
        // 用户没法区分"还没答"和"答过了"。所以按当前确认状态标出来，而不是把它们删掉——
        // 删掉就看不出系统问过这一条了。
        var facts = profiles.facts(candidateId);
        var statuses = facts.statuses();
        // 资料在这期间变了，上一份列表的序号就不再对应当前结论。要说出来，
        // 否则页面会拿着旧序号继续问"第二个怎么样"，而重新排出来的第二个可能是另一个岗位。
        String current = facts.profile().profileVersion();
        return new AgentSessionResponse(session.sessionId(), session.profileVersion(),
            !session.matchesProfileVersion(current),
            session.lastJobIdsInOrder(),
            session.pendingConfirmations().stream()
                .map(item -> new SessionPendingConfirmation(item.factKey(), item.question(), item.jobPostingId(),
                    statuses.get(item.factKey()) == com.careeros.domain.CandidateFacts.CandidateFactStatus.CONFIRMED))
                .toList());
    }

    /** @param answered 这一条是否已经确认过；刷新之后据此区分"还没答"和"答过了" */
    record SessionPendingConfirmation(CandidateFactKey factKey, String question, UUID jobPostingId,
                                      boolean answered) {}

    /** @param stale 资料已变，这份列表与这些问题不再对应当前结论 */
    record AgentSessionResponse(UUID sessionId, String profileVersion, boolean stale, List<UUID> jobIdsInOrder,
                                List<SessionPendingConfirmation> pendingConfirmations) {}

    /**
     * "第 N 个怎么样"——指的是用户屏幕上那一份列表的第 N 个。
     *
     * <p>解析不出序号就返回 null，走普通查询。解析出来了但那份列表已经不作数（资料变了）
     * 或者序号越界，就明说，**不重新排名**：重新排出来的第 N 个可能是另一个岗位，
     * 系统会一本正经地讲另一件事，而用户看不出它换了对象。
     *
     * <p>没有会话时也走普通查询——那说明用户是新开一轮，序号本来就无所指。
     */
    private AgentResponse resolveOrdinal(UUID candidateId, AgentRequest request, int limit) {
        if (request.sessionId() == null) return null;
        var ordinal = OrdinalReference.parse(request.question());
        if (ordinal.isEmpty()) return null;
        var reference = sessions.resolveOrdinal(candidateId, request.sessionId(), ordinal.get());
        if (reference.outcome() == Outcome.NO_SESSION) return null;
        if (reference.outcome() == Outcome.STALE_LISTING) {
            return describe(candidateId, request, service.cannotResolve(request.question(),
                "你的资料已经更新，上一份列表的排序不再对应当前结论，请重新查询后再指定序号。", limit), request.sessionId());
        }
        if (reference.outcome() == Outcome.OUT_OF_RANGE) {
            return describe(candidateId, request, service.cannotResolve(request.question(),
                "上一份列表里没有第 " + ordinal.get() + " 个。", limit), request.sessionId());
        }
        return describe(candidateId, request,
            service.describe(candidateId, reference.jobPostingId(), request.question(), limit, Instant.now()),
            request.sessionId());
    }

    /**
     * 把针对单个岗位的回答装配成响应。
     *
     * <p>不调用 {@code sessions.remember}：这一轮没有产生新的列表，覆盖掉原有顺序
     * 会让下一句"第三个"失去依据。
     */
    private AgentResponse describe(UUID candidateId, AgentRequest request,
                                  AgentQueryService.AgentResponse result, UUID sessionId) {
        var decisions = result.decisions().stream()
            .map(value -> DecisionApiModels.DecisionResponse.from(value, explanations.explain(value))).toList();
        return new AgentResponse(result.question(), result.answer(), decisions, result.modelPhrased(),
            result.fallbackUsed(), result.disclaimer(), result.violations(), sessionId,
            sessions.profileVersionSeenBy(candidateId, sessionId).orElse(null), List.of());
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
