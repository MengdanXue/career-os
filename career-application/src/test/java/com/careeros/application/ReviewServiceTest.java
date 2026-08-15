package com.careeros.application;

import static com.careeros.application.ExtractionPorts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewServiceTest {
    @Test
    void confirmWritesJobsAndResolvesReviewAtomically() {
        Harness harness = new Harness(2L);

        ReviewDetails result = harness.service.act(new ApplyReviewActionCommand(
            harness.reviewId(), ReviewDecision.CONFIRM, 2L, null, "证据已核对"));

        assertThat(result.item().status()).isEqualTo(ReviewStatus.RESOLVED);
        assertThat(result.run().status()).isEqualTo(DataQualityStatus.VERIFIED);
        assertThat(harness.writer.calls).isEqualTo(1);
        assertThat(harness.unitOfWork.calls).isEqualTo(1);
    }

    @Test
    void correctionWritesCorrectedProposalAndResolvesReview() {
        Harness harness = new Harness(1L);

        ReviewDetails result = harness.service.act(new ApplyReviewActionCommand(
            harness.reviewId(), ReviewDecision.CORRECT, 1L, Fixtures.verifiedProposal(), "人工修正"));

        assertThat(result.item().status()).isEqualTo(ReviewStatus.RESOLVED);
        assertThat(harness.writer.calls).isEqualTo(1);
        assertThat(result.item().actions().getFirst().originalPayload())
            .extracting(com.careeros.domain.ReviewPayload::proposal)
            .extracting(com.careeros.domain.RecruitmentExtractionProposal::jobs)
            .isEqualTo(Fixtures.verifiedProposal().jobs());
        assertThat(result.item().actions().getFirst().correctedPayload())
            .extracting(com.careeros.domain.ReviewPayload::proposal)
            .extracting(com.careeros.domain.RecruitmentExtractionProposal::jobs)
            .isEqualTo(Fixtures.verifiedProposal().jobs());
    }

    @Test
    void correctionCannotChangeImmutableSourceMetadata() {
        Harness harness = new Harness(0L);
        var valid = Fixtures.verifiedProposal();
        var alteredSource = new com.careeros.domain.RecruitmentExtractionProposal.SourceProposal(
            java.util.UUID.randomUUID(), "https://attacker.invalid/other", "unrelated source");
        var altered = new com.careeros.domain.RecruitmentExtractionProposal(
            valid.schemaVersion(), alteredSource, valid.organization(), valid.recruitmentEvent(),
            valid.jobs(), valid.warnings(), valid.confidence(), valid.completeSnapshot());

        assertThatThrownBy(() -> harness.service.act(new ApplyReviewActionCommand(
            harness.reviewId(), ReviewDecision.CORRECT, 0L, altered, "altered source")))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class)
            .hasMessageContaining("source");
        assertThat(harness.writer.calls).isZero();
    }

    @Test
    void rejectResolvesWithoutWritingJobs() {
        Harness harness = new Harness(0L);

        ReviewDetails result = harness.service.act(new ApplyReviewActionCommand(
            harness.reviewId(), ReviewDecision.REJECT, 0L, null, "公告不在范围内"));

        assertThat(result.item().status()).isEqualTo(ReviewStatus.RESOLVED);
        assertThat(result.run().status()).isEqualTo(DataQualityStatus.REJECTED);
        assertThat(harness.writer.calls).isZero();
    }

    @Test
    void needMoreEvidenceAppendsActionAndKeepsPending() {
        Harness harness = new Harness(3L);

        ReviewDetails result = harness.service.act(new ApplyReviewActionCommand(
            harness.reviewId(), ReviewDecision.NEED_MORE_EVIDENCE, 3L, null, "补充用工性质原文"));

        assertThat(result.item().status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(result.item().version()).isEqualTo(4L);
        assertThat(result.item().actions()).hasSize(1);
        assertThat(harness.writer.calls).isZero();
    }

    @Test
    void staleVersionFailsBeforeAnyWrite() {
        Harness harness = new Harness(4L);

        assertThatThrownBy(() -> harness.service.act(new ApplyReviewActionCommand(
            harness.reviewId(), ReviewDecision.CONFIRM, 3L, null, "过期页面提交")))
            .isInstanceOf(ExtractionExceptions.ReviewConflictException.class);
        assertThat(harness.writer.calls).isZero();
        assertThat(harness.unitOfWork.calls).isZero();
    }

    @Test
    void correctionRequiresCorrectedPayload() {
        Harness harness = new Harness(0L);

        assertThatThrownBy(() -> harness.service.act(new ApplyReviewActionCommand(
            harness.reviewId(), ReviewDecision.CORRECT, 0L, null, "缺少修正内容")))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class);
    }

    private static final class Harness {
        final Fixtures.MemoryReviewPersistence persistence;
        final Fixtures.RecordingWriter writer = new Fixtures.RecordingWriter();
        final Fixtures.RecordingUnitOfWork unitOfWork = new Fixtures.RecordingUnitOfWork();
        final ReviewService service;

        Harness(long version) {
            persistence = new Fixtures.MemoryReviewPersistence(Fixtures.pendingReviewDetails(version));
            service = new ReviewService(
                persistence,
                proposal -> {},
                (proposal, fragments) -> List.of(),
                writer,
                unitOfWork,
                Fixtures.CLOCK);
        }

        java.util.UUID reviewId() { return persistence.findPage(ReviewStatus.PENDING, 0, 1).items().getFirst().id(); }
    }
}
