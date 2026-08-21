package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.EmploymentType.PUBLIC_INSTITUTION_FORMAL;
import static com.careeros.domain.DomainEnums.EmploymentType.UNKNOWN;
import static com.careeros.domain.DomainEnums.EventType.PUBLIC_INSTITUTION;

import com.careeros.domain.DomainEnums.EventType;
import com.careeros.infrastructure.acquisition.OfficialAnnouncementFactParser.OfficialAnnouncementFacts;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficialAnnouncementFactService {
    private static final String VERSION = "official-announcement-v2";
    private final RecruitmentEventJpaRepository events;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OfficialAnnouncementJobRefreshService jobRefresh;

    public OfficialAnnouncementFactService(RecruitmentEventJpaRepository events) {
        this(events, null, Clock.systemUTC(), null);
    }

    @Autowired
    public OfficialAnnouncementFactService(
        RecruitmentEventJpaRepository events,
        JdbcTemplate jdbc,
        Clock clock,
        @org.springframework.lang.Nullable OfficialAnnouncementJobRefreshService jobRefresh
    ) {
        this.events = Objects.requireNonNull(events);
        this.jdbc = jdbc;
        this.clock = Objects.requireNonNull(clock);
        this.jobRefresh = jobRefresh;
    }

    @Transactional
    public JpaModels.RecruitmentEventEntity upsert(
        String title,
        String sourceUrl,
        int recruitmentYear,
        EventType eventType,
        OfficialAnnouncementFacts facts,
        UUID evidenceId
    ) {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(evidenceId, "evidenceId");
        var event = events.findFirstBySourceUrl(sourceUrl).orElseGet(JpaModels.RecruitmentEventEntity::new);
        if (event.id == null) event.id = UUID.randomUUID();
        event.title = require(title, "title");
        event.sourceUrl = require(sourceUrl, "sourceUrl");
        event.recruitmentYear = recruitmentYear;
        event.eventType = Objects.requireNonNull(eventType, "eventType");
        event.publishedOn = preferIncoming(event.publishedOn, facts.publishedOn());
        event.applicationStartsAt = preferIncoming(event.applicationStartsAt, facts.applicationStartsAt());
        event.applicationEndsAt = preferIncoming(event.applicationEndsAt, facts.applicationEndsAt());
        event.applicationStartsOn = preferIncoming(event.applicationStartsOn,
            facts.applicationStartsAt() == null ? null : facts.applicationStartsAt().toLocalDate());
        event.applicationEndsOn = preferIncoming(event.applicationEndsOn,
            facts.applicationEndsAt() == null ? null : facts.applicationEndsAt().toLocalDate());
        event.ageReferenceDate = preferIncoming(event.ageReferenceDate, facts.ageReferenceDate());
        event.registrationUrl = preferIncoming(event.registrationUrl, facts.registrationUrl());
        event.qualificationReviewEndsOn = preferIncoming(event.qualificationReviewEndsOn, facts.qualificationReviewEndsOn());
        event.paymentEndsOn = preferIncoming(event.paymentEndsOn, facts.paymentEndsOn());
        event.admissionTicketStartsOn = preferIncoming(event.admissionTicketStartsOn, facts.admissionTicketStartsOn());
        event.admissionTicketEndsOn = preferIncoming(event.admissionTicketEndsOn, facts.admissionTicketEndsOn());
        event.writtenExamOn = preferIncoming(event.writtenExamOn, facts.writtenExamOn());
        if (facts.writtenExamSubjects() != null && !facts.writtenExamSubjects().isEmpty()) {
            event.writtenExamSubjects = new ArrayList<>(facts.writtenExamSubjects());
        }
        event.graduateRule = preferIncoming(event.graduateRule, facts.graduateRule());
        event.overseasDegreeRule = preferIncoming(event.overseasDegreeRule, facts.overseasDegreeRule());
        event.experienceEvidenceRule = preferIncoming(event.experienceEvidenceRule, facts.experienceEvidenceRule());
        event.employmentStatement = preferIncoming(event.employmentStatement, facts.employmentStatement());
        event.interviewRule = preferIncoming(event.interviewRule, facts.interviewRule());
        var parsedEmploymentType = facts.employmentStatement() == null
            ? (event.defaultEmploymentType == null ? UNKNOWN : event.defaultEmploymentType)
            : eventType == PUBLIC_INSTITUTION && facts.employmentStatement().contains("签订聘用合同")
                ? PUBLIC_INSTITUTION_FORMAL : UNKNOWN;
        event.defaultEmploymentType = parsedEmploymentType;
        var evidence = new LinkedHashSet<UUID>();
        if (event.evidenceIds != null) evidence.addAll(event.evidenceIds);
        evidence.add(evidenceId);
        event.evidenceIds = new ArrayList<>(evidence);
        // Field-evidence rows are written through JDBC in the same transaction. Flush the
        // event first so PostgreSQL can enforce their foreign key immediately.
        var saved = events.saveAndFlush(event);
        if (jdbc != null) replaceFieldEvidence(saved.id, sourceUrl, facts, evidenceId, parsedEmploymentType);
        if (jobRefresh != null) jobRefresh.refresh(sourceUrl, saved);
        return saved;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private void replaceFieldEvidence(
        UUID eventId,
        String sourceUrl,
        OfficialAnnouncementFacts facts,
        UUID evidenceId,
        Object employmentType
    ) {
        Map<String, String> excerpts = facts.evidenceExcerpts();
        writeFact(eventId, evidenceId, "applicationStartsAt", excerpts.get("applicationPeriod"), facts.applicationStartsAt());
        writeFact(eventId, evidenceId, "applicationEndsAt", excerpts.get("applicationPeriod"), facts.applicationEndsAt());
        writeFact(eventId, evidenceId, "ageReferenceDate", excerpts.get("ageReferenceDate"), facts.ageReferenceDate());
        writeFact(eventId, evidenceId, "employmentStatement", excerpts.get("employmentStatement"), facts.employmentStatement());
        writeFact(eventId, evidenceId, "defaultEmploymentType", excerpts.get("employmentStatement"), employmentType);
    }

    private void writeFact(UUID eventId, UUID evidenceId, String field, String excerpt, Object normalizedValue) {
        if (excerpt == null || excerpt.isBlank() || normalizedValue == null) return;
        String sourceUrl = jdbc.queryForObject(
            "select source_url from evidence where id=?", String.class, evidenceId);
        jdbc.update("""
            delete from recruitment_event_field_evidence fact
            using evidence_fragment fragment, evidence source_evidence
            where fact.evidence_fragment_id=fragment.id
              and fragment.evidence_id=source_evidence.id
              and fact.recruitment_event_id=? and fact.field_name=?
              and source_evidence.source_url=?
            """, eventId, field, sourceUrl);
        UUID fragmentId = UUID.nameUUIDFromBytes(
            (evidenceId + "|" + field + "|" + excerpt).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
            insert into evidence_fragment
                (id, evidence_id, locator_type, locator, verbatim_text, content_hash, created_at)
            values (?, ?, 'HTML', cast(? as jsonb), ?, ?, ?)
            on conflict (id) do nothing
            """, fragmentId, evidenceId, "{\"fieldName\":\"" + field + "\"}", excerpt,
            sha256(excerpt), Timestamp.from(clock.instant()));
        boolean conflict = Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists (
              select 1 from recruitment_event_field_evidence
              where recruitment_event_id=? and field_name=?
                and raw_value is distinct from ? and fact_status in ('EXPLICIT','CONFLICT'))
            """, Boolean.class, eventId, field, normalizedValue.toString()));
        if (conflict) jdbc.update("""
            update recruitment_event_field_evidence set fact_status='CONFLICT'
            where recruitment_event_id=? and field_name=?
            """, eventId, field);
        jdbc.update("""
            insert into recruitment_event_field_evidence
                (id, recruitment_event_id, field_name, fact_status, evidence_fragment_id,
                 raw_value, extractor_version, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict on constraint uk_recruitment_event_field_evidence do update set
                fact_status=excluded.fact_status, raw_value=excluded.raw_value,
                extractor_version=excluded.extractor_version, created_at=excluded.created_at
            """, UUID.nameUUIDFromBytes((eventId + "|" + field + "|" + fragmentId).getBytes(StandardCharsets.UTF_8)),
            eventId, field, conflict ? "CONFLICT" : "EXPLICIT", fragmentId,
            normalizedValue.toString(), VERSION, Timestamp.from(clock.instant()));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static <T> T preferIncoming(T existing, T incoming) {
        return incoming != null ? incoming : existing;
    }
}
