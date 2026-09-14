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
        if (request.question() == null || request.question().isBlank()) throw new IllegalArgumentException("question is required");
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between 1 and 20");
        // Check ownership before ranking, describing or reading a remembered version.
        if (request.sessionId() != null) sessions.requireOwned(candidateId, request.sessionId());
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
    @Transactional(readOnly = true)
    AgentSessionResponse session(
        @PathVariable("candidateId") UUID candidateId,
        @PathVariable("sessionId") UUID sessionId
    ) {
        var session = sessions.requireOwned(candidateId, sessionId);
        // 只读核对当前确认状态。会话刷新由确认写入后的短事务完成；GET 不更新会话或结论。
        var facts = profiles.facts(candidateId);
        return new AgentSessionResponse(session.sessionId(), session.profileVersion(),
            facts.profile().profileVersion(), !session.matchesProfileVersion(facts.profile().profileVersion()),
            session.lastJobIdsInOrder(),
            session.pendingConfirmations().stream()
                .map(item -> new SessionPendingConfirmation(item.factKey(), item.question(), item.jobPostingId(),
                    answered(facts, item.factKey())))
                .toList());
    }

    private static boolean answered(com.careeros.application.CandidateProfileService.CandidateProfileFacts facts,
                                     CandidateFactKey key) {
        if (facts.statuses().get(key) != com.careeros.domain.CandidateFacts.CandidateFactStatus.CONFIRMED) return false;
        var profile = facts.profile();
        return switch (key) {
            case GENDER -> profile.gender() != com.careeros.domain.DomainEnums.Gender.UNKNOWN;
            case POLITICAL_AFFILIATION -> profile.politicalAffiliation() != com.careeros.domain.DomainEnums.PoliticalAffiliation.UNKNOWN;
            case EMPLOYER_SETTLEMENT_AT_APPLICATION -> profile.employerSettlementAtApplication()
                != com.careeros.domain.DomainEnums.ApplicationTimeStatus.UNDECLARED;
            case SOCIAL_INSURANCE_AT_APPLICATION -> profile.socialInsuranceAtApplication()
                != com.careeros.domain.DomainEnums.ApplicationTimeStatus.UNDECLARED;
            default -> false;
        };
    }

    /** @param answered 这一条是否已经确认过；刷新之后据此区分"还没答"和"答过了" */
    record SessionPendingConfirmation(CandidateFactKey factKey, String question, UUID jobPostingId,
                                      boolean answered) {}

    record AgentSessionResponse(UUID sessionId, String profileVersion, String currentProfileVersion,
                                boolean stale, List<UUID> jobIdsInOrder,
                                List<SessionPendingConfirmation> pendingConfirmations) {}

    /**
     * "第 N 个怎么样"——指的是用户屏幕上那一份列表的第 N 个。
     *
     * <p>解析不出序号就返回 null，走普通查询。解析出来了但那份列表已经不作数（资料变了）
     * 或者序号越界，就明说，**不重新排名**：重新排出来的第 N 个可能是另一个岗位，
     * 系统会一本正经地讲另一件事，而用户看不出它换了对象。
     *
     * <p>没有会话时明确拒绝序号引用，不用新排名代替用户指向的列表。
     */
    private AgentResponse resolveOrdinal(UUID candidateId, AgentRequest request, int limit) {
        var ordinal = OrdinalReference.parse(request.question());
        if (ordinal.isEmpty()) return null;
        if (request.sessionId() == null) {
            return describe(candidateId, request, service.cannotResolve(request.question(),
                "没有可对应的上一份岗位列表，请先查询列表后再指定序号。", limit), null);
        }
        var reference = sessions.resolveOrdinal(candidateId, request.sessionId(), ordinal.get());
        if (reference.outcome() == Outcome.NO_SESSION) {
            throw new AgentSessionService.SessionNotFoundException("session not found");
        }
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
    private AgentResponse describe(UUID candidateId, AgentRequest request, AgentQueryService.AgentResponse result, UUID sessionId) {
        var decisions = result.decisions().stream()
            .map(value -> DecisionApiModels.DecisionResponse.from(value, explanations.explain(value))).toList();
        AgentSession session = sessionId == null ? null : sessions.requireOwned(candidateId, sessionId);
        if (session != null && !result.decisions().isEmpty()) {
            session = sessions.rememberDescription(candidateId, sessionId, result.decisions());
        }
        var focusedIds = result.decisions().stream().map(value -> value.decision().jobPostingId()).toList();
        var pending = session == null ? List.<PendingConfirmation>of() : session.pendingConfirmations().stream()
            .filter(item -> focusedIds.contains(item.jobPostingId()))
            .map(item -> new PendingConfirmation(item.factKey(), item.question(), item.jobPostingId())).toList();
        return new AgentResponse(result.question(), result.answer(), decisions, result.modelPhrased(),
            result.fallbackUsed(), result.disclaimer(), result.violations(), sessionId,
            session == null ? null : session.profileVersion(), pending);
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
