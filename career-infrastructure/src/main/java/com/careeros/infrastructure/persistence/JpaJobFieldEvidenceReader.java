package com.careeros.infrastructure.persistence;

import com.careeros.application.JobAdmissionPorts.EvidenceReference;
import com.careeros.application.JobAdmissionPorts.FieldEvidenceCoverage;
import com.careeros.application.JobAdmissionPorts.JobFieldEvidence;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class JpaJobFieldEvidenceReader implements JobFieldEvidence {
    private final JdbcTemplate jdbc;

    public JpaJobFieldEvidenceReader(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public FieldEvidenceCoverage coverage(UUID jobId) {
        return coverage(List.of(jobId)).getOrDefault(jobId, empty());
    }

    @Override
    public Map<UUID, FieldEvidenceCoverage> coverage(Collection<UUID> requestedJobIds) {
        var jobIds = new LinkedHashSet<>(requestedJobIds == null ? List.<UUID>of() : requestedJobIds);
        if (jobIds.isEmpty()) return Map.of();
        var values = new LinkedHashMap<UUID, MutableCoverage>();
        jobIds.forEach(jobId -> values.put(jobId, new MutableCoverage()));
        Object[] parameters = jobIds.toArray();
        String in = placeholders(jobIds.size());

        RowCallbackHandler jobFacts = row -> addFact(values, row.getObject(1, UUID.class),
            row.getString(2), row.getString(3), row.getString(4), row.getString(5),
            row.getString(6), row.getString(7));
        jdbc.query("""
            select fact.job_posting_id, fact.field_name, fact.fact_status,
                   evidence.source_title, evidence.source_url,
                   fragment.locator::text, fragment.verbatim_text
            from job_field_evidence fact
            left join evidence_fragment fragment on fragment.id=fact.evidence_fragment_id
            left join evidence on evidence.id=fragment.evidence_id
            where fact.job_posting_id in (%s)
            """.formatted(in), jobFacts, parameters);

        RowCallbackHandler officialAttachments = row ->
            values.get(row.getObject(1, UUID.class)).officialAttachment = row.getBoolean(2);
        jdbc.query("""
            select job.id,
                   exists (
                     select 1
                     from jsonb_array_elements_text(job.evidence_ids) value
                     join evidence source_evidence on source_evidence.id=value::uuid
                     where source_evidence.evidence_type='OFFICIAL_ATTACHMENT'
                   ) as official_attachment
            from job_posting job
            where job.id in (%s)
            """.formatted(in), officialAttachments, parameters);

        RowCallbackHandler eventFacts = row -> addFact(values, row.getObject(1, UUID.class),
            row.getString(2), row.getString(3), row.getString(4), row.getString(5),
            row.getString(6), row.getString(7));
        jdbc.query("""
            select job.id, fact.field_name, fact.fact_status,
                   evidence.source_title, evidence.source_url,
                   fragment.locator::text, fragment.verbatim_text
            from job_posting job
            join recruitment_event evidence_event
              on evidence_event.id=job.recruitment_event_id
              or evidence_event.source_url=job.source_url
            join recruitment_event_field_evidence fact
              on fact.recruitment_event_id=evidence_event.id
            left join evidence_fragment fragment on fragment.id=fact.evidence_fragment_id
            left join evidence on evidence.id=fragment.evidence_id
            where job.id in (%s)
            """.formatted(in), eventFacts, parameters);

        var result = new LinkedHashMap<UUID, FieldEvidenceCoverage>();
        values.forEach((jobId, value) -> result.put(jobId, value.freeze()));
        return Map.copyOf(result);
    }

    private static void addFact(
        Map<UUID, MutableCoverage> values,
        UUID jobId,
        String field,
        String status,
        String sourceTitle,
        String sourceUrl,
        String locator,
        String excerpt
    ) {
        MutableCoverage value = values.get(jobId);
        if (value == null) return;
        if ("EXPLICIT".equals(status)) value.explicit.add(field);
        if ("NOT_REQUIRED".equals(status)) value.notRequired.add(field);
        if ("CONFLICT".equals(status)) value.conflicts.add(field);
        value.references.add(new EvidenceReference(field, status, sourceTitle, sourceUrl, locator, excerpt));
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static FieldEvidenceCoverage empty() {
        return new FieldEvidenceCoverage(Set.of(), Set.of(), false, false, false, Set.of(), List.of());
    }

    private static final class MutableCoverage {
        final Set<String> explicit = new LinkedHashSet<>();
        final Set<String> conflicts = new LinkedHashSet<>();
        final Set<String> notRequired = new LinkedHashSet<>();
        final List<EvidenceReference> references = new ArrayList<>();
        boolean officialAttachment;

        FieldEvidenceCoverage freeze() {
            boolean deadline = explicit.contains("applicationEndsAt")
                && !conflicts.contains("applicationEndsAt");
            boolean employment = (explicit.contains("defaultEmploymentType")
                    || explicit.contains("employmentType"))
                && !conflicts.contains("defaultEmploymentType")
                && !conflicts.contains("employmentType");
            return new FieldEvidenceCoverage(
                explicit, conflicts, officialAttachment, deadline, employment, notRequired, references);
        }
    }
}
