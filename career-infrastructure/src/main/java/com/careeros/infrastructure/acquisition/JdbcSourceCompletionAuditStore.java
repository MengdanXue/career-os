package com.careeros.infrastructure.acquisition;

import com.careeros.application.SourceCompletionAuditPorts.AuditSnapshots;
import com.careeros.application.SourceCompletionAuditPorts.AuditSnapshots.SnapshotEnvelope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSourceCompletionAuditStore implements AuditSnapshots {
    private final JdbcTemplate jdbc;
    public JdbcSourceCompletionAuditStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void append(UUID id, UUID parentId, UUID registrySnapshotId, int fromYear, int toYear,
        String coverageThrough, Instant cutoffAt, Instant assessedAt, String registryHash,
        String assessorVersion, String payload) {
        jdbc.update("""
            insert into source_completion_audit_snapshot
            (audit_snapshot_id,parent_snapshot_id,registry_snapshot_id,schema_version,assessor_version,registry_hash,from_year,to_year,coverage_through,cutoff_at,assessed_at,payload)
            values (?,?,?,?,?,?,?, ?, ?::date, ?, ?, ?::jsonb)
            """, id, parentId, registrySnapshotId, 1, assessorVersion, registryHash, fromYear, toYear,
            coverageThrough, Timestamp.from(cutoffAt), Timestamp.from(assessedAt), payload);
    }

    @Override public Optional<String> find(UUID id) {
        return jdbc.query("select payload::text from source_completion_audit_snapshot where audit_snapshot_id = ?", rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), id);
    }
    @Override public Optional<String> findLatest(int fromYear, int toYear) {
        return jdbc.query("select payload::text from source_completion_audit_snapshot where from_year = ? and to_year = ? order by assessed_at desc, audit_snapshot_id desc limit 1", rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), fromYear, toYear);
    }

    @Override public Optional<SnapshotEnvelope> findEnvelope(UUID id) {
        return jdbc.query("select audit_snapshot_id,parent_snapshot_id,registry_snapshot_id,from_year,to_year,coverage_through,cutoff_at,assessed_at,registry_hash,assessor_version,payload::text from source_completion_audit_snapshot where audit_snapshot_id = ?", rs -> rs.next() ? Optional.of(new SnapshotEnvelope(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4), rs.getInt(5),
            rs.getObject(6, java.time.LocalDate.class).toString(), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(),
            rs.getString(9), rs.getString(10), rs.getString(11))) : Optional.empty(), id);
    }
}
