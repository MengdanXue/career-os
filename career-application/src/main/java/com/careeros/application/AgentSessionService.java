package com.careeros.application;

import com.careeros.application.AgentSession.PendingConfirmation;
import com.careeros.application.AgentSession.SessionFilters;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.GraduateEligibilityRule;
import com.careeros.domain.GenderRequirementClassifier;
import com.careeros.domain.PoliticalRequirementClassifier;
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
    private final RepositoryPorts.CandidateFactConfirmations confirmations;
    private final Clock clock;

    public AgentSessionService(
        AgentSessionPorts.Sessions sessions,
        RepositoryPorts.CandidateProfiles candidates,
        RepositoryPorts.CandidateFactConfirmations confirmations,
        Clock clock
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
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
        var existing = sessions.find(sessionId).orElse(null);
        if (existing != null && !existing.candidateId().equals(candidateId)) {
            throw new SessionNotFoundException("session not found");
        }
        verifyDecisions(candidateId, profile.profileVersion(), decisions);
        var jobIds = decisions.stream().map(value -> value.decision().jobPostingId()).distinct().toList();
        var pending = pending(decisions, facts(profile), profile);
        var session = existing == null
            ? new AgentSession(sessionId, candidateId, filters, jobIds, pending,
                profile.profileVersion(), clock.instant())
            : existing.withListing(filters, jobIds, pending, profile.profileVersion(), clock.instant());
        if (existing == null) return sessions.save(session);
        if (!sessions.compareAndSet(existing, session)) {
            throw new SessionChangedException("会话在查询期间已更新，请重新查询");
        }
        return session;
    }

    /**
     * 把"第 N 个"解析回岗位。
     *
     * @return 解析结果。资料已变时返回 {@link Reference#staleListing()}——不重新排名，
     *         因为重新排出来的第 N 个可能是另一个岗位，而用户无从察觉。
     */
    public Reference resolveOrdinal(UUID candidateId, UUID sessionId, int ordinal) {
        var session = find(candidateId, sessionId).orElse(null);
        if (session == null) return Reference.noSession();
        var profile = candidates.findById(session.candidateId()).orElseThrow(() ->
            new CandidateProfileService.CandidateProfileNotFoundException(
                "Candidate not found: " + session.candidateId()));
        if (!session.matchesProfileVersion(profile.profileVersion())) return Reference.staleListing();
        return session.jobAtOrdinal(ordinal).map(Reference::resolved).orElseGet(Reference::outOfRange);
    }

    /**
     * 取回一轮会话，并核对它属于这个候选人。
     *
     * <p>会话 ID 是可猜的 UUID，读接口必须按候选人校验归属，否则拿到别人的会话
     * 就能看到别人的待确认事项和岗位顺序。
     */
    public Optional<AgentSession> find(UUID candidateId, UUID sessionId) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(sessionId, "sessionId");
        return sessions.find(sessionId).filter(session -> session.candidateId().equals(candidateId));
    }

    public AgentSession requireOwned(UUID candidateId, UUID sessionId) {
        return find(candidateId, sessionId).orElseThrow(() -> new SessionNotFoundException("session not found"));
    }

    /** 会话不存在，或不属于这个候选人。两者对外一视同仁，不泄露"存在但不是你的"。 */
    public static final class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String message) { super(message); }
    }

    public static final class SessionChangedException extends RuntimeException {
        public SessionChangedException(String message) { super(message); }
    }

    /**
     * 用户要回答某个待确认事项时，取出他当时看到的资料版本。
     *
     * <p>确认写入用的乐观版本检查要以"用户看到问题时的版本"为准，而不是"此刻库里的版本"——
     * 后者永远等于当前值，那个检查就恒真，等于没检查。
     */
    public Optional<String> profileVersionSeenBy(UUID candidateId, UUID sessionId) {
        return find(candidateId, sessionId).map(AgentSession::profileVersion);
    }

    /**
     * 一次确认写入之后，把会话记录的资料版本推进到那次写入产生的版本。
     *
     * <p>不推进的话，第二个待确认问题必定失败：它带的还是第一次查询时的版本，
     * 而资料已经被用户自己的上一次确认改过了。用户每回答一条就得重新查一次，
     * 连续确认根本走不完——每一条确认单独测都是对的，连起来才暴露。
     *
     * <p>只从 {@code fromVersion} 推进到 {@code toVersion}，不是"刷成当前值"。
     * 期间若有别处改动，会话里的版本已经不是 {@code fromVersion}，这里什么都不做，
     * 下一次确认照样会撞上版本检查——那正是这个检查要拦的情况。
     *
     * @return 是否真的推进了
     */
    public boolean advanceProfileVersion(UUID candidateId, UUID sessionId, String fromVersion, String toVersion) {
        if (sessionId == null || fromVersion == null || toVersion == null) return false;
        var session = requireOwned(candidateId, sessionId);
        if (!session.profileVersion().equals(fromVersion)) return false;
        // The caller supplies a short transaction, separate from confirmation
        // recording and recomputation. Do not promote over an unrelated edit.
        var profile = candidates.findByIdForUpdate(candidateId).orElseThrow(() ->
            new CandidateProfileService.CandidateProfileNotFoundException("Candidate not found: " + candidateId));
        if (!profile.profileVersion().equals(toVersion)) return false;
        var facts = facts(profile);
        var remaining = session.pendingConfirmations().stream()
            .filter(item -> !resolved(facts, profile, item.factKey())).toList();
        if (fromVersion.equals(toVersion) && remaining.equals(session.pendingConfirmations())) return false;
        return sessions.compareAndSet(session,
            session.withPendingAndVersion(remaining, toVersion, clock.instant()));
    }

    /** Refresh a focused job's question associations without replacing the visible list. */
    public AgentSession rememberDescription(UUID candidateId, UUID sessionId,
                                            List<DecisionPorts.DecisionBundle> decisions) {
        var before = requireOwned(candidateId, sessionId);
        var profile = candidates.findById(candidateId).orElseThrow(() ->
            new CandidateProfileService.CandidateProfileNotFoundException("Candidate not found: " + candidateId));
        if (!before.matchesProfileVersion(profile.profileVersion())) {
            throw new SessionChangedException("资料已变更，请重新查询列表");
        }
        verifyDecisions(candidateId, before.profileVersion(), decisions);
        var focusedIds = decisions.stream().map(value -> value.decision().jobPostingId()).toList();
        if (!before.lastJobIdsInOrder().containsAll(focusedIds)) {
            throw new SessionChangedException("岗位不在已保存列表中，请重新查询");
        }
        var refreshed = pending(decisions, facts(profile), profile);
        var refreshedKeys = refreshed.stream().map(PendingConfirmation::factKey).toList();
        var combined = new ArrayList<>(before.pendingConfirmations().stream()
            .filter(item -> !focusedIds.contains(item.jobPostingId()) && !refreshedKeys.contains(item.factKey()))
            .toList());
        combined.addAll(refreshed);
        var after = before.withPendingAndVersion(combined, before.profileVersion(), clock.instant());
        if (!sessions.compareAndSet(before, after)) {
            throw new SessionChangedException("会话在解释期间已更新，请重新查询");
        }
        return after;
    }

    /** 从一组岗位结论里提取待确认事项。工具面也要用它，所以是公开的。 */
    public List<PendingConfirmation> pendingFor(UUID candidateId, List<DecisionPorts.DecisionBundle> decisions) {
        Objects.requireNonNull(candidateId, "candidateId");
        var profile = candidates.findById(candidateId).orElseThrow(() ->
            new CandidateProfileService.CandidateProfileNotFoundException("Candidate not found: " + candidateId));
        verifyDecisions(candidateId, profile.profileVersion(), decisions);
        return pending(decisions, facts(profile), profile);
    }

    private CandidateFacts facts(CandidateProfile profile) {
        return CandidateFacts.resolve(profile, confirmations.findByCandidateId(profile.id()));
    }

    private static void verifyDecisions(UUID candidateId, String profileVersion,
                                         List<DecisionPorts.DecisionBundle> decisions) {
        for (var bundle : decisions) {
            if (!bundle.decision().candidateProfileId().equals(candidateId)
                || !bundle.decision().profileVersion().equals(profileVersion)) {
                throw new SessionChangedException("结论与候选人或当前资料版本不匹配，请重新查询");
            }
        }
    }

    /** 从岗位结论里提取待确认事项：哪个字段没确认、是哪个岗位问的、原话是什么。 */
    private static List<PendingConfirmation> pending(List<DecisionPorts.DecisionBundle> decisions,
                                                     CandidateFacts facts, CandidateProfile profile) {
        var pending = new ArrayList<PendingConfirmation>();
        var seen = new java.util.HashSet<CandidateFactKey>();
        for (var bundle : decisions) {
            var eligibility = bundle.eligibility();
            if (eligibility == null) continue;
            for (var rule : RuleType.values()) {
                var result = eligibility.ruleResults().get(rule);
                if (result == null || result.status() != EligibilityStatus.NEEDS_CONFIRMATION) continue;
                CandidateFactKey key = factKeyFor(rule);
                if (key == null || resolved(facts, profile, key)) continue;
                var job = bundle.jobContext().job();
                if (key == CandidateFactKey.POLITICAL_AFFILIATION
                    && !new PoliticalRequirementClassifier().hasHardRequirement(job)) continue;
                if (key == CandidateFactKey.GENDER
                    && new GenderRequirementClassifier().hardRequirement(job).isEmpty()) continue;
                if (!seen.add(key)) continue;
                pending.add(new PendingConfirmation(key, result.explanation(),
                    bundle.decision().jobPostingId()));
            }
            var clause = bundle.jobContext().graduateClause();
            if (clause == null || clause.evidenceState() != GraduateEligibilityRule.EvidenceState.CONFIRMED) continue;
            if (clause.requiresNoEmployer()) addApplicationPending(pending, seen, facts, profile,
                CandidateFactKey.EMPLOYER_SETTLEMENT_AT_APPLICATION,
                "公告要求报名时未落实工作单位，请声明届时报名时是否满足；声明不等于官方核实。", bundle);
            if (clause.restrictsSocialInsurance()) addApplicationPending(pending, seen, facts, profile,
                CandidateFactKey.SOCIAL_INSURANCE_AT_APPLICATION,
                "公告限制报名时社保缴纳情况，请声明届时是否满足；声明不等于官方核实。", bundle);
        }
        return List.copyOf(pending);
    }

    private static void addApplicationPending(List<PendingConfirmation> pending,
        java.util.Set<CandidateFactKey> seen, CandidateFacts facts, CandidateProfile profile, CandidateFactKey key,
        String question, DecisionPorts.DecisionBundle bundle) {
        if (!resolved(facts, profile, key) && seen.add(key)) {
            pending.add(new PendingConfirmation(key, question, bundle.decision().jobPostingId()));
        }
    }

    private static boolean resolved(CandidateFacts facts, CandidateProfile profile, CandidateFactKey key) {
        if (!facts.isConfirmed(key)) return false;
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
