package com.careeros.application;

import com.careeros.domain.Evidence;
import com.careeros.domain.EvidenceFragment;
import com.careeros.domain.ExtractionRun;
import com.careeros.domain.ParsedDocument;
import com.careeros.domain.RecruitmentExtractionProposal;
import com.careeros.domain.ReviewAction;
import com.careeros.domain.ReviewIssue;
import com.careeros.domain.ReviewItem;
import com.careeros.domain.SourceArtifact;
import com.careeros.domain.DomainEnums.ReviewDecision;
import com.careeros.domain.DomainEnums.ReviewStatus;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ExtractionPorts {
    private ExtractionPorts() {}

    public interface ArtifactStore {
        SourceArtifact put(byte[] content, String mediaType, Instant capturedAt);
        InputStream open(SourceArtifact artifact) throws IOException;
    }

    public interface DocumentParser {
        ParserDescriptor descriptor();
        boolean supports(String mediaType);
        ParsedDocument parse(SourceArtifact artifact, InputStream input, Evidence evidence) throws IOException;
    }

    public interface DocumentEnrichmentPort {
        ParsedDocument enrich(SourceArtifact artifact, ParsedDocument parsed);
    }

    public interface StructuredExtractor {
        ExtractorDescriptor descriptor();
        ExtractionAttempt extract(ParsedDocument document, ExtractionContext context);
    }

    public interface ProposalValidator {
        void validate(RecruitmentExtractionProposal proposal);
    }

    public interface EvidenceVerifier {
        List<ReviewIssue> verify(RecruitmentExtractionProposal proposal, List<EvidenceFragment> fragments);
    }

    public interface ExtractionPersistence {
        Optional<PersistedExtraction> findByInputFingerprint(String fingerprint);
        PersistedExtraction save(ExtractionBundle bundle);
        PersistedExtraction saveFailure(FailedExtractionBundle bundle);
        PersistedExtraction findById(UUID id);
    }

    public interface FingerprintLock {
        <T> T execute(String fingerprint, Supplier<T> operation);

        default <T> T execute(
            String fingerprint,
            Supplier<T> operation,
            Consumer<RuntimeException> afterRollback
        ) {
            try {
                return execute(fingerprint, operation);
            } catch (RuntimeException failure) {
                afterRollback.accept(failure);
                throw failure;
            }
        }
    }

    public interface ReviewPersistence {
        ReviewDetails findById(UUID id);
        ReviewPage findPage(ReviewStatus status, int page, int size);
        ReviewDetails apply(ReviewResolution resolution);
    }

    public interface VerifiedProposalWriter {
        JobUpsertService.JobUpsertResult write(RecruitmentExtractionProposal proposal, List<UUID> evidenceIds);
    }

    public interface ExtractionObserver {
        void completed(ExtractionRun run, boolean reused, Duration duration);
        void modelCall(String model, String result);
    }

    public interface UnitOfWork {
        <T> T execute(Supplier<T> operation);
    }

    public record SubmitExtractionCommand(
        byte[] content,
        String mediaType,
        String sourceUrl,
        String sourceTitle,
        Instant capturedAt,
        UUID organizationId,
        UUID recruitmentEventId,
        boolean requireModel
    ) {
        public SubmitExtractionCommand {
            content = content == null ? new byte[0] : content.clone();
            requireText(mediaType, "mediaType");
            requireText(sourceUrl, "sourceUrl");
            requireText(sourceTitle, "sourceTitle");
            Objects.requireNonNull(capturedAt, "capturedAt");
        }

        @Override public byte[] content() { return content.clone(); }
    }

    public record ExtractionResult(ExtractionRun run, Optional<UUID> reviewId, boolean reused) {
        public ExtractionResult {
            Objects.requireNonNull(run, "run");
            reviewId = reviewId == null ? Optional.empty() : reviewId;
        }
    }

    public record ApplyReviewActionCommand(
        UUID reviewId,
        ReviewDecision decision,
        long expectedVersion,
        RecruitmentExtractionProposal correctedPayload,
        String note
    ) {
        public ApplyReviewActionCommand {
            Objects.requireNonNull(reviewId, "reviewId");
            Objects.requireNonNull(decision, "decision");
            if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }

    public record ParserDescriptor(String name, String version) {
        public ParserDescriptor { requireText(name, "name"); requireText(version, "version"); }
    }

    public record ExtractorDescriptor(
        String strategy,
        String version,
        String modelName,
        String promptVersion,
        boolean enabled
    ) {
        public ExtractorDescriptor {
            requireText(strategy, "strategy");
            requireText(version, "version");
            requireText(modelName, "modelName");
            requireText(promptVersion, "promptVersion");
        }
    }

    /**
     * @param warnings 抽取器自身发现的、会削弱结果可信度的问题（例如输入被预算截断）。
     *                 非空时 ExtractionService 会强制该次抽取进入人工复核，不允许自动核验。
     */
    public record ExtractionAttempt(
        RecruitmentExtractionProposal proposal,
        String rawResponse,
        List<String> warnings
    ) {
        public ExtractionAttempt {
            Objects.requireNonNull(proposal, "proposal");
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public ExtractionAttempt(RecruitmentExtractionProposal proposal, String rawResponse) {
            this(proposal, rawResponse, List.of());
        }
    }

    public record ExtractionContext(
        Evidence evidence,
        UUID organizationId,
        UUID recruitmentEventId,
        boolean requireModel
    ) {
        public ExtractionContext { Objects.requireNonNull(evidence, "evidence"); }
    }

    public record ExtractionBundle(
        SourceArtifact artifact,
        Evidence evidence,
        ParsedDocument parsed,
        ExtractionRun run,
        ReviewItem review
    ) {
        public ExtractionBundle {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(parsed, "parsed");
            Objects.requireNonNull(run, "run");
        }
    }

    public record FailedExtractionBundle(
        SourceArtifact artifact,
        Evidence evidence,
        ExtractionRun run
    ) {
        public FailedExtractionBundle {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(run, "run");
            if (run.status() != com.careeros.domain.DomainEnums.DataQualityStatus.FAILED) {
                throw new IllegalArgumentException("failed extraction bundle requires FAILED run");
            }
        }
    }

    public record PersistedExtraction(ExtractionRun run, Optional<UUID> reviewId) {
        public PersistedExtraction {
            Objects.requireNonNull(run, "run");
            reviewId = reviewId == null ? Optional.empty() : reviewId;
        }
    }

    public record ReviewDetails(
        ReviewItem item,
        ExtractionRun run,
        List<EvidenceFragment> fragments
    ) {
        public ReviewDetails {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(run, "run");
            fragments = fragments == null ? List.of() : List.copyOf(fragments);
        }
    }

    public record ReviewPage(List<ReviewItem> items, int page, int size, long totalElements) {
        public ReviewPage {
            items = items == null ? List.of() : List.copyOf(items);
            if (page < 0) throw new IllegalArgumentException("page must not be negative");
            if (size < 1) throw new IllegalArgumentException("size must be positive");
            if (totalElements < 0) throw new IllegalArgumentException("totalElements must not be negative");
        }
    }

    public record ReviewResolution(
        ReviewItem current,
        ReviewAction action,
        RecruitmentExtractionProposal resolvedProposal
    ) {
        public ReviewResolution {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(resolvedProposal, "resolvedProposal");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
