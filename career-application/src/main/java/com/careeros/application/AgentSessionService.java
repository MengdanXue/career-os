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
     * 记下一轮动态运行的结果：这一轮的范围、列表顺序和还没答的问题。
     *
     * <p>与 {@link #remember} 的区别是它不需要 {@code DecisionBundle}——动态运行拿到的是工具
     * 已经算好的岗位顺序，再评估一遍只会多一次逐岗扇出，而且可能算出和用户屏幕上不同的结果。
     *
     * <p><b>只读入不写回，会话就永远停在第一轮。</b> 用户在"余杭"那一轮看到的是新列表，
     * 下一句"第二个"却解析回上一轮的杭州列表，系统一本正经地讲另一个岗位，而他看不出来。
     */
    public AgentSession rememberRun(UUID sessionId, UUID candidateId, SessionFilters filters,
                                    List<UUID> jobIdsInOrder, List<PendingConfirmation> pending) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(candidateId, "candidateId");
        var profile = candidates.findById(candidateId).orElseThrow(() ->
            new CandidateProfileService.CandidateProfileNotFoundException("Candidate not found: " + candidateId));
        var existing = sessions.find(sessionId).orElse(null);
        if (existing != null && !existing.candidateId().equals(candidateId)) {
            throw new IllegalArgumentException("session belongs to another candidate");
        }
        var order = jobIdsInOrder == null ? List.<UUID>of() : jobIdsInOrder.stream().distinct().toList();
        var items = pending == null ? List.<PendingConfirmation>of() : pending;
        var session = existing == null
            ? new AgentSession(sessionId, candidateId, filters, order, items,
                profile.profileVersion(), clock.instant())
            : existing.withListing(filters, order, items, profile.profileVersion(), clock.instant());
        return sessions.save(session);
    }

    /**
     * 一轮以追问收场时，记下"还没做完的事"和"问过什么"。
     *
     * <p>不记的话，用户刷新之后只答一句"余杭"，系统既不知道他原本要办什么，
     * 也不知道自己问过他什么——那一问等于白问，他得从头再说一遍。
     *
     * <p>刻意只动这两个字段：这一轮没有查出新列表，上一轮的范围、顺序和待确认项
     * 还是用户屏幕上那一份，清掉它们下一句"第二个"就没有东西可指了。
     */
    public AgentSession rememberAsk(UUID sessionId, UUID candidateId, String openTask, String question) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(candidateId, "candidateId");
        var existing = find(candidateId, sessionId).orElse(null);
        if (existing != null) {
            return sessions.save(existing.withOpenTask(openTask, question, clock.instant()));
        }
        // 第一轮就以追问收场时也要开一轮会话，否则最先问出去的那一句永远接不回来：
        // 用户刷新之后页面什么都没有，他刚被问的那句话和他原本要办的事一起消失了。
        var profile = candidates.findById(candidateId).orElseThrow(() ->
            new CandidateProfileService.CandidateProfileNotFoundException("Candidate not found: " + candidateId));
        var opened = new AgentSession(sessionId, candidateId,
            new SessionFilters(null, null, null, SessionFilters.DEFAULT_LIMIT),
            List.of(), List.of(), profile.profileVersion(), clock.instant());
        return sessions.save(opened.withOpenTask(openTask, question, clock.instant()));
    }

    /**
     * 把"第 N 个"解析回岗位。
     *
     * @return 解析结果。资料已变时返回 {@link Reference#staleListing()}——不重新排名，
     *         因为重新排出来的第 N 个可能是另一个岗位，而用户无从察觉。
     */
    public Reference resolveOrdinal(UUID candidateId, UUID sessionId, int ordinal) {
        var session = find(candidateId, sessionId).orElse(null);
        // 不是本人的会话与"没有这轮会话"对外一视同仁，不泄露"存在但不是你的"。
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
        return sessions.find(sessionId).filter(session -> session.candidateId().equals(candidateId));
    }

    /** 会话不存在，或不属于这个候选人。两者对外一视同仁，不泄露"存在但不是你的"。 */
    public static final class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String message) { super(message); }
    }

    /**
     * 用户要回答某个待确认事项时，取出他当时看到的资料版本。
     *
     * <p>确认写入用的乐观版本检查要以"用户看到问题时的版本"为准，而不是"此刻库里的版本"——
     * 后者永远等于当前值，那个检查就恒真，等于没检查。
     *
     * <p><b>要带候选人。</b> 会话 ID 是可猜的 UUID；不核对归属的话，报上别人的会话 ID
     * 就能拿它记着的版本去过自己的版本检查，而那个版本跟本人的资料毫无关系。
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
     * <p>同样要带候选人：推进别人会话里的版本，会让那个人的下一次确认静默跳过版本检查。
     *
     * @return 是否真的推进了
     */
    public boolean advanceProfileVersion(UUID candidateId, UUID sessionId, String fromVersion, String toVersion) {
        if (candidateId == null || sessionId == null || fromVersion == null || toVersion == null) return false;
        var session = find(candidateId, sessionId).orElse(null);
        if (session == null || !session.profileVersion().equals(fromVersion)) return false;
        if (fromVersion.equals(toVersion)) return false;
        sessions.save(session.withProfileVersion(toVersion, clock.instant()));
        return true;
    }

    /** 从一组岗位结论里提取待确认事项。工具面也要用它，所以是公开的。 */
    public List<PendingConfirmation> pendingFor(UUID candidateId, List<DecisionPorts.DecisionBundle> decisions) {
        Objects.requireNonNull(candidateId, "candidateId");
        return pending(decisions);
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
