package com.careeros.application;

import com.careeros.application.DecisionPorts.JobContext;
import com.careeros.application.DecisionPorts.JobContexts;
import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.application.JobAdmissionPorts.JobFieldEvidence;
import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.JobAdmissionReason;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import com.careeros.domain.JobAdmission;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 复核人确认一个岗位在不在目标范围内——可信机会池唯一的入口。
 *
 * <p>自动准入对目标技术岗只给到 {@code NEEDS_REVIEW}，而进入可信池要 {@code VERIFIED + INCLUDED}。
 * 在这之前，全仓库没有任何一条路径会写入 {@code INCLUDED}：采到多少岗位，可信池都是零。
 *
 * <p>这一步<b>不降低门槛</b>，只补上"人的判断"：
 * <ul>
 *   <li>纳入只对自动准入已判为数据质量 {@code VERIFIED} 的岗位开放——关键字段都有官方证据、
 *       没有冲突、用工身份已知。证据不全时人不能替证据作保。</li>
 *   <li>规则排除的岗位（派遣、博士限定、教师等）不能被纳入；那是产品基线的排除项，不是自动分类拿不准。</li>
 *   <li>请求必须带上复核人看到的内容指纹。岗位在他看的时候之后变了，这个决定就不算数——
 *       他确认的是旧内容。</li>
 *   <li>每个决定追加进台账，不改写。岗位内容此后再变化，数据库触发器照旧撤销准入。</li>
 * </ul>
 */
public final class AdmissionScopeReviewService {
    public static final String EVALUATOR_VERSION = "admission-human-scope-v1";

    private final JobContexts jobs;
    private final JobAdmissions admissions;
    private final JobFieldEvidence evidence;
    private final ScopeDecisionLedger ledger;
    private final Clock clock;

