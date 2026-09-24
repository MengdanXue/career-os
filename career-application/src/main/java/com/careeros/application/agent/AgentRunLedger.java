package com.careeros.application.agent;

import com.careeros.application.agent.AgentTooling.Observation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 运行台账：每次运行、每一步工具调用、每一轮模型往返，以及每一步引用的官方证据。
 *
 * <p>响应里的轨迹只活在那一次 HTTP 应答里，事后既审计不了也回放不了。v1.0 要求"记录 Agent 每一步
 * 工具调用和证据来源"，所以运行结束时整份落库：
 * <ul>
 *   <li>规划器身份——是真实模型（哪个模型、哪个端点）还是录制回放；</li>
 *   <li>每一步调了什么、参数、理由、是否被执行器接受、拒绝原因、花了多少预算、结果摘要与数据；</li>
 *   <li>每一步引用了哪些证据：岗位所在的官方文件、字段原文片段与定位、规则引用的片段 ID；</li>
 *   <li>模型规划器的每一轮：提示的哈希、发给模型的状态、模型原样返回的文字——可以据此回放。</li>
 * </ul>
 */
public interface AgentRunLedger {

    void record(RunRecord run);

    /** 最近的运行，新的在前。 */
    List<RunSummary> recent(UUID candidateId, int limit);

    /** 一次运行的完整记录；不存在或不属于这个候选人时为空。 */
    Optional<RunRecord> find(UUID candidateId, UUID runId);

    /**
     * @param userQuestion 用户问的那句话
     * @param askedQuestion 这一轮以追问收场时，问出去的那句话（程序渲染、已过校验）
     * @param narrative 通过校验的收尾叙述；被拒时为空
     */
    record RunRecord(UUID runId, UUID candidateId, UUID sessionId, String userQuestion, String plannerIdentity,
                     String outcome, String narrative, String askedQuestion, List<String> violations,
                     List<Integer> groundedOn, int budgetSpent, int budgetLimit, Instant startedAt,
                     Instant finishedAt, List<StepRecord> steps, List<ModelTurnRecord> modelTurns) {
        public RunRecord {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(candidateId, "candidateId");
            if (userQuestion == null || userQuestion.isBlank()) throw new IllegalArgumentException("userQuestion is required");
            if (plannerIdentity == null || plannerIdentity.isBlank()) throw new IllegalArgumentException("plannerIdentity is required");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(finishedAt, "finishedAt");
            violations = List.copyOf(violations == null ? List.of() : violations);
            groundedOn = List.copyOf(groundedOn == null ? List.of() : groundedOn);
            steps = List.copyOf(steps == null ? List.of() : steps);
            modelTurns = List.copyOf(modelTurns == null ? List.of() : modelTurns);
        }

        /** 这次运行引用过的全部证据，去重后按首次出现的顺序。 */
        public List<EvidenceRef> evidence() {
            var all = new LinkedHashSet<EvidenceRef>();
            steps.forEach(step -> all.addAll(step.evidence()));
            return List.copyOf(all);
        }

        /**
         * 从执行器的一次运行拼出台账记录。
         *
         * <p>步骤与观测按执行器记下的观测序号对位，不按"第几个被接受的"数：被拒的调用同样留下一条
         * 失败观测，数着对位会错开一格。
         */
        public static RunRecord of(UUID runId, UUID candidateId, UUID sessionId, String userQuestion,
                                   String plannerIdentity, AgentExecutor.AgentRun run, Instant startedAt,
                                   Instant finishedAt, List<ModelPlanner.RecordedTurn> turns) {
            Objects.requireNonNull(run, "run");
            var steps = new ArrayList<StepRecord>();
            var observations = run.observations();
            int index = 0;
            for (AgentExecutor.TraceEntry entry : run.trace()) {
                Observation observation = entry.hasObservation() && entry.observationIndex() <= observations.size()
                    ? observations.get(entry.observationIndex() - 1) : null;
                steps.add(new StepRecord(++index, entry.tool(), entry.arguments(), entry.why(), entry.accepted(),
                    entry.reason(), entry.budgetUnits(), entry.hasObservation() ? entry.observationIndex() : null,
                    observation == null ? null : observation.ok(),
                    observation == null ? null : observation.summary(),
                    observation == null ? Map.of() : observation.data(),
                    observation == null || !observation.ok() ? List.of() : evidenceOf(observation)));
            }
            var modelTurns = new ArrayList<ModelTurnRecord>();
            for (int turn = 0; turn < (turns == null ? 0 : turns.size()); turn++) {
                var recorded = turns.get(turn);
                modelTurns.add(new ModelTurnRecord(turn + 1, sha256(recorded.protocol()), recorded.state(),
                    recorded.response()));
            }
            return new RunRecord(runId, candidateId, sessionId, userQuestion, plannerIdentity,
                run.outcome().name(), run.narrative(), run.question(), run.violations(), run.groundedOn(),
                run.budgetSpent(), run.budgetLimit(), startedAt, finishedAt, steps, modelTurns);
        }
    }

