package com.careeros.application;

import static com.careeros.application.ExtractionPorts.*;
import static com.careeros.domain.DomainEnums.DataQualityStatus;
import static com.careeros.domain.DomainEnums.ReviewReasonCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.domain.ReviewPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ExtractionServiceTest {
    @Test
    void identicalInputReusesRunWithoutSecondExtractionCall() {
        Fixtures.CountingExtractor extractor = new Fixtures.CountingExtractor(Fixtures.verifiedProposal(), true);
        Fixtures.MemoryExtractionPersistence persistence = new Fixtures.MemoryExtractionPersistence();
        ExtractionService service = service(extractor, persistence, new Fixtures.RecordingWriter());
        SubmitExtractionCommand command = Fixtures.htmlCommand("<h1>招聘公告</h1>");

        ExtractionResult first = service.submit(command);
        ExtractionResult second = service.submit(command);

        assertThat(second.run().id()).isEqualTo(first.run().id());
        assertThat(second.reused()).isTrue();
        assertThat(extractor.calls()).isEqualTo(1);
    }

    @Test
    void verifiedProposalWritesFormalJobsInsideUnitOfWork() {
        Fixtures.CountingExtractor extractor = new Fixtures.CountingExtractor(Fixtures.verifiedProposal(), true);
        Fixtures.RecordingWriter writer = new Fixtures.RecordingWriter();
        Fixtures.RecordingUnitOfWork unitOfWork = new Fixtures.RecordingUnitOfWork();
        ExtractionService service = service(extractor, new Fixtures.MemoryExtractionPersistence(), writer, unitOfWork);

        ExtractionResult result = service.submit(Fixtures.htmlCommand("<h1>招聘公告</h1>"));

        assertThat(result.run().status()).isEqualTo(DataQualityStatus.VERIFIED);
        assertThat(result.reviewId()).isEmpty();
        assertThat(writer.calls).isEqualTo(1);
        assertThat(unitOfWork.calls).isEqualTo(1);
    }

    @Test
    void truncatedInputCannotAutoVerifyEvenWhenTheProposalIsOtherwiseClean() {
        // 与 verifiedProposalWritesFormalJobsInsideUnitOfWork 用的是同一份"干净"提案，
        // 唯一差别是抽取器上报了输入截断——只覆盖部分原文的结果绝不能直接写进正式岗位库。
        Fixtures.CountingExtractor extractor = new Fixtures.CountingExtractor(
            Fixtures.verifiedProposal(), true,
            List.of("Document exceeded the 5000 character extraction budget: 3 of 40 evidence fragments were sent to the model, 37 were dropped"));
        Fixtures.RecordingWriter writer = new Fixtures.RecordingWriter();
        Fixtures.MemoryExtractionPersistence persistence = new Fixtures.MemoryExtractionPersistence();
        ExtractionService service = service(extractor, persistence, writer);

        ExtractionResult result = service.submit(Fixtures.htmlCommand("<h1>招聘公告</h1>"));

        assertThat(result.run().status()).isEqualTo(DataQualityStatus.REVIEW_REQUIRED);
        assertThat(result.reviewId()).isPresent();
        assertThat(writer.calls).isZero();
        assertThat(persistence.onlyReview().issues())
            .anyMatch(issue -> issue.reasonCode() == ReviewReasonCode.INPUT_TRUNCATED
                && issue.message().contains("were dropped"));
    }

    @Test
    void disabledModelCreatesReviewablePartialProposal() {
        Fixtures.CountingExtractor extractor = new Fixtures.CountingExtractor(Fixtures.reviewProposal(), false);
        Fixtures.RecordingWriter writer = new Fixtures.RecordingWriter();
        ExtractionService service = service(extractor, new Fixtures.MemoryExtractionPersistence(), writer);

        ExtractionResult result = service.submit(Fixtures.htmlCommand("<h1>招聘公告</h1>"));

        assertThat(result.run().status()).isEqualTo(DataQualityStatus.REVIEW_REQUIRED);
        assertThat(result.reviewId()).isPresent();
        assertThat(writer.calls).isZero();
    }

    @Test
    void immutableSourceMetadataIsBoundToTheUploadedEvidence() {
        Fixtures.CountingExtractor extractor = new Fixtures.CountingExtractor(Fixtures.verifiedProposal(), true);
        ExtractionService service = service(
            extractor, new Fixtures.MemoryExtractionPersistence(), new Fixtures.RecordingWriter());
        SubmitExtractionCommand command = Fixtures.htmlCommand("<h1>招聘公告</h1>");

        ExtractionResult result = service.submit(command);

        assertThat(result.run().proposedPayload().source().evidenceId())
            .isEqualTo(result.run().evidenceId());
        assertThat(result.run().proposedPayload().source().sourceUrl())
            .isEqualTo(command.sourceUrl());
        assertThat(result.run().proposedPayload().source().sourceTitle())
            .isEqualTo(command.sourceTitle());
    }

    @Test
    void concurrentIdenticalSubmissionsInvokeExtractorOnceAndReuseWinner() throws Exception {
        Fixtures.CountingExtractor extractor = new Fixtures.CountingExtractor(Fixtures.verifiedProposal(), true);
        Fixtures.MemoryExtractionPersistence persistence = new Fixtures.MemoryExtractionPersistence();
        ExtractionService service = service(
            extractor, persistence, new Fixtures.RecordingWriter(),
            new Fixtures.RecordingUnitOfWork(), new Fixtures.SynchronizedFingerprintLock());
        SubmitExtractionCommand command = Fixtures.htmlCommand("<h1>same</h1>");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return service.submit(command); });
            var second = executor.submit(() -> { start.await(); return service.submit(command); });
            start.countDown();
            ExtractionResult one = first.get();
            ExtractionResult two = second.get();

            assertThat(two.run().id()).isEqualTo(one.run().id());
            assertThat(List.of(one.reused(), two.reused())).containsExactlyInAnyOrder(false, true);
            assertThat(extractor.calls()).isEqualTo(1);
        }
    }

    @Test
    void extractorFailureIsPersistedAsFailedRun() {
        StructuredExtractor failing = new StructuredExtractor() {
            @Override public ExtractorDescriptor descriptor() {
                return new ExtractorDescriptor("failing", "1.0.0", "fixture-model", "p1", true);
            }
            @Override public ExtractionAttempt extract(
                com.careeros.domain.ParsedDocument document, ExtractionContext context
            ) {
                throw new ExtractionExceptions.InvalidProposalException("model output is invalid");
            }
        };
        Fixtures.MemoryExtractionPersistence persistence = new Fixtures.MemoryExtractionPersistence();
        ExtractionService service = service(failing, persistence, new Fixtures.RecordingWriter());

        assertThatThrownBy(() -> service.submit(Fixtures.htmlCommand("<h1>invalid</h1>")))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class);
        assertThat(persistence.onlyValue().run().status()).isEqualTo(DataQualityStatus.FAILED);
        assertThat(persistence.onlyValue().run().proposedPayload()).isNull();
        assertThat(persistence.onlyValue().run().errorCode()).isEqualTo("InvalidProposalException");
    }

    @Test
    void failedRunIsPersistedOnlyAfterFingerprintLockIsReleased() {
        StructuredExtractor failing = new StructuredExtractor() {
            @Override public ExtractorDescriptor descriptor() {
                return new ExtractorDescriptor("failing", "1.0.0", "fixture-model", "p1", true);
            }
            @Override public ExtractionAttempt extract(
                com.careeros.domain.ParsedDocument document, ExtractionContext context
            ) {
                throw new ExtractionExceptions.InvalidProposalException("model output is invalid");
            }
        };
        AtomicBoolean insideLock = new AtomicBoolean();
        FingerprintLock lock = new FingerprintLock() {
            @Override public <T> T execute(String fingerprint, java.util.function.Supplier<T> operation) {
                insideLock.set(true);
                try {
                    return operation.get();
                } finally {
                    insideLock.set(false);
                }
            }
        };
        Fixtures.MemoryExtractionPersistence delegate = new Fixtures.MemoryExtractionPersistence();
        ExtractionPersistence persistence = new ExtractionPersistence() {
            @Override public Optional<PersistedExtraction> findByInputFingerprint(String fingerprint) {
                return delegate.findByInputFingerprint(fingerprint);
            }
            @Override public PersistedExtraction save(ExtractionBundle bundle) {
                return delegate.save(bundle);
            }
            @Override public PersistedExtraction saveFailure(FailedExtractionBundle bundle) {
                assertThat(insideLock).isFalse();
                return delegate.saveFailure(bundle);
            }
            @Override public PersistedExtraction findById(UUID id) {
                return delegate.findById(id);
            }
        };
        ExtractionService service = service(
            failing, persistence, new Fixtures.RecordingWriter(),
            new Fixtures.RecordingUnitOfWork(), lock);

        assertThatThrownBy(() -> service.submit(Fixtures.htmlCommand("<h1>invalid</h1>")))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class);
        assertThat(delegate.onlyValue().run().status()).isEqualTo(DataQualityStatus.FAILED);
    }

    @Test
    void findDoesNotMisreportUnexpectedPersistenceFailureAsNotFound() {
        Fixtures.MemoryExtractionPersistence persistence = new Fixtures.MemoryExtractionPersistence();
        IllegalStateException outage = new IllegalStateException("database unavailable");
        persistence.failFindWith(outage);
        ExtractionService service = service(
            new Fixtures.CountingExtractor(Fixtures.verifiedProposal(), true),
            persistence, new Fixtures.RecordingWriter());

        assertThatThrownBy(() -> service.find(java.util.UUID.randomUUID())).isSameAs(outage);
    }

    private static ExtractionService service(
        StructuredExtractor extractor,
        ExtractionPersistence persistence,
        Fixtures.RecordingWriter writer
    ) {
        return service(extractor, persistence, writer, new Fixtures.RecordingUnitOfWork());
    }

    private static ExtractionService service(
        StructuredExtractor extractor,
        ExtractionPersistence persistence,
        Fixtures.RecordingWriter writer,
        Fixtures.RecordingUnitOfWork unitOfWork
    ) {
        return service(extractor, persistence, writer, unitOfWork, new Fixtures.SynchronizedFingerprintLock());
    }

    private static ExtractionService service(
        StructuredExtractor extractor,
        ExtractionPersistence persistence,
        Fixtures.RecordingWriter writer,
        Fixtures.RecordingUnitOfWork unitOfWork,
        FingerprintLock fingerprintLock
    ) {
        ProposalValidator validator = proposal -> {};
        EvidenceVerifier verifier = (proposal, fragments) -> List.of();
        ExtractionObserver observer = new ExtractionObserver() {
            @Override public void completed(com.careeros.domain.ExtractionRun run, boolean reused, java.time.Duration duration) {}
            @Override public void modelCall(String model, String result) {}
        };
        return new ExtractionService(
            new Fixtures.MemoryArtifactStore(), new Fixtures.HtmlParser(), (artifact, parsed) -> parsed,
            extractor, validator, verifier, persistence, writer, unitOfWork, fingerprintLock, observer,
            new ReviewPolicy(0.90), Fixtures.CLOCK, 5_000_000);
    }
}
