package com.careeros.infrastructure.persistence;

import com.careeros.domain.RecruitmentLifecycle;
import com.careeros.domain.RecruitmentLifecycle.Stage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficialLifecycleDocumentService {
    private static final double MIN_CONTAINMENT_RATIO = 0.75d;
    private static final int MIN_CONTAINED_STEM_LENGTH = 12;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OfficialLifecycleDocumentService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public Optional<Result> recordIfLifecycle(
        String title,
        String sourceUrl,
        int recruitmentYear,
        LocalDate publishedOn,
        UUID evidenceId
    ) {
        Set<Stage> stages = RecruitmentLifecycle.classify(title);
        if (stages.isEmpty()) return Optional.empty();
        String campaignStem = RecruitmentLifecycle.campaignStem(title);
        List<Candidate> matches = matchingCandidates(recruitmentYear, sourceUrl, campaignStem);
        MatchStatus status = matches.size() == 1 ? MatchStatus.MATCHED
            : matches.isEmpty() ? MatchStatus.UNMATCHED : MatchStatus.AMBIGUOUS;
        UUID eventId = status == MatchStatus.MATCHED ? matches.getFirst().id() : null;
        String basis = matchBasis(campaignStem, matches, status);
        for (Stage stage : stages) {
            upsert(title, sourceUrl, recruitmentYear, publishedOn, evidenceId, stage, status, eventId, basis);
            if (eventId != null) updateEventStage(eventId, stage, title, sourceUrl);
        }
        return Optional.of(new Result(status, eventId, stages.size()));
    }

    private List<Candidate> matchingCandidates(int year, String sourceUrl, String targetStem) {
        if (targetStem.isEmpty()) return List.of();
        List<Candidate> matches = new ArrayList<>();
        for (Candidate candidate : jdbc.query("""
            select id, title, source_url from recruitment_event
            where recruitment_year=? and source_url<>?
            """, (row, ignored) -> new Candidate(
                row.getObject("id", UUID.class), row.getString("title"), row.getString("source_url")), year, sourceUrl)) {
            String candidateStem = RecruitmentLifecycle.campaignStem(candidate.title());
            if (matches(targetStem, candidateStem)) matches.add(candidate);
        }
        return collapseDuplicateArtifacts(matches);
    }

    private static List<Candidate> collapseDuplicateArtifacts(List<Candidate> candidates) {
        if (candidates.size() < 2) return List.copyOf(candidates);
        String title = candidates.getFirst().title();
        if (candidates.stream().anyMatch(candidate -> !candidate.title().equals(title))) {
            return List.copyOf(candidates);
        }
        List<Candidate> htmlNotices = candidates.stream()
            .filter(candidate -> candidate.sourceUrl().toLowerCase(java.util.Locale.ROOT).endsWith(".html"))
            .toList();
        boolean remainingAreDownloads = candidates.stream()
            .filter(candidate -> !htmlNotices.contains(candidate))
            .allMatch(candidate -> candidate.sourceUrl().contains("/document/download"));
        return htmlNotices.size() == 1 && remainingAreDownloads
            ? List.of(htmlNotices.getFirst()) : List.copyOf(candidates);
    }

    private static boolean matches(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return false;
        if (left.equals(right)) return true;
        String shorter = left.length() <= right.length() ? left : right;
        String longer = left.length() <= right.length() ? right : left;
        return shorter.length() >= MIN_CONTAINED_STEM_LENGTH
            && longer.contains(shorter)
            && (double) shorter.length() / longer.length() >= MIN_CONTAINMENT_RATIO;
    }

    private static String matchBasis(String stem, List<Candidate> matches, MatchStatus status) {
        if (stem.isEmpty()) return "CAMPAIGN_STEM_MISSING";
        if (status == MatchStatus.UNMATCHED) return "NO_CAMPAIGN_CANDIDATE";
        if (status == MatchStatus.AMBIGUOUS) return "MULTIPLE_CAMPAIGN_CANDIDATES:" + matches.size();
        String candidateStem = RecruitmentLifecycle.campaignStem(matches.getFirst().title());
        return stem.equals(candidateStem) ? "EXACT_CAMPAIGN_STEM" : "CONTAINED_CAMPAIGN_STEM";
    }

    private void upsert(
        String title, String sourceUrl, int year, LocalDate publishedOn, UUID evidenceId,
        Stage stage, MatchStatus status, UUID eventId, String basis
    ) {
        UUID id = UUID.nameUUIDFromBytes((sourceUrl + "|" + stage.name()).getBytes(StandardCharsets.UTF_8));
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            insert into recruitment_lifecycle_document
                (id, source_url, title, recruitment_year, published_on, stage, evidence_id,
                 matched_event_id, match_status, match_basis, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict on constraint uk_recruitment_lifecycle_source_stage do update set
                title=excluded.title,
                recruitment_year=excluded.recruitment_year,
                published_on=excluded.published_on,
                evidence_id=excluded.evidence_id,
                matched_event_id=excluded.matched_event_id,
                match_status=excluded.match_status,
                match_basis=excluded.match_basis,
                updated_at=excluded.updated_at
            """, id, sourceUrl, title, year, publishedOn, stage.name(), evidenceId,
            eventId, status.name(), basis, now, now);
    }

    private void updateEventStage(UUID eventId, Stage stage, String title, String sourceUrl) {
        String detail = title + " · " + sourceUrl;
        switch (stage) {
            case QUALIFICATION_REVIEW -> jdbc.update(
                "update recruitment_event set qualification_review_state='CONFIRMED', updated_at=now() where id=?",
                eventId);
            case WRITTEN_EXAM -> jdbc.update(
                "update recruitment_event set written_exam_state='CONFIRMED', updated_at=now() where id=?",
                eventId);
            case SCORE_RESULT -> jdbc.update(
                "update recruitment_event set written_exam_state='CONFIRMED', updated_at=now() where id=?",
                eventId);
            case INTERVIEW -> jdbc.update(
                "update recruitment_event set interview_state='CONFIRMED', interview_rule=?, updated_at=now() where id=?",
                detail, eventId);
            case PHYSICAL_EXAM -> jdbc.update(
                "update recruitment_event set physical_exam_state='CONFIRMED', physical_exam_rule=?, updated_at=now() where id=?",
                detail, eventId);
            case INVESTIGATION -> jdbc.update(
                "update recruitment_event set investigation_state='CONFIRMED', investigation_rule=?, updated_at=now() where id=?",
                detail, eventId);
            case PUBLICATION -> jdbc.update(
                "update recruitment_event set publication_state='CONFIRMED', publication_rule=?, updated_at=now() where id=?",
                detail, eventId);
            case APPOINTMENT -> jdbc.update(
                "update recruitment_event set appointment_state='CONFIRMED', appointment_rule=?, updated_at=now() where id=?",
                detail, eventId);
        }
    }

    private record Candidate(UUID id, String title, String sourceUrl) {}

    public record Result(MatchStatus status, UUID matchedEventId, int stageCount) {
        public Result {
            Objects.requireNonNull(status);
            if (stageCount < 1) throw new IllegalArgumentException("stageCount must be positive");
            if ((status == MatchStatus.MATCHED) != (matchedEventId != null)) {
                throw new IllegalArgumentException("matchedEventId must agree with status");
            }
        }
    }

    public enum MatchStatus { MATCHED, UNMATCHED, AMBIGUOUS }
}
