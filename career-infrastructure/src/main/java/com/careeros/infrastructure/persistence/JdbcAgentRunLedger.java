package com.careeros.infrastructure.persistence;

import com.careeros.application.agent.AgentRunLedger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Agent 运行台账。一次运行的三类记录在同一个事务里写入：要么整份都在，要么都不在。 */
@Repository
public class JdbcAgentRunLedger implements AgentRunLedger {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<Integer>> INTEGERS = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> ARGUMENTS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> DATA = new TypeReference<>() {};
    private static final TypeReference<List<EvidenceRef>> EVIDENCE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;

    public JdbcAgentRunLedger(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void record(RunRecord run) {
        jdbc.update("""
            insert into agent_run
            (id, candidate_profile_id, session_id, user_question, planner_identity, outcome, narrative,
             asked_question, violations, grounded_on, budget_spent, budget_limit, started_at, finished_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            """, run.runId(), run.candidateId(), run.sessionId(), run.userQuestion(), run.plannerIdentity(),
            run.outcome(), run.narrative(), run.askedQuestion(), json(run.violations()), json(run.groundedOn()),
            run.budgetSpent(), run.budgetLimit(), Timestamp.from(run.startedAt()), Timestamp.from(run.finishedAt()));
        for (StepRecord step : run.steps()) {
            jdbc.update("""
                insert into agent_run_step
                (run_id, step_index, tool, arguments, why, accepted, reason, budget_units, observation_index,
                 observation_ok, observation_summary, observation_data, evidence)
                values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, run.runId(), step.index(), step.tool(), json(step.arguments()), step.why(), step.accepted(),
                step.reason(), step.budgetUnits(), step.observationIndex(), step.observationOk(),
                step.observationSummary(), json(step.observationData()), json(step.evidence()));
        }
        for (ModelTurnRecord turn : run.modelTurns()) {
            jdbc.update("""
                insert into agent_model_turn (run_id, turn_index, prompt_sha256, state_text, raw_response)
                values (?, ?, ?, ?, ?)
                """, run.runId(), turn.index(), turn.promptSha256(), turn.state(), turn.response());
        }
    }

    @Override
    public List<RunSummary> recent(UUID candidateId, int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        return jdbc.query("""
            select run.id, run.session_id, run.user_question, run.planner_identity, run.outcome, run.started_at,
                   (select count(*) from agent_run_step step where step.run_id = run.id),
                   (select coalesce(sum(jsonb_array_length(step.evidence)), 0)
                      from agent_run_step step where step.run_id = run.id)
            from agent_run run
            where run.candidate_profile_id = ?
            order by run.started_at desc, run.id
            limit ?
            """, (rs, row) -> new RunSummary(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(7), rs.getInt(8),
                rs.getTimestamp(6).toInstant()), candidateId, limit);
    }

    @Override
    public Optional<RunRecord> find(UUID candidateId, UUID runId) {
        var steps = jdbc.query("""
            select step_index, tool, arguments::text, why, accepted, reason, budget_units, observation_index,
                   observation_ok, observation_summary, observation_data::text, evidence::text
            from agent_run_step where run_id = ? order by step_index
            """, (rs, row) -> new StepRecord(rs.getInt(1), rs.getString(2), read(rs.getString(3), ARGUMENTS),
                rs.getString(4), rs.getBoolean(5), rs.getString(6), rs.getInt(7), integer(rs, 8), bool(rs, 9),
                rs.getString(10), read(rs.getString(11), DATA), read(rs.getString(12), EVIDENCE)), runId);
        var turns = jdbc.query("""
            select turn_index, prompt_sha256, state_text, raw_response
            from agent_model_turn where run_id = ? order by turn_index
            """, (rs, row) -> new ModelTurnRecord(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)),
            runId);
        return jdbc.query("""
            select id, candidate_profile_id, session_id, user_question, planner_identity, outcome, narrative,
                   asked_question, violations::text, grounded_on::text, budget_spent, budget_limit,
                   started_at, finished_at
            from agent_run where id = ? and candidate_profile_id = ?
            """, rs -> rs.next() ? Optional.of(new RunRecord(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), read(rs.getString(9), STRINGS), read(rs.getString(10), INTEGERS), rs.getInt(11),
                rs.getInt(12), rs.getTimestamp(13).toInstant(), rs.getTimestamp(14).toInstant(), steps, turns))
                : Optional.<RunRecord>empty(), runId, candidateId);
    }

    private static Integer integer(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean bool(ResultSet rs, int column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法把台账字段序列化为 JSON", failure);
        }
    }

    private static <T> T read(String value, TypeReference<T> type) {
        try {
            return JSON.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("台账里的 JSON 读不出来", failure);
        }
    }
}
