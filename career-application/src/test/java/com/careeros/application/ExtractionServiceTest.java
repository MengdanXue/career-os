package com.careeros.application;

import static com.careeros.application.ExtractionPorts.*;
import static com.careeros.domain.DomainEnums.DataQualityStatus;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.ReviewPolicy;
import java.util.List;
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
    void disabledModelCreatesReviewablePartialProposal() {
        Fixtures.CountingExtractor extractor = new Fixtures.CountingExtractor(Fixtures.reviewProposal(), false);
        Fixtures.RecordingWriter writer = new Fixtures.RecordingWriter();
        ExtractionService service = service(extractor, new Fixtures.MemoryExtractionPersistence(), writer);

        ExtractionResult result = service.submit(Fixtures.htmlCommand("<h1>招聘公告</h1>"));

        assertThat(result.run().status()).isEqualTo(DataQualityStatus.REVIEW_REQUIRED);
        assertThat(result.reviewId()).isPresent();
        assertThat(writer.calls).isZero();
    }

    private static ExtractionService service(
        Fixtures.CountingExtractor extractor,
        Fixtures.MemoryExtractionPersistence persistence,
        Fixtures.RecordingWriter writer
    ) {
        return service(extractor, persistence, writer, new Fixtures.RecordingUnitOfWork());
    }

    private static ExtractionService service(
        Fixtures.CountingExtractor extractor,
        Fixtures.MemoryExtractionPersistence persistence,
        Fixtures.RecordingWriter writer,
        Fixtures.RecordingUnitOfWork unitOfWork
    ) {
        ProposalValidator validator = proposal -> {};
        EvidenceVerifier verifier = (proposal, fragments) -> List.of();
        ExtractionObserver observer = new ExtractionObserver() {
            @Override public void completed(com.careeros.domain.ExtractionRun run, boolean reused, java.time.Duration duration) {}
            @Override public void modelCall(String model, String result) {}
        };
        return new ExtractionService(
            new Fixtures.MemoryArtifactStore(), new Fixtures.HtmlParser(), (artifact, parsed) -> parsed,
            extractor, validator, verifier, persistence, writer, unitOfWork, observer,
            new ReviewPolicy(0.90), Fixtures.CLOCK, 5_000_000);
    }
}