    public AdmissionScopeReviewService(JobContexts jobs, JobAdmissions admissions, JobFieldEvidence evidence,
                                       ScopeDecisionLedger ledger, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 调用方负责事务：取锁、改准入、写台账必须在同一个事务里。 */
    public Outcome decide(Command command) {
        Objects.requireNonNull(command, "command");
        JobContext context = jobs.findByJobIdForUpdate(command.jobId())
            .filter(JobContext::active)
            .orElseThrow(() -> new DecisionExceptions.JobNotFoundException(
                "没有这个岗位，或它已下线：" + command.jobId()));
        if (!context.contentFingerprint().equals(command.contentFingerprint())) {
            throw new StaleContentException("岗位内容在你查看之后已经变化，请刷新后按新内容重新确认。");
        }
        // 锁住准入行：两个复核人同时点，后一个要看到前一个的结果，而不是各写各的。
        admissions.findByJobIdForUpdate(command.jobId());

        Instant now = clock.instant();
        var automated = OfficialJobAdmissionService.automatedAssessment(
            context.job(), evidence.coverage(command.jobId()), now);
        if (command.decision() == Decision.INCLUDE) {
            var blockers = blockers(context, automated);
            if (!blockers.isEmpty()) throw new NotAdmissibleException(blockers);
        }

        var reasons = EnumSet.noneOf(JobAdmissionReason.class);
        reasons.addAll(automated.reasonCodes());
        reasons.add(command.decision() == Decision.INCLUDE
            ? JobAdmissionReason.HUMAN_SCOPE_INCLUDED : JobAdmissionReason.HUMAN_SCOPE_EXCLUDED);
        var scope = command.decision() == Decision.INCLUDE ? TargetScopeStatus.INCLUDED : TargetScopeStatus.EXCLUDED;
        var saved = admissions.save(new JobAdmission(command.jobId(), automated.dataQualityStatus(), scope,
            reasons, EVALUATOR_VERSION, now, true));

        var recorded = new RecordedDecision(UUID.randomUUID(), command.jobId(), command.decision(),
            command.contentFingerprint(), command.reviewer(), command.note(),
            saved.dataQualityStatus(), saved.targetScopeStatus(), now);
        ledger.append(recorded);
        return new Outcome(saved, recorded);
    }

    public List<RecordedDecision> history(UUID jobId) {
        return ledger.findByJobId(Objects.requireNonNull(jobId, "jobId"));
    }

    /**
     * 不能纳入的原因，逐条说清。
     *
     * <p>全部来自自动准入那一份结论，不在这里另判：人工这一侧另写规则，迟早会和自动准入对不上。
     */
    static List<String> blockers(JobContext context, JobAdmission automated) {
        var blockers = new ArrayList<String>();
        if (automated.targetScopeStatus() == TargetScopeStatus.EXCLUDED) {
            blockers.add("按规则属于排除项（" + automated.reasonCodes().stream()
                .filter(reason -> reason != JobAdmissionReason.OFFICIAL_WORKBOOK_PARSED)
                .map(Enum::name).sorted().reduce((a, b) -> a + "、" + b).orElse("规则排除") + "）");
        }
        if (context.job().employmentType() == EmploymentType.UNKNOWN) {
            blockers.add("用工身份未知，不能进入可信机会池");
        }
        if (automated.dataQualityStatus() != DataQualityStatus.VERIFIED) {
            var missing = new ArrayList<String>();
            if (automated.reasonCodes().contains(JobAdmissionReason.CONFLICTING_FIELD_EVIDENCE)) missing.add("官方字段互相矛盾");
            if (automated.reasonCodes().contains(JobAdmissionReason.MISSING_FIELD_EVIDENCE)) missing.add("关键字段缺少官方证据");
            if (automated.reasonCodes().contains(JobAdmissionReason.OFFICIAL_FACTS_INCOMPLETE)) missing.add("关键事实不完整");
            blockers.add("数据质量为 " + automated.dataQualityStatus().name()
                + (missing.isEmpty() ? "" : "：" + String.join("、", missing))
                + "；只有证据齐全（VERIFIED）的岗位才能纳入");
        }
        return List.copyOf(blockers);
    }

    public enum Decision { INCLUDE, EXCLUDE }

    public record Command(UUID jobId, Decision decision, String contentFingerprint, String reviewer, String note) {
        public Command {
            Objects.requireNonNull(jobId, "jobId");
            if (decision == null) throw new IllegalArgumentException("decision 必须是 INCLUDE 或 EXCLUDE");
            if (contentFingerprint == null || contentFingerprint.isBlank()) {
                throw new IllegalArgumentException("contentFingerprint 必填：要说明你确认的是哪一版岗位内容");
            }
            if (reviewer == null || reviewer.isBlank()) throw new IllegalArgumentException("reviewer is required");
            note = note == null || note.isBlank() ? null : note.strip();
            if (note != null && note.length() > 500) throw new IllegalArgumentException("note 不能超过 500 字");
        }
    }

    public record RecordedDecision(
        UUID decisionId, UUID jobPostingId, Decision decision, String contentFingerprint, String reviewer,
        String note, DataQualityStatus resultingQuality, TargetScopeStatus resultingScope, Instant decidedAt
    ) {}

    public record Outcome(JobAdmission admission, RecordedDecision decision) {
        /** 这次决定之后，它是否真的在可信池里。 */
        public boolean admitted() { return admission.admitted(); }
    }

    /** 追加式台账。只增不改：谁、何时、依据哪一版内容、做了什么决定。 */
    public interface ScopeDecisionLedger {
        void append(RecordedDecision decision);
        List<RecordedDecision> findByJobId(UUID jobId);
    }

    public static final class StaleContentException extends RuntimeException {
        public StaleContentException(String message) { super(message); }
    }

    public static final class NotAdmissibleException extends RuntimeException {
        private final List<String> blockers;
        public NotAdmissibleException(List<String> blockers) {
            super("这个岗位现在不能纳入可信机会池：" + String.join("；", blockers));
            this.blockers = List.copyOf(blockers);
        }
        public List<String> blockers() { return blockers; }
    }
}
