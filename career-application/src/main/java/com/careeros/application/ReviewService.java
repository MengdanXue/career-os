package com.careeros.application;

import static com.careeros.application.ExtractionPorts.*;
import static com.careeros.domain.DomainEnums.ReviewDecision;

import com.careeros.domain.RecruitmentExtractionProposal;
import com.careeros.domain.ReviewAction;
import com.careeros.domain.ReviewIssue;
import com.careeros.domain.DomainEnums.ReviewStatus;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ReviewService {
    private final ReviewPersistence persistence;
    private final ProposalValidator validator;
    private final EvidenceVerifier evidenceVerifier;
    private final VerifiedProposalWriter writer;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public ReviewService(
        ReviewPersistence persistence,
        ProposalValidator validator,
        EvidenceVerifier evidenceVerifier,
        VerifiedProposalWriter writer,
        UnitOfWork unitOfWork,
        Clock clock
    ) {
        this.persistence = Objects.requireNonNull(persistence);
        this.validator = Objects.requireNonNull(validator);
        this.evidenceVerifier = Objects.requireNonNull(evidenceVerifier);
        this.writer = Objects.requireNonNull(writer);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.clock = Objects.requireNonNull(clock);
    }

    public ReviewDetails act(ApplyReviewActionCommand command) {
        Objects.requireNonNull(command, "command");
        ReviewDetails details = persistence.findById(command.reviewId());
        if (details.item().version() != command.expectedVersion()) {
            throw new ExtractionExceptions.ReviewConflictException(
                "Review version conflict: expected " + command.expectedVersion() + " but was " + details.item().version());
        }
        if (details.item().status() != ReviewStatus.PENDING) {
            throw new ExtractionExceptions.ReviewConflictException("Review is already resolved");
        }
        return switch (command.decision()) {
            case CONFIRM -> verifyAndResolve(details, details.item().proposal(), command);
            case CORRECT -> verifyAndResolve(details, requireCorrectedPayload(command), command);
            case REJECT -> applyWithoutJobWrite(details, details.item().proposal(), command);
            case NEED_MORE_EVIDENCE -> applyWithoutJobWrite(details, details.item().proposal(), command);
        };
    }

    public ReviewDetails find(UUID id) { return persistence.findById(id); }
    public ReviewPage findPage(ReviewStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Invalid page request");
        return persistence.findPage(status, page, size);
    }

    private ReviewDetails verifyAndResolve(
        ReviewDetails details,
        RecruitmentExtractionProposal proposal,
        ApplyReviewActionCommand command
    ) {
        validator.validate(proposal);
        List<ReviewIssue> issues = evidenceVerifier.verify(proposal, details.fragments());
        if (!issues.isEmpty()) {
            throw new ExtractionExceptions.InvalidProposalException("Corrected proposal still has unsupported restrictive facts");
        }
        ReviewResolution resolution = resolution(details, proposal, command);
        return unitOfWork.execute(() -> {
            writer.write(proposal, List.of(details.run().evidenceId()));
            return persistence.apply(resolution);
        });
    }

    private ReviewDetails applyWithoutJobWrite(
        ReviewDetails details,
        RecruitmentExtractionProposal proposal,
        ApplyReviewActionCommand command
    ) {
        return persistence.apply(resolution(details, proposal, command));
    }

    private ReviewResolution resolution(
        ReviewDetails details,
        RecruitmentExtractionProposal proposal,
        ApplyReviewActionCommand command
    ) {
        ReviewAction action = new ReviewAction(
            UUID.randomUUID(), command.reviewId(), command.decision(), command.expectedVersion(),
            payloadSummary(details.item().proposal()),
            command.correctedPayload() == null ? Map.of() : payloadSummary(command.correctedPayload()),
            command.note(), clock.instant());
        return new ReviewResolution(details.item(), action, proposal);
    }

    private static RecruitmentExtractionProposal requireCorrectedPayload(ApplyReviewActionCommand command) {
        if (command.correctedPayload() == null) {
            throw new ExtractionExceptions.InvalidProposalException("CORRECT requires correctedPayload");
        }
        return command.correctedPayload();
    }

    private static Map<String, Object> payloadSummary(RecruitmentExtractionProposal proposal) {
        return Map.of(
            "schemaVersion", proposal.schemaVersion(),
            "sourceUrl", proposal.source().sourceUrl(),
            "confidence", proposal.confidence(),
            "jobCount", proposal.jobs().size());
    }
}
