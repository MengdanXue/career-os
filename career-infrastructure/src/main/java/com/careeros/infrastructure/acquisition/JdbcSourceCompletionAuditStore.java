package com.careeros.infrastructure.acquisition;

import com.careeros.application.SourceCompletionAuditPorts.AuditSnapshots;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcSourceCompletionAuditStore implements AuditSnapshots {
    private final JdbcTemplate jdbc;
    public JdbcSourceCompletionAuditStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void append(UUID id, UUID parentId, UUID registrySnapshotId, int fromYear, int toYear,
        String coverageThrough, Instant cutoffAt, Instant assessedAt, String registryHash,
        String assessorVersion, String payload) {
        jdbc.update("""
            insert into source_completion_audit_snapshot
            (audit_snapshot_id,parent_snapshot_id,registry_snapshot_id,schema_version,assessor_version,registry_hash,from_year,to_year,coverage_through,cutoff_at,assessed_at,payload)
            values (?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
            """, id, parentId, registrySnapshotId, 1, assessorVersion, registryHash, fromYear, toYear,
            coverageThrough, Timestamp.from(cutoffAt), Timestamp.from(assessedAt), payload);
    }

    @Override public Optional<String> find(UUID id) {
        return jdbc.query("select payload::text from source_completion_audit_snapshot where audit_snapshot_id = ?", rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), id);
    }
    @Override public Optional<String> findLatest(int fromYear, int toYear) {
        return jdbc.query("select payload::text from source_completion_audit_snapshot where from_year = ? and to_year = ? order by assessed_at desc, audit_snapshot_id desc limit 1", rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), fromYear, toYear);
    }
}
