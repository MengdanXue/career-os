package com.careeros.application;

import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OpportunityTier;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一轮对话记住的东西。
 *
 * <p>四样都不是可有可无的便利：
 *
 * <p><b>筛选条件。</b> 用户说过"只看杭州编制岗"，下一句"还有别的吗"必须落在同一个范围里，
 * 否则系统会拿另一批岗位回答同一个问题。
 *
 * <p><b>上次的岗位 ID 顺序。</b> 用户说"第二个怎么样"，指的是他屏幕上那一份列表的第二个。
 * 重新排一次名次可能已经变了——不记住顺序，系统会拿着另一个岗位认真回答，而且看不出错。
 * 所以序号只在这份顺序里解析，从不重新排名。
 *
 * <p><b>待确认事项。</b> 系统问过什么、为哪个岗位问的，要能在用户回答时对上；
 * 否则一句"是的"无从归属。
 *
 * <p><b>资料版本。</b> 用户是在某一版资料下看到那份列表和那些问题的。资料一旦变了，
 * 名次和结论都可能变，旧的序号与旧的待确认问题都不能继续用——
 * 这也是确认回答时那个乐观版本检查的来源。
 */
public record AgentSession(
    UUID sessionId,
    UUID candidateId,
    SessionFilters filters,
    List<UUID> lastJobIdsInOrder,
    List<PendingConfirmation> pendingConfirmations,
    String profileVersion,
    /**
     * 还没做完的那件事：系统追问时，用户原本要办的是什么。
     *
     * <p>不记的话，他刷新之后只答一句"余杭"，系统既不知道他要办什么，
     * 也不知道自己问过什么——那一问等于白问，他得从头再说一遍。
     */
    String openTask,
    /** 系统问过他的那句话。刷新之后要能原样摆回去，而不是让他猜自己在回答什么。 */
    String pendingQuestion,
    Instant updatedAt
) {
    public AgentSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(filters, "filters");
        lastJobIdsInOrder = lastJobIdsInOrder == null ? List.of() : List.copyOf(lastJobIdsInOrder);
        pendingConfirmations = pendingConfirmations == null ? List.of() : List.copyOf(pendingConfirmations);
        if (profileVersion == null || profileVersion.isBlank()) {
            throw new IllegalArgumentException("profileVersion is required");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (lastJobIdsInOrder.size() != lastJobIdsInOrder.stream().distinct().count()) {
            // 同一个岗位出现两次，序号就不再唯一指向一个岗位。
            throw new IllegalArgumentException("lastJobIdsInOrder must not repeat a job");
        }
    }

    /** 没有未完成任务的一轮。绝大多数调用点是这种。 */
    public AgentSession(UUID sessionId, UUID candidateId, SessionFilters filters, List<UUID> lastJobIdsInOrder,
                        List<PendingConfirmation> pendingConfirmations, String profileVersion, Instant updatedAt) {
        this(sessionId, candidateId, filters, lastJobIdsInOrder, pendingConfirmations, profileVersion,
            null, null, updatedAt);
    }

    /**
     * 解析"第 N 个"。
     *
     * @param ordinal 用户说的序号，从 1 开始
     * @return 那份列表里的岗位；序号越界时为空。序号永远在这份记下来的顺序里解析，
     *         不会重新排名——重新排名得到的"第二个"可能是另一个岗位，而用户看不出来。
     */
    public Optional<UUID> jobAtOrdinal(int ordinal) {
        if (ordinal < 1 || ordinal > lastJobIdsInOrder.size()) return Optional.empty();
        return Optional.of(lastJobIdsInOrder.get(ordinal - 1));
    }

    /** 这份列表是否还是用户当时看到的那一份。资料变了就不是了。 */
    public boolean matchesProfileVersion(String currentProfileVersion) {
        return profileVersion.equals(currentProfileVersion);
    }

    public Optional<PendingConfirmation> pendingFor(CandidateFactKey factKey) {
        return pendingConfirmations.stream().filter(item -> item.factKey() == factKey).findFirst();
    }

    /**
     * 只换资料版本，保留岗位顺序与待确认事项。
     *
     * <p>用户自己的一次确认改了资料版本，但他看到的那份列表和那些问题还是同一批，
     * 序号仍然指向同一个岗位——所以不能顺手把顺序清掉。
     */
    public AgentSession withProfileVersion(String profileVersion, Instant at) {
        return new AgentSession(sessionId, candidateId, filters, lastJobIdsInOrder,
            pendingConfirmations, profileVersion, openTask, pendingQuestion, at);
    }

    /**
     * 记下这一轮查出来的新列表。
     *
     * <p><b>不碰"还没做完的事"。</b> 早先这里顺手把它清掉，理由是"这一轮给出了结果，
     * 那件事就算办完了"——可是查出新列表和办完那件事根本不是一回事。
     * 用户问"宁波有什么合适的"，系统查了、一个都没查到，于是反问"要不要放宽范围"：
     * 这一轮既有新范围，也仍然欠着那件事。顺手清掉，他刷新之后那句追问就没了，
     * 答一句"杭州"也没人知道是在回答什么。办完与否由调用方判断，用 {@link #withTaskDone}。
     */
    public AgentSession withListing(
        SessionFilters filters, List<UUID> jobIds, List<PendingConfirmation> pending,
        String profileVersion, Instant at
    ) {
        return new AgentSession(sessionId, candidateId, filters, jobIds, pending, profileVersion,
            openTask, pendingQuestion, at);
    }

    /** 只记下"还没做完的事"和"问过什么"，列表、范围与待确认项原样保留。 */
    public AgentSession withOpenTask(String openTask, String pendingQuestion, Instant at) {
        return new AgentSession(sessionId, candidateId, filters, lastJobIdsInOrder,
            pendingConfirmations, profileVersion, blankToNull(openTask), blankToNull(pendingQuestion), at);
    }

    /**
     * 那件事办完了。
     *
     * <p>只有真的办完才调它：清早了，用户下一句会被当成一个孤立的新问题；
     * 该清不清，一件已经办完的事会被下一轮当成还欠着的，反复接着做。
     */
    public AgentSession withTaskDone(Instant at) {
        return new AgentSession(sessionId, candidateId, filters, lastJobIdsInOrder,
            pendingConfirmations, profileVersion, null, null, at);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 还欠着一件事没做完。刷新之后据此把原来的任务和那句追问接回去。 */
    public boolean hasOpenTask() { return openTask != null && !openTask.isBlank(); }

    /**
     * @param limit 这一轮取了几个。记下来是因为"还有别的吗"要接着往下取，而不是从头再来。
     */
    public record SessionFilters(OpportunityTier tier, String location, JobFamily jobFamily, int limit) {
        /** 动态运行不由用户指定每页取几个，用这个值记账。 */
        public static final int DEFAULT_LIMIT = 5;

        public SessionFilters {
            if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between 1 and 20");
        }
    }

    /**
     * @param jobPostingId 是哪个岗位提出的这个问题。同一个字段可能被多个岗位问到，
     *        但用户的回答是给这个人的资料的，不是给某个岗位的——记下来只为把问题说清楚。
     */
    public record PendingConfirmation(CandidateFactKey factKey, String question, UUID jobPostingId) {
        public PendingConfirmation {
            Objects.requireNonNull(factKey, "factKey");
            if (question == null || question.isBlank()) throw new IllegalArgumentException("question is required");
            Objects.requireNonNull(jobPostingId, "jobPostingId");
        }
    }
}
