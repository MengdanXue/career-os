package com.careeros;

import com.careeros.application.ExtractionPorts.ApplyReviewActionCommand;
import com.careeros.application.ExtractionPorts.ExtractionResult;
import com.careeros.application.ExtractionPorts.PersistedExtraction;
import com.careeros.domain.RecruitmentExtractionProposal;
import com.careeros.domain.DomainEnums.ReviewDecision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class ExtractionApiModels {
    private ExtractionApiModels() {}

    record ExtractionMetadataRequest(
        String sourceUrl,
        String sourceTitle,
        Instant capturedAt,
        UUID organizationId,
        UUID recruitmentEventId,
        boolean requireModel
    ) {}

    record ExtractionResponse(
        UUID id,
        String status,
        boolean reused,
        UUID evidenceId,
        UUID reviewId,
        RecruitmentExtractionProposal proposal,
        String errorCode,
        String errorMessage
    ) {
        static ExtractionResponse from(ExtractionResult result) {
            return from(result.run(), result.reviewId().orElse(null), result.reused());
        }

        static ExtractionResponse from(PersistedExtraction extraction) {
            return from(extraction.run(), extraction.reviewId().orElse(null), false);
        }

        private static ExtractionResponse from(
            com.careeros.domain.ExtractionRun run,
            UUID reviewId,
            boolean reused
        ) {
            return new ExtractionResponse(
                run.id(), run.status().name(), reused, run.evidenceId(), reviewId,
                run.proposedPayload(), run.errorCode(), run.errorMessage());
        }
    }

    record ApplyReviewActionRequest(
        ReviewDecision decision,
        long expectedVersion,
        RecruitmentExtractionProposal correctedPayload,
        String note
    ) {
        /**
         * actor 由调用方的认证主体决定，不是请求体的一部分——
         * 否则任何人都能在审计记录里署上别人的名字。
         */
        ApplyReviewActionCommand toCommand(UUID reviewId, String actor) {
            if (decision == null) throw new IllegalArgumentException("decision is required");
            if (decision == ReviewDecision.CORRECT && correctedPayload == null) {
                throw new IllegalArgumentException("CORRECT requires correctedPayload");
            }
            return new ApplyReviewActionCommand(
                reviewId, decision, expectedVersion, correctedPayload, note, actor);
        }
    }

    record PageResponse<T>(List<T> content, int page, int size, long totalElements) {
        PageResponse {
            content = content == null ? List.of() : List.copyOf(content);
        }
    }
}
