package com.careeros.domain;

import static com.careeros.domain.DomainEnums.DataQualityStatus;
import static com.careeros.domain.DomainEnums.FactStatus;
import static com.careeros.domain.DomainEnums.ReviewDecision;
import static com.careeros.domain.DomainEnums.ReviewStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExtractionDomainTest {
    @Test
    void verifiedRunCannotMoveBackToReview() {
        ExtractionRun run = ExtractionFixtures.run(DataQualityStatus.VERIFIED);

        assertThatThrownBy(() -> run.transitionTo(
            DataQualityStatus.REVIEW_REQUIRED,
            Instant.parse("2026-08-14T10:00:00Z")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("VERIFIED");
    }

    @Test
    void normalizedRunCanBecomeVerifiedAndRecordsCompletionTime() {
        ExtractionRun run = ExtractionFixtures.run(DataQualityStatus.NORMALIZED);
        Instant completedAt = Instant.parse("2026-08-14T10:00:00Z");

        ExtractionRun changed = run.transitionTo(DataQualityStatus.VERIFIED, completedAt);

        assertThat(changed.status()).isEqualTo(DataQualityStatus.VERIFIED);
        assertThat(changed.completedAt()).isEqualTo(completedAt);
        assertThat(run.status()).isEqualTo(DataQualityStatus.NORMALIZED);
    }

    @Test
    void needMoreEvidenceKeepsReviewPendingAndAppendsAction() {
        ReviewItem item = ExtractionFixtures.pendingReview(3L);
        ReviewAction action = new ReviewAction(
            UUID.randomUUID(),
            item.id(),
            ReviewDecision.NEED_MORE_EVIDENCE,
            3L,
            Map.of("schemaVersion", "1.0.0"),
            Map.of(),
            "补充用工性质原文",
            Instant.parse("2026-08-14T10:00:00Z"));

        ReviewItem changed = item.apply(action);

        assertThat(changed.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(changed.version()).isEqualTo(4L);
        assertThat(changed.actions()).containsExactly(action);
        assertThat(changed.resolvedAt()).isNull();
    }

    @Test
    void staleReviewActionCannotOverwriteNewerReview() {
        ReviewItem item = ExtractionFixtures.pendingReview(4L);
        ReviewAction stale = new ReviewAction(
            UUID.randomUUID(), item.id(), ReviewDecision.CONFIRM, 3L,
            Map.of(), Map.of(), "确认", Instant.parse("2026-08-14T10:00:00Z"));

        assertThatThrownBy(() -> item.apply(stale))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("version");
    }

    @Test
    void unknownFactCannotCarryAValue() {
        assertThatThrownBy(() -> new ExtractedFact<>(
            "事业编制", FactStatus.UNKNOWN, 0.3, List.of(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("UNKNOWN");
    }

    @Test
    void proposalRejectsUnknownSchemaVersion() {
        RecruitmentExtractionProposal valid = ExtractionFixtures.proposal();

        assertThatThrownBy(() -> new RecruitmentExtractionProposal(
            "2.0.0", valid.source(), valid.organization(), valid.recruitmentEvent(),
            valid.jobs(), valid.warnings(), valid.confidence(), valid.completeSnapshot()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("schemaVersion");
    }
}
