package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.EvidenceType.OFFICIAL_ATTACHMENT;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficialWorkbookEvidenceService {
    public static final String VERSION = "official-workbook-v2";
    private final EvidenceJpaRepository evidence;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public OfficialWorkbookEvidenceService(
        EvidenceJpaRepository evidence,
        JdbcTemplate jdbc,
        ObjectMapper json,
        Clock clock
    ) {
        this.evidence = Objects.requireNonNull(evidence);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public UUID begin(String sourceUrl, String sourceTitle, byte[] content) {
        String hash = sha256(content);
        UUID id = stableId("evidence|" + OfficialWorkbookIdentity.of(sourceUrl) + "|" + hash);
        var existing = evidence.findById(id);
        if (existing.isPresent()) {
            var entity = existing.orElseThrow();
            entity.sourceUrl = sourceUrl;
            entity.sourceTitle = sourceTitle;
            entity.capturedAt = clock.instant();
            evidence.saveAndFlush(entity);
            return id;
        }
        var entity = new JpaModels.EvidenceEntity();
        entity.id = id;
        entity.sourceArtifactId = null;
        entity.evidenceType = OFFICIAL_ATTACHMENT;
        entity.sourceUrl = sourceUrl;
        entity.sourceTitle = sourceTitle;
        entity.excerpt = null;
        entity.contentHash = hash;
        entity.capturedAt = clock.instant();
        evidence.saveAndFlush(entity);
        return id;
    }

    @Transactional
    public void replaceJobFacts(
        UUID evidenceId,
        UUID jobId,
        String sheetName,
        int rowNumber,
        Map<String, String> fields
    ) {
        replaceJobFacts(evidenceId, jobId, sheetName, rowNumber, fields, Map.of());
    }

    @Transactional
    public void replaceJobFacts(
        UUID evidenceId,
        UUID jobId,
        String sheetName,
        int rowNumber,
        Map<String, String> fields,
        Map<String, String> sourceColumnNames
    ) {
        String currentSourceUrl = jdbc.queryForObject(
            "select source_url from evidence where id=?", String.class, evidenceId);
        String currentIdentity = OfficialWorkbookIdentity.of(currentSourceUrl);
        jdbc.query("""
            select distinct source_evidence.id, source_evidence.source_url
            from job_field_evidence fact
            join evidence_fragment fragment on fragment.id=fact.evidence_fragment_id
            join evidence source_evidence on source_evidence.id=fragment.evidence_id
            where fact.job_posting_id=?
            """, row -> {
                UUID previousEvidenceId = row.getObject(1, UUID.class);
                if (currentIdentity.equals(OfficialWorkbookIdentity.of(row.getString(2)))) {
                    jdbc.update("""
                        delete from job_field_evidence fact
                        using evidence_fragment fragment
                        where fact.evidence_fragment_id=fragment.id
                          and fact.job_posting_id=? and fragment.evidence_id=?
                        """, jobId, previousEvidenceId);
                }
            }, jobId);
        for (var entry : new LinkedHashMap<>(fields).entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
            String factStatus = factStatus(entry.getKey(), entry.getValue());
            boolean conflict = !jdbc.query("""
                select fact.normalized_value
                from job_field_evidence fact
                where fact.job_posting_id=? and fact.field_name=?
                  and fact.fact_status in ('EXPLICIT','NOT_REQUIRED','CONFLICT')
                """, (row, index) -> row.getString(1), jobId, entry.getKey()).stream()
                .allMatch(entry.getValue()::equals);
            if (conflict) {
                jdbc.update("""
                    update job_field_evidence set fact_status='CONFLICT'
                    where job_posting_id=? and field_name=?
                    """, jobId, entry.getKey());
            }
            String identity = evidenceId + "|" + sheetName + "|" + rowNumber + "|" + entry.getKey() + "|" + entry.getValue();
            UUID fragmentId = stableId("fragment|" + identity);
            jdbc.update("""
                insert into evidence_fragment
                    (id, evidence_id, locator_type, locator, verbatim_text, content_hash, created_at)
                values (?, ?, 'SPREADSHEET', cast(? as jsonb), ?, ?, ?)
                on conflict (id) do nothing
                """, fragmentId, evidenceId, locator(sheetName, rowNumber,
                    sourceColumnNames.getOrDefault(entry.getKey(), entry.getKey())),
                entry.getValue(), sha256(entry.getValue().getBytes(StandardCharsets.UTF_8)),
                Timestamp.from(clock.instant()));
            jdbc.update("""
                insert into job_field_evidence
                    (id, job_posting_id, field_name, fact_status, evidence_fragment_id,
                     raw_value, normalized_value, extractor_version, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict on constraint uk_job_field_evidence do update set
                    fact_status=excluded.fact_status,
                    raw_value=excluded.raw_value,
                    normalized_value=excluded.normalized_value,
                    extractor_version=excluded.extractor_version,
                    created_at=excluded.created_at
                """, stableId("job-fact|" + jobId + "|" + entry.getKey() + "|" + fragmentId),
                jobId, entry.getKey(), conflict ? "CONFLICT" : factStatus, fragmentId,
                entry.getValue(), entry.getValue(), VERSION,
                Timestamp.from(clock.instant()));
        }
    }

    private String locator(String sheetName, int rowNumber, String columnName) {
        try {
            return json.writeValueAsString(Map.of(
                "sheetName", sheetName,
                "rowNumber", rowNumber,
                "columnName", columnName));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode spreadsheet locator", exception);
        }
    }

    private static String factStatus(String fieldName, String value) {
        String normalized = value.replaceAll("[\\s，,。；;：:]", "").strip();
        if ((fieldName.equals("majorRequirementText") || fieldName.equals("ageRequirementText"))
            && (normalized.equals("不限") || normalized.equals("不限制")
                || normalized.equals("无") || normalized.equals("无要求")
                || normalized.equals("专业不限") || normalized.equals("年龄不限"))) {
            return "NOT_REQUIRED";
        }
        return "EXPLICIT";
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
