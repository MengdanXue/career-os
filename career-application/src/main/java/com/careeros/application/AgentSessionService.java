package com.careeros.application;

import com.careeros.application.AgentSession.PendingConfirmation;
import com.careeros.application.AgentSession.SessionFilters;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.RuleType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 维护一轮对话记住的东西，并把"第 N 个"解析回用户真正指着的那个岗位。
 *
 * <p>这里唯一做的判断是：那份列表还算不算数。资料版本一变，名次和结论都可能变，
 * 旧的序号就不再指向用户看到的岗位——此时宁可说"列表已过期，请重新查询"，
 * 也不能拿着重新排出来的第二名认真作答。那种错没有任何外在迹象。
 */
public final class AgentSessionService {
    private final AgentSessionPorts.Sessions sessions;
    private final RepositoryPorts.CandidateProfiles candidates;
    private final Clock clock;

    public AgentSessionService(
        AgentSessionPorts.Sessions sessions,
        RepositoryPorts.CandidateProfiles candidates,
        Clock clock
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 记下这一轮的筛选条件、岗位顺序、待确认事项和资料版本。 */
    public AgentSession remember(
        UUID sessionId, UUID candidateId, SessionFilters filters, List<DecisionPorts.DecisionBundle> decisions
    ) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(candidateId, "candidateId");
        var profile = candidates.findById(candidateId).orElseThrow(() ->
            new CandidateProfileService.CandidateProfileNotFoundException("Candidate not found: " + candidateId));
        var jobIds = decisions.stream().map(value -> value.decision().jobPostingId()).distinct().toList();
        var existing = sessions.find(sessionId).orElse(null);
        if (existing != null && !existing.candidateId().equals(candidateId)) {
            throw new IllegalArgumentException("session belongs to another candidate");
        }
        var session = existing == null
            ? new AgentSession(sessionId, candidateId, filters, jobIds, pending(decisions),
                profile.profileVersion(), clock.instant())
            : existing.withListing(filters, jobIds, pending(decisions), profile.profileVersion(), clock.instant());
        return sessions.save(session);
    }

    /**
     * 把"第 N 个"解析回岗位。
     *
     * @return 解析结果。资料已变时返回 {@link Reference#staleListing()}——不重新排名，
     *         因为重新排出来的第 N 个可能是另一个岗位，而用户无从察觉。
     */
    public Reference resolveOrdinal(UUID sessionId, int ordinal) {
        var session = sessions.find(sessionId).orElse(null);
        if (session == null) return Reference.noSession();
        var profile = candidates.findById(session.candidateId()).orElseThrow(() ->
            new CandidateProfileService.CandidateProfileNotFoundException(
                "Candidate not found: " + session.candidateId()));
        if (!session.matchesProfileVersion(profile.profileVersion())) return Reference.staleListing();
        return session.jobAtOrdinal(ordinal).map(Reference::resolved).orElseGet(Reference::outOfRange);
    }

    /**
     * 用户要回答某个待确认事项时，取出他当时看到的资料版本。
     *
     * <p>确认写入用的乐观版本检查要以"用户看到问题时的版本"为准，而不是"此刻库里的版本"——
     * 后者永远等于当前值，那个检查就恒真，等于没检查。
     */
    public Optional<String> profileVersionSeenBy(UUID sessionId) {
        return sessions.find(sessionId).map(AgentSession::profileVersion);
    }

    /** 从岗位结论里提取待确认事项：哪个字段没确认、是哪个岗位问的、原话是什么。 */
    private static List<PendingConfirmation> pending(List<DecisionPorts.DecisionBundle> decisions) {
        var pending = new ArrayList<PendingConfirmation>();
        var seen = new java.util.HashSet<CandidateFactKey>();
        for (var bundle : decisions) {
            var eligibility = bundle.eligibility();
            if (eligibility == null) continue;
            for (var entry : eligibility.ruleResults().entrySet()) {
                if (entry.getValue().status() != EligibilityStatus.NEEDS_CONFIRMATION) continue;
                CandidateFactKey key = factKeyFor(entry.getKey());
                if (key == null || !seen.add(key)) continue;
                pending.add(new PendingConfirmation(key, entry.getValue().explanation(),
                    bundle.decision().jobPostingId()));
            }
        }
        return List.copyOf(pending);
    }

    /**
     * 硬条件对应哪个候选人字段。
     *
     * <p>只映射对话里能直接回答的标量字段。学历、工作经历这类要凭材料判断的，这里返回 null——
     * 它们不该出现在"回一句就能解决"的待确认清单里。
     */
    private static CandidateFactKey factKeyFor(RuleType rule) {
        return switch (rule) {
            case POLITICAL_AFFILIATION -> CandidateFactKey.POLITICAL_AFFILIATION;
            case GENDER -> CandidateFactKey.GENDER;
            case AGE, EDUCATION, EXACT_MAJOR, GRADUATE_YEAR, EXPERIENCE, PROFESSIONAL_TITLE,
                 FRESH_GRADUATE_STATUS, OTHER -> null;
        };
    }

    /**
     * @param jobPostingId 解析出来的岗位；未解析出来时为 {@code null}
     */
    public record Reference(Outcome outcome, UUID jobPostingId) {
        public enum Outcome {
            RESOLVED,
            /** 没有这轮会话，无从解析序号。 */
            NO_SESSION,
            /** 资料已变，那份列表不再是用户看到的那一份。 */
            STALE_LISTING,
            /** 序号超出那份列表的范围。 */
            OUT_OF_RANGE
        }

        static Reference resolved(UUID jobPostingId) { return new Reference(Outcome.RESOLVED, jobPostingId); }
        static Reference noSession() { return new Reference(Outcome.NO_SESSION, null); }
        static Reference staleListing() { return new Reference(Outcome.STALE_LISTING, null); }
        static Reference outOfRange() { return new Reference(Outcome.OUT_OF_RANGE, null); }
    }
}
