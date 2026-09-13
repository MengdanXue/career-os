package com.careeros.application.personal;

import static com.careeros.domain.CandidateFacts.CandidateFactKey.*;

import com.careeros.application.CandidateProfileService;
import com.careeros.application.RepositoryPorts;
import com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength;
import com.careeros.application.personal.ProfileConfirmationPorts.ConfirmationLedger;
import com.careeros.application.personal.ProfileConfirmationPorts.LedgerEntry;
import com.careeros.application.personal.ProfileConfirmationPorts.Stage;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.DomainEnums.ApplicationTimeStatus;
import com.careeros.domain.DomainEnums.Gender;
import com.careeros.domain.DomainEnums.PoliticalAffiliation;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 把用户对"待确认"问题的回答落成一次完整的闭环：记录 → 按正确证据等级保存 → 重算 → 给出变化。
 *
 * <p>三件事是这个服务存在的理由：
 *
 * <p><b>一、重算由业务服务保证，不靠模型记得再调一次工具。</b> 资料写入与重算在同一次调用里完成，
 * 调用方拿不到"只写了资料、没重算"的中间状态；真的重算失败，返回值会说明结论尚未刷新，
 * 并且带同一把幂等钥匙重试会从重算那一步接着做。
 *
 * <p><b>二、确认回答不会变成官方核实。</b> 这条路径写入的证据等级恒为
 * {@link EvidenceStrength#SELF_REPORTED}，没有任何入参能把它抬到 DOCUMENTED 或 VERIFIED。
 * 需要材料才能判断的字段（学历、工作经历等）在这里直接拒绝，指回证据任务，而不是让一句
 * "我有的"变成硬资格依据。
 *
 * <p><b>三、改写既有答案要先确认。</b> 覆盖一个已记录且不同的值，必须带上确认标记；否则返回
 * 待确认的变更详情，一个字都不写。
 */
public final class ProfileConfirmationService {

    /** 对话里能直接回答的标量字段。其余字段都要材料，不能靠一句话确认。 */
    private static final Set<CandidateFactKey> DECLARABLE = EnumSet.of(
        GENDER, POLITICAL_AFFILIATION, EMPLOYER_SETTLEMENT_AT_APPLICATION, SOCIAL_INSURANCE_AT_APPLICATION
    );

    private final RepositoryPorts.CandidateProfiles profiles;
    private final CandidateProfileService profileService;
    private final CandidateDecisionDiffService diffs;
    private final ConfirmationLedger ledger;
    private final Clock clock;

    public ProfileConfirmationService(
        RepositoryPorts.CandidateProfiles profiles,
        CandidateProfileService profileService,
        CandidateDecisionDiffService diffs,
        ConfirmationLedger ledger,
        Clock clock
    ) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.profileService = Objects.requireNonNull(profileService, "profileService");
        this.diffs = Objects.requireNonNull(diffs, "diffs");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 记录并重算。**只适用于不在事务里的调用方。**
     *
     * <p>放在一个事务里调用会毁掉失败恢复：重算内部的评估会在失败时把整个事务标成
     * rollback-only，于是这里 catch 住异常、返回"已记录但未重算"之后，提交阶段仍然整体回滚——
     * 用户的回答连同台账一起消失，接口返回 500。真机注入一次评估失败即可复现。
     *
     * <p>Web 层因此改用两段式：{@link #recordAnswer} 在事务里提交写入，提交之后再调
     * {@link #completeRecompute}。写入先落库，重算失败也只是"结论还没刷新"。
     */
    public ConfirmationOutcome record(ConfirmationRequest request, LocalDate asOf) {
        var recorded = recordAnswer(request);
        return recorded.needsRecompute() ? completeRecompute(recorded, asOf) : recorded.outcome();
    }

    /**
     * 第一段：校验并写入，不做重算。
     *
     * <p>资料写入与台账写入必须在同一个事务里——两者分开提交，中间崩溃会让重试误判成
     * 尚未写入，于是重复写一次。重算不能进这个事务，理由见 {@link #record}。
     */
    public RecordedAnswer recordAnswer(ConfirmationRequest request) {
        return recordAnswerInternal(request);
    }

    /**
     * 第二段：重算并给出变化。必须在第一段的事务**提交之后**调用。
     */
    public ConfirmationOutcome completeRecompute(RecordedAnswer recorded, LocalDate asOf) {
        Objects.requireNonNull(recorded, "recorded");
        Objects.requireNonNull(asOf, "asOf");
        if (!recorded.needsRecompute()) return recorded.outcome();
        return recompute(recorded.entry(), asOf, recorded.message(), recorded.replayed());
    }

    private RecordedAnswer recordAnswerInternal(ConfirmationRequest request) {
        Objects.requireNonNull(request, "request");
        var current = profiles.findById(request.candidateId()).orElseThrow(() ->
            new CandidateProfileService.CandidateProfileNotFoundException(
                "Candidate not found: " + request.candidateId()));

        var replay = ledger.find(request.candidateId(), request.idempotencyKey()).orElse(null);
        if (replay != null) return replay(replay, request);

        if (!DECLARABLE.contains(request.factKey())) {
            return RecordedAnswer.rejected(refuse(current, Result.REQUIRES_DOCUMENT,
                "这一项要凭材料判断，不能只凭一句回答就当作已确认。请在资料页补充对应证据。"));
        }
        if (!request.value().matches(request.factKey())) {
            return RecordedAnswer.rejected(refuse(current, Result.REQUIRES_DOCUMENT,
                "回答的类型与「" + label(request.factKey()) + "」不符，没有写入任何内容。"));
        }
        if (!current.profileVersion().equals(request.expectedProfileVersion())) {
            // 乐观版本检查：资料在这次对话期间被改过，此刻写入会静默覆盖别处的修改。
            return RecordedAnswer.rejected(refuse(current, Result.PROFILE_VERSION_CHANGED,
                "资料在你回答期间已变更（你看到的是 " + request.expectedProfileVersion()
                    + "，当前是 " + current.profileVersion() + "），没有写入任何内容，请重新确认。"));
        }

        var updated = request.value().applyTo(current);
        String recordedBefore = recordedValue(current, request.factKey());
        String declared = request.value().canonical();
        if (recordedBefore.equals(declared) && confirmed(request.candidateId(), request.factKey())) {
            return RecordedAnswer.rejected(new ConfirmationOutcome(Result.NO_CHANGE_NEEDED,
                EvidenceStrength.SELF_REPORTED, current.profileVersion(), current.profileVersion(),
                "「" + label(request.factKey()) + "」已经是这个答案且已确认，没有重复写入。", null, null));
        }
        if (!recordedBefore.equals(declared) && !isUnset(recordedBefore) && !request.acknowledgedChange()) {
            // 覆盖一个不同的既有答案要先说清楚改的是什么，不能顺手改掉。
            return RecordedAnswer.rejected(new ConfirmationOutcome(Result.CHANGE_REQUIRES_ACKNOWLEDGEMENT,
                EvidenceStrength.SELF_REPORTED, current.profileVersion(), current.profileVersion(),
                "这会把「" + label(request.factKey()) + "」从「" + recordedBefore + "」改成「" + declared
                    + "」。改完要重算才知道各岗位结论有没有变，现在还不能说会变成什么。",
                new DeclaredChange(request.factKey(), recordedBefore, declared), null));
        }
        String before = current.profileVersion();
        profileService.saveDraft(request.candidateId(), updated);
        // 确认写的是 USER_CONFIRMED（本人声明），这条路径没有任何入参能写成官方核实。
        var confirmed = profileService.confirm(request.candidateId(), Set.of(request.factKey()));
        String after = confirmed.profile().profileVersion();
        var entry = ledger.save(new LedgerEntry(request.candidateId(), request.idempotencyKey(),
            request.factKey(), declared, Stage.WRITTEN, before, after, clock.instant()));
        return RecordedAnswer.written(entry,
            "已按你本人的声明记录「" + label(request.factKey()) + "：" + declared + "」。这是自述信息，不是官方核实结果。");
    }

    /**
     * 重放一次已经记录过的回答。
     *
     * <p>不第二次写资料，也不第二次推高版本——差异是按台账里的对照基线重新算出来的，
     * 同一把钥匙拿到的是同一个结论。
     */
    private RecordedAnswer replay(LedgerEntry entry, ConfirmationRequest request) {
        if (!entry.factKey().equals(request.factKey()) || !entry.declaredValue().equals(request.value().canonical())) {
            return RecordedAnswer.rejected(new ConfirmationOutcome(Result.IDEMPOTENCY_KEY_REUSED,
                EvidenceStrength.SELF_REPORTED, entry.profileVersionBefore(), entry.profileVersionAfter(),
                "这把幂等钥匙已经用于记录「" + label(entry.factKey()) + "：" + entry.declaredValue()
                    + "」，不能用同一把钥匙记录另一个答案。", null, null));
        }
        if (entry.stage() == Stage.WRITTEN) {
            // 上次写入成功、重算没做完。从重算接着做，而不是重新写一遍资料。
            return RecordedAnswer.written(entry, "上次的回答已经记录，这次补完了岗位结论的重算。");
        }
        return RecordedAnswer.replayed(entry, "这条回答此前已经记录过，没有重复写入。");
    }

    /**
     * 重算并给出变化。
     *
     * <p>重算失败不回滚已记录的回答——用户的声明是他给的事实，不该因为算不动就丢掉。
     * 返回值明说结论尚未刷新，台账停在 {@link Stage#WRITTEN}，重试会从这里接着做。
     */
    private ConfirmationOutcome recompute(LedgerEntry entry, LocalDate asOf, String message, boolean replayed) {
        DecisionChangeSummary changes;
        try {
            changes = diffs.recompute(entry.candidateId(), entry.profileVersionBefore(), asOf);
        } catch (RuntimeException failure) {
            return new ConfirmationOutcome(Result.RECORDED_RECOMPUTE_DEFERRED, EvidenceStrength.SELF_REPORTED,
                entry.profileVersionBefore(), entry.profileVersionAfter(),
                message + " 岗位结论尚未重算完成，稍后重试即可，回答不会重复记录。", null, null);
        }
        if (entry.stage() == Stage.WRITTEN) ledger.save(entry.recomputed());
        var outcome = new ConfirmationOutcome(Result.RECORDED, EvidenceStrength.SELF_REPORTED,
            entry.profileVersionBefore(), entry.profileVersionAfter(), message, null, changes);
        return replayed ? outcome.asAlreadyRecorded() : outcome;
    }

    private boolean confirmed(UUID candidateId, CandidateFactKey key) {
        return profileService.facts(candidateId).statuses().get(key) == CandidateFacts.CandidateFactStatus.CONFIRMED;
    }

    private ConfirmationOutcome refuse(CandidateProfile current, Result result, String message) {
        return new ConfirmationOutcome(result, EvidenceStrength.SELF_REPORTED,
            current.profileVersion(), current.profileVersion(), message, null, null);
    }

    private static boolean isUnset(String recorded) {
        return recorded.equals(Gender.UNKNOWN.name())
            || recorded.equals(PoliticalAffiliation.UNKNOWN.name())
            || recorded.equals(ApplicationTimeStatus.UNDECLARED.name());
    }

    private static String recordedValue(CandidateProfile profile, CandidateFactKey key) {
        return switch (key) {
            case GENDER -> profile.gender().name();
            case POLITICAL_AFFILIATION -> profile.politicalAffiliation().name();
            case EMPLOYER_SETTLEMENT_AT_APPLICATION -> profile.employerSettlementAtApplication().name();
            case SOCIAL_INSURANCE_AT_APPLICATION -> profile.socialInsuranceAtApplication().name();
            default -> throw new IllegalArgumentException("not a declarable fact: " + key);
        };
    }

    static String label(CandidateFactKey key) {
        return switch (key) {
            case GENDER -> "性别";
            case POLITICAL_AFFILIATION -> "政治面貌";
            case EMPLOYER_SETTLEMENT_AT_APPLICATION -> "报名时是否已落实工作单位";
            case SOCIAL_INSURANCE_AT_APPLICATION -> "报名时社保缴纳状态";
            case EDUCATION_RECORDS -> "学历经历";
            case EMPLOYMENT_HISTORY -> "工作经历";
            case PROFESSIONAL_TITLES -> "专业职称";
            default -> key.name();
        };
    }

    /**
     * 用户能在对话里给出的回答，一个封闭集合。
     *
     * <p>做成封闭类型而不是自由文本，是为了让"回答"与"字段"必须对得上：把性别答案写进政治面貌
     * 这种事在类型上就不成立，不必靠字符串解析去猜。
     */
    public sealed interface DeclaredValue {
        String canonical();
        boolean matches(CandidateFactKey key);
        CandidateProfile applyTo(CandidateProfile profile);

        record OfGender(Gender value) implements DeclaredValue {
            public OfGender { Objects.requireNonNull(value, "value"); }
            @Override public String canonical() { return value.name(); }
            @Override public boolean matches(CandidateFactKey key) { return key == GENDER; }
            @Override public CandidateProfile applyTo(CandidateProfile profile) { return profile.withGender(value); }
        }

        record OfPoliticalAffiliation(PoliticalAffiliation value) implements DeclaredValue {
            public OfPoliticalAffiliation { Objects.requireNonNull(value, "value"); }
            @Override public String canonical() { return value.name(); }
            @Override public boolean matches(CandidateFactKey key) { return key == POLITICAL_AFFILIATION; }
            @Override public CandidateProfile applyTo(CandidateProfile profile) {
                return profile.withPoliticalAffiliation(value);
            }
        }

        record OfApplicationTimeStatus(CandidateFactKey key, ApplicationTimeStatus value) implements DeclaredValue {
            public OfApplicationTimeStatus {
                Objects.requireNonNull(key, "key");
                Objects.requireNonNull(value, "value");
                if (key != EMPLOYER_SETTLEMENT_AT_APPLICATION && key != SOCIAL_INSURANCE_AT_APPLICATION) {
                    throw new IllegalArgumentException("not an application-time fact: " + key);
                }
            }
            @Override public String canonical() { return value.name(); }
            @Override public boolean matches(CandidateFactKey other) { return other == key; }
            @Override public CandidateProfile applyTo(CandidateProfile profile) {
                return key == EMPLOYER_SETTLEMENT_AT_APPLICATION
                    ? profile.withEmployerSettlementAtApplication(value)
                    : profile.withSocialInsuranceAtApplication(value);
            }
        }
    }

    /**
     * @param expectedProfileVersion 用户看到那个问题时的资料版本，用于乐观并发检查
     * @param idempotencyKey         同一次回答重试时必须带同一把钥匙
     * @param acknowledgedChange     是否已确认要覆盖一个不同的既有答案
     */
    public record ConfirmationRequest(
        UUID candidateId,
        CandidateFactKey factKey,
        DeclaredValue value,
        String expectedProfileVersion,
        String idempotencyKey,
        boolean acknowledgedChange
    ) {
        public ConfirmationRequest {
            Objects.requireNonNull(candidateId, "candidateId");
            Objects.requireNonNull(factKey, "factKey");
            Objects.requireNonNull(value, "value");
            if (expectedProfileVersion == null || expectedProfileVersion.isBlank()) {
                throw new IllegalArgumentException("expectedProfileVersion is required");
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey is required");
            }
        }
    }

    /**
     * 第一段的结果。
     *
     * <p>{@code entry} 非空表示资料与台账已经写好，等着在**事务提交之后**重算；
     * 为空表示这次什么都没写（拒绝、无需变更、待确认覆盖），直接返回 {@code outcome}。
     *
     * @param replayed 这次是同一把幂等钥匙的重放，重算之后要报 ALREADY_RECORDED
     */
    public record RecordedAnswer(ConfirmationOutcome outcome, ProfileConfirmationPorts.LedgerEntry entry,
                                 String message, boolean replayed) {
        static RecordedAnswer rejected(ConfirmationOutcome outcome) {
            return new RecordedAnswer(outcome, null, outcome.message(), false);
        }
        static RecordedAnswer written(ProfileConfirmationPorts.LedgerEntry entry, String message) {
            return new RecordedAnswer(null, entry, message, false);
        }
        static RecordedAnswer replayed(ProfileConfirmationPorts.LedgerEntry entry, String message) {
            return new RecordedAnswer(null, entry, message, true);
        }
        public boolean needsRecompute() { return entry != null; }
    }

    public record DeclaredChange(CandidateFactKey factKey, String from, String to) {}

    public enum Result {
        /** 已记录并完成重算。 */
        RECORDED,
        /** 已记录，但重算没做完；带同一把钥匙重试会从重算接着做。 */
        RECORDED_RECOMPUTE_DEFERRED,
        /** 同一把钥匙的重放，没有第二次写入。 */
        ALREADY_RECORDED,
        /** 这把钥匙已经用于另一个答案。 */
        IDEMPOTENCY_KEY_REUSED,
        /** 值和确认状态都没变，什么都没写。 */
        NO_CHANGE_NEEDED,
        /** 会覆盖一个不同的既有答案，需要先确认。 */
        CHANGE_REQUIRES_ACKNOWLEDGEMENT,
        /** 资料在回答期间被改过，什么都没写。 */
        PROFILE_VERSION_CHANGED,
        /** 这一项要凭材料判断，不能靠对话确认。 */
        REQUIRES_DOCUMENT
    }

    /**
     * @param evidenceStrength 这条路径恒为 {@link EvidenceStrength#SELF_REPORTED}：
     *        用户的确认回答是自述信息，任何入参都不能把它抬成官方核实。
     * @param changes          真实重算出来的变化；没有写入或尚未重算时为 {@code null}。
     *        这里只报算出来的结果，不报"确认后会解锁多少个岗位"那种承诺。
     */
    public record ConfirmationOutcome(
        Result result,
        EvidenceStrength evidenceStrength,
        String profileVersionBefore,
        String profileVersionAfter,
        String message,
        DeclaredChange pendingChange,
        DecisionChangeSummary changes
    ) {
        public ConfirmationOutcome {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(evidenceStrength, "evidenceStrength");
            if (evidenceStrength != EvidenceStrength.SELF_REPORTED) {
                throw new IllegalArgumentException("a conversational confirmation is self-reported, never verified");
            }
            Objects.requireNonNull(message, "message");
        }

        ConfirmationOutcome asAlreadyRecorded() {
            return new ConfirmationOutcome(Result.ALREADY_RECORDED, evidenceStrength,
                profileVersionBefore, profileVersionAfter, message, pendingChange, changes);
        }
    }
}
