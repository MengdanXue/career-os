package com.careeros.application;

import static com.careeros.application.ExtractionPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.domain.Evidence;
import com.careeros.domain.ExtractionRun;
import com.careeros.domain.ParsedDocument;
import com.careeros.domain.ReviewIssue;
import com.careeros.domain.ReviewItem;
import com.careeros.domain.ReviewPolicy;
import com.careeros.domain.SourceArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ExtractionService {
    public static final String PIPELINE_VERSION = "extraction-projection-v2";
    private final ArtifactStore artifactStore;
    private final DocumentParser parser;
    private final DocumentEnrichmentPort enrichment;
    private final StructuredExtractor extractor;
    private final ProposalValidator validator;
    private final EvidenceVerifier evidenceVerifier;
    private final ExtractionPersistence persistence;
    private final VerifiedProposalWriter writer;
    private final UnitOfWork unitOfWork;
    private final FingerprintLock fingerprintLock;
    private final ExtractionObserver observer;
    private final ReviewPolicy reviewPolicy;
    private final Clock clock;
    private final long maxDocumentBytes;

    public ExtractionService(
        ArtifactStore artifactStore,
        DocumentParser parser,
        DocumentEnrichmentPort enrichment,
        StructuredExtractor extractor,
        ProposalValidator validator,
        EvidenceVerifier evidenceVerifier,
        ExtractionPersistence persistence,
        VerifiedProposalWriter writer,
        UnitOfWork unitOfWork,
        FingerprintLock fingerprintLock,
        ExtractionObserver observer,
        ReviewPolicy reviewPolicy,
        Clock clock,
        long maxDocumentBytes
    ) {
        this.artifactStore = Objects.requireNonNull(artifactStore);
        this.parser = Objects.requireNonNull(parser);
        this.enrichment = Objects.requireNonNull(enrichment);
        this.extractor = Objects.requireNonNull(extractor);
        this.validator = Objects.requireNonNull(validator);
        this.evidenceVerifier = Objects.requireNonNull(evidenceVerifier);
        this.persistence = Objects.requireNonNull(persistence);
        this.writer = Objects.requireNonNull(writer);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.fingerprintLock = Objects.requireNonNull(fingerprintLock);
        this.observer = Objects.requireNonNull(observer);
        this.reviewPolicy = Objects.requireNonNull(reviewPolicy);
        this.clock = Objects.requireNonNull(clock);
        if (maxDocumentBytes < 1) throw new IllegalArgumentException("maxDocumentBytes must be positive");
        this.maxDocumentBytes = maxDocumentBytes;
    }

    public ExtractionResult submit(SubmitExtractionCommand command) {
        Objects.requireNonNull(command, "command");
        byte[] content = command.content();
        if (content.length == 0) throw new ExtractionExceptions.InvalidProposalException("Document is empty");
        if (content.length > maxDocumentBytes) {
            throw new ExtractionExceptions.DocumentTooLargeException("Document exceeds " + maxDocumentBytes + " bytes");
        }
        if (!parser.supports(command.mediaType())) {
            throw new ExtractionExceptions.UnsupportedDocumentException("Unsupported media type: " + command.mediaType());
        }

        Instant startedAt = clock.instant();
        SourceArtifact artifact = artifactStore.put(content, command.mediaType(), command.capturedAt());
        String fingerprint = inputFingerprint(artifact);
        try {
            return fingerprintLock.execute(fingerprint,
                () -> submitLocked(command, artifact, fingerprint, startedAt),
                failure -> persistProcessingFailure(
                    command, artifact, fingerprint, startedAt, failure));
        } catch (ProcessingFailure failure) {
            for (Throwable suppressed : failure.getSuppressed()) {
                failure.original().addSuppressed(suppressed);
            }
            throw failure.original();
        }
    }

    private ExtractionResult submitLocked(
        SubmitExtractionCommand command,
        SourceArtifact artifact,
        String fingerprint,
        Instant startedAt
    ) {
        Optional<PersistedExtraction> existing = persistence.findByInputFingerprint(fingerprint);
        if (existing.isEmpty()) {
            // Human-confirmed/corrected proposals and pending/rejected reviews require explicit re-review.
            existing = persistence.findByInputFingerprint(legacyInputFingerprint(artifact))
                .filter(value -> value.reviewId().isPresent()
                    || value.run().status() == DataQualityStatus.REVIEW_REQUIRED
                    || value.run().status() == DataQualityStatus.REJECTED);
        }
        if (existing.isPresent()) {
            PersistedExtraction value = existing.orElseThrow();
            observer.completed(value.run(), true, Duration.between(startedAt, clock.instant()));
            return new ExtractionResult(value.run(), value.reviewId(), true);
        }

        UUID evidenceId = UUID.randomUUID();
        Evidence evidence = new Evidence(
            evidenceId, artifact.id(), EvidenceType.OFFICIAL_NOTICE,
            command.sourceUrl(), command.sourceTitle(), null, artifact.sha256(), command.capturedAt());
        try {
            return processNew(command, artifact, fingerprint, startedAt, evidence);
        } catch (RuntimeException failure) {
            throw new ProcessingFailure(evidence, failure);
        }
    }

    private ExtractionResult processNew(
        SubmitExtractionCommand command,
        SourceArtifact artifact,
        String fingerprint,
        Instant startedAt,
        Evidence evidence
    ) {
        ParsedDocument parsed;
        try (InputStream input = artifactStore.open(artifact)) {
            parsed = enrichment.enrich(artifact, parser.parse(artifact, input, evidence));
        } catch (IOException exception) {
            throw new IllegalStateException("Document parsing failed", exception);
        }

        ExtractorDescriptor extractorDescriptor = extractor.descriptor();
        if (command.requireModel() && !extractorDescriptor.enabled()) {
            throw new ExtractionExceptions.ModelUnavailableException("A model is required but no model is configured");
        }
        ExtractionAttempt attempt = extractor.extract(
            parsed, new ExtractionContext(evidence, command.organizationId(), command.recruitmentEventId(), command.requireModel()));
        observer.modelCall(extractorDescriptor.modelName(), attempt.rawResponse() == null ? "disabled" : "success");
        var proposal = bindSource(attempt.proposal(), evidence);
        validator.validate(proposal);

        List<ReviewIssue> evidenceIssues = evidenceVerifier.verify(proposal, parsed.fragments());
        ReviewPolicy.Evaluation policy = reviewPolicy.evaluate(parsed, proposal, evidenceIssues.isEmpty());
        UUID runId = UUID.randomUUID();
        UUID reviewId = policy.autoVerified() ? null : policy.reviewItemId();
        List<ReviewIssue> issues = new ArrayList<>(policy.issues());
        if (!evidenceIssues.isEmpty()) {
            if (reviewId == null) reviewId = UUID.randomUUID();
            UUID resolvedReviewId = reviewId;
            evidenceIssues.stream().map(issue -> new ReviewIssue(
                issue.id(), resolvedReviewId, issue.reasonCode(), issue.fieldPath(), issue.message(), issue.evidenceFragmentId()))
                .forEach(issues::add);
        }
        // 抽取器自报的削弱信号（当前只有输入截断）同样必须落成复核项：
        // 只覆盖了部分原文的抽取结果绝不能走自动核验直接写进正式岗位库。
        if (!attempt.warnings().isEmpty()) {
            if (reviewId == null) reviewId = UUID.randomUUID();
            UUID resolvedReviewId = reviewId;
            attempt.warnings().stream()
                .map(warning -> new ReviewIssue(
                    UUID.randomUUID(), resolvedReviewId, ReviewReasonCode.INPUT_TRUNCATED, "document", warning, null))
                .forEach(issues::add);
        }

        DataQualityStatus status = issues.isEmpty() ? DataQualityStatus.VERIFIED : DataQualityStatus.REVIEW_REQUIRED;
        ExtractionRun run = new ExtractionRun(
            runId, evidence.id(), command.organizationId(), command.recruitmentEventId(), fingerprint,
            sourceType(command.mediaType()), parsed.parserName(), parsed.parserVersion(),
            extractorDescriptor.strategy(), extractorDescriptor.version(), extractorDescriptor.modelName(),
            extractorDescriptor.promptVersion(), proposal.schemaVersion(), status,
            proposal.confidence(), proposal, attempt.rawResponse(), null, null,
            startedAt, clock.instant());

        ReviewItem review = null;
        if (status == DataQualityStatus.REVIEW_REQUIRED) {
            review = new ReviewItem(
                Objects.requireNonNull(reviewId), runId, ReviewStatus.PENDING, 0,
                proposal, issues, List.of(), clock.instant(), null);
        }
        ExtractionBundle bundle = new ExtractionBundle(artifact, evidence, parsed, run, review);
        PersistedExtraction saved;
        if (status == DataQualityStatus.VERIFIED) {
            saved = unitOfWork.execute(() -> {
                writer.write(proposal, List.of(evidence.id()));
                return persistence.save(bundle);
            });
        } else {
            saved = persistence.save(bundle);
        }
        observer.completed(saved.run(), false, Duration.between(startedAt, clock.instant()));
        return new ExtractionResult(saved.run(), saved.reviewId(), false);
    }

    public PersistedExtraction find(UUID id) {
        return persistence.findById(id);
    }

    private void persistFailure(
        SubmitExtractionCommand command,
        SourceArtifact artifact,
        String fingerprint,
        Instant startedAt,
        Evidence evidence,
        RuntimeException failure
    ) {
        ParserDescriptor parserDescriptor = parser.descriptor();
        ExtractorDescriptor extractorDescriptor = extractor.descriptor();
        ExtractionRun failed = new ExtractionRun(
            UUID.randomUUID(), evidence.id(), command.organizationId(), command.recruitmentEventId(), fingerprint,
            sourceType(command.mediaType()), parserDescriptor.name(), parserDescriptor.version(),
            extractorDescriptor.strategy(), extractorDescriptor.version(), extractorDescriptor.modelName(),
            extractorDescriptor.promptVersion(), com.careeros.domain.RecruitmentExtractionProposal.SCHEMA_VERSION,
            DataQualityStatus.FAILED, 0, null, null, failure.getClass().getSimpleName(),
            failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage(),
            startedAt, clock.instant());
        try {
            PersistedExtraction saved = persistence.saveFailure(
                new FailedExtractionBundle(artifact, evidence, failed));
            observer.completed(saved.run(), false, Duration.between(startedAt, clock.instant()));
        } catch (RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }

    private void persistProcessingFailure(
        SubmitExtractionCommand command,
        SourceArtifact artifact,
        String fingerprint,
        Instant startedAt,
        RuntimeException failure
    ) {
        if (!(failure instanceof ProcessingFailure processingFailure)) return;
        try {
            if (persistence.findByInputFingerprint(fingerprint).isPresent()) return;
            persistFailure(
                command, artifact, fingerprint, startedAt,
                processingFailure.evidence(), processingFailure.original());
        } catch (RuntimeException persistenceFailure) {
            processingFailure.original().addSuppressed(persistenceFailure);
        }
    }

    private String inputFingerprint(SourceArtifact artifact) {
        return sha256(PIPELINE_VERSION + "|" + legacyInputFingerprint(artifact));
    }

    private String legacyInputFingerprint(SourceArtifact artifact) {
        ParserDescriptor parserDescriptor = parser.descriptor();
        ExtractorDescriptor extractorDescriptor = extractor.descriptor();
        return sha256(String.join("|",
            artifact.sha256(), parserDescriptor.name(), parserDescriptor.version(),
            extractorDescriptor.strategy(), extractorDescriptor.version(),
            extractorDescriptor.modelName(), extractorDescriptor.promptVersion(),
            com.careeros.domain.RecruitmentExtractionProposal.SCHEMA_VERSION));
    }

    private static com.careeros.domain.RecruitmentExtractionProposal bindSource(
        com.careeros.domain.RecruitmentExtractionProposal proposal,
        Evidence evidence
    ) {
        var source = new com.careeros.domain.RecruitmentExtractionProposal.SourceProposal(
            evidence.id(), evidence.sourceUrl(), evidence.sourceTitle());
        return new com.careeros.domain.RecruitmentExtractionProposal(
            proposal.schemaVersion(), source, proposal.organization(), proposal.recruitmentEvent(),
            proposal.jobs(), proposal.warnings(), proposal.confidence(), false);
    }

    private static ExtractionSourceType sourceType(String mediaType) {
        return switch (mediaType) {
            case "text/html", "application/xhtml+xml" -> ExtractionSourceType.HTML;
            case "application/pdf" -> ExtractionSourceType.PDF;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ExtractionSourceType.DOCX;
            default -> throw new ExtractionExceptions.UnsupportedDocumentException("Unsupported media type: " + mediaType);
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class ProcessingFailure extends RuntimeException {
        private final Evidence evidence;
        private final RuntimeException original;

        private ProcessingFailure(Evidence evidence, RuntimeException original) {
            super(original);
            this.evidence = Objects.requireNonNull(evidence);
            this.original = Objects.requireNonNull(original);
        }

        private Evidence evidence() {
            return evidence;
        }

        private RuntimeException original() {
            return original;
        }
    }
}