    /**
     * @param observationIndex 这一步对应第几条观测，从 1 开始；规划器层面的条目（没给出计划等）为空
     * @param observationData  工具返回的结构化数据，原样保存
     * @param evidence         这一步引用的官方证据；失败的观测没有证据
     */
    record StepRecord(int index, String tool, Map<String, String> arguments, String why, boolean accepted,
                      String reason, int budgetUnits, Integer observationIndex, Boolean observationOk,
                      String observationSummary, Map<String, Object> observationData, List<EvidenceRef> evidence) {
        public StepRecord {
            arguments = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(arguments == null ? Map.of() : arguments));
            observationData = observationData == null ? Map.of() : observationData;
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }
    }

    /**
     * 一条证据引用。
     *
     * @param kind OFFICIAL_SOURCE（岗位来自哪份官方文件：有岗位表附件时是附件，否则是公告页）、
     *             FIELD（某个字段的官方原文片段）、
     *             RULE（某条资格规则引用的证据片段）
     */
    record EvidenceRef(String kind, String jobPostingId, String field, String sourceUrl, String locator,
                       String excerpt, String evidenceFragmentId) {
        public EvidenceRef {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** @param promptSha256 系统提示（格式说明 + 工具目录）的 SHA-256；全文每轮都一样，存哈希足以核对 */
    record ModelTurnRecord(int index, String promptSha256, String state, String response) {}

    record RunSummary(UUID runId, UUID sessionId, String userQuestion, String plannerIdentity, String outcome,
                      int stepCount, int evidenceCount, Instant startedAt) {}

    /**
     * 从一条成功观测里取出它引用的证据。只认工具约定的结构化字段，不从摘要文字里猜。
     */
    @SuppressWarnings("unchecked")
    static List<EvidenceRef> evidenceOf(Observation observation) {
        var refs = new LinkedHashSet<EvidenceRef>();
        var data = observation.data();
        if (data.get("jobs") instanceof List<?> jobs) {
            for (Object row : jobs) {
                if (row instanceof Map<?, ?> map) officialSource((Map<String, Object>) map, refs);
            }
        }
        String jobId = data.get("jobPostingId") == null ? null : String.valueOf(data.get("jobPostingId"));
        officialSource(data, refs);
        var fragmentSources = new LinkedHashMap<String, EvidenceRef>();
        if (data.get("evidence") instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> map)) continue;
                var typed = (Map<String, Object>) map;
                var ref = new EvidenceRef("FIELD", jobId, text(typed.get("field")), text(typed.get("sourceUrl")),
                    text(typed.get("locator")), text(typed.get("excerpt")), text(typed.get("evidenceFragmentId")));
                refs.add(ref);
                if (ref.evidenceFragmentId() != null) fragmentSources.put(ref.evidenceFragmentId(), ref);
            }
        }
        if (data.get("rules") instanceof List<?> rules) {
            for (Object row : rules) {
                if (!(row instanceof Map<?, ?> map)) continue;
                var typed = (Map<String, Object>) map;
                if (!(typed.get("evidenceFragmentIds") instanceof List<?> fragments)) continue;
                for (Object fragment : fragments) {
                    String id = String.valueOf(fragment);
                    var source = fragmentSources.get(id);
                    refs.add(new EvidenceRef("RULE", jobId, text(typed.get("rule")),
                        source == null ? null : source.sourceUrl(), source == null ? null : source.locator(),
                        source == null ? null : source.excerpt(), id));
                }
            }
        }
        return List.copyOf(refs);
    }

    private static void officialSource(Map<String, Object> row, java.util.Set<EvidenceRef> refs) {
        Object url = row.get("officialSourceUrl");
        Object job = row.get("jobPostingId");
        if (url == null || String.valueOf(url).isBlank() || job == null) return;
        refs.add(new EvidenceRef("OFFICIAL_SOURCE", String.valueOf(job), null, String.valueOf(url), null, null, null));
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }

    static String sha256(String value) {
        if (value == null) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
