package com.careeros.infrastructure.persistence;

import com.careeros.application.AdmissionScopeReviewService.Decision;
import com.careeros.application.AdmissionScopeReviewService.RecordedDecision;
import com.careeros.application.AdmissionScopeReviewService.ScopeDecisionLedger;
import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 范围决定台账。表上有只增不改的触发器，这里也只提供追加与读取。 */
@Repository
public class JdbcScopeDecisionLedger implements ScopeDecisionLedger {
    private final JdbcTemplate jdbc;

    public JdbcScopeDecisionLedger(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void append(RecordedDecision decision) {
        jdbc.update("""
            insert into job_admission_scope_decision
            (id, job_posting_id, decision, content_fingerprint, reviewer, note,
             resulting_quality, resulting_scope, decided_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, decision.decisionId(), decision.jobPostingId(), decision.decision().name(),
            decision.contentFingerprint(), decision.reviewer(), decision.note(),
            decision.resultingQuality().name(), decision.resultingScope().name(),
            Timestamp.from(decision.decidedAt()));
    }

    @Override
    public List<RecordedDecision> findByJobId(UUID jobId) {
        return jdbc.query("""
            select id, job_posting_id, decision, content_fingerprint, reviewer, note,
                   resulting_quality, resulting_scope, decided_at
            from job_admission_scope_decision
            where job_posting_id = ?
            order by decided_at desc, id
            """, (rs, row) -> new RecordedDecision(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), Decision.valueOf(rs.getString(3)),
                rs.getString(4), rs.getString(5), rs.getString(6),
                DataQualityStatus.valueOf(rs.getString(7)), TargetScopeStatus.valueOf(rs.getString(8)),
                rs.getTimestamp(9).toInstant()), jobId);
    }
}
