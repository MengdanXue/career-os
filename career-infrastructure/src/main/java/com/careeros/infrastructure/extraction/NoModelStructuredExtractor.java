package com.careeros.infrastructure.extraction;

import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.ExtractionPorts.ExtractionAttempt;
import com.careeros.application.ExtractionPorts.ExtractionContext;
import com.careeros.application.ExtractionPorts.ExtractorDescriptor;
import com.careeros.application.ExtractionPorts.StructuredExtractor;
import com.careeros.domain.ExtractedFact;
import com.careeros.domain.ParsedDocument;
import com.careeros.domain.RecruitmentExtractionProposal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

public final class NoModelStructuredExtractor implements StructuredExtractor {
    @Override
    public ExtractorDescriptor descriptor() {
        return new ExtractorDescriptor("no-model", "1.0.0", "none", "none", false);
    }

    @Override
    public ExtractionAttempt extract(ParsedDocument document, ExtractionContext context) {
        int year = context.evidence().capturedAt().atZone(ZoneOffset.UTC).getYear();
        var source = new RecruitmentExtractionProposal.SourceProposal(
            context.evidence().id(), context.evidence().sourceUrl(), context.evidence().sourceTitle());
        var organization = new RecruitmentExtractionProposal.OrganizationProposal(
            context.evidence().sourceTitle(), unknown());
        var event = new RecruitmentExtractionProposal.EventProposal(
            context.evidence().sourceTitle(), year, EventType.OTHER,
            unknown(), unknown(), unknown());
        var proposal = new RecruitmentExtractionProposal(
            RecruitmentExtractionProposal.SCHEMA_VERSION,
            source, organization, event, List.of(), List.of("MODEL_UNAVAILABLE"), 0, false);
        return new ExtractionAttempt(proposal, null);
    }

    private static <T> ExtractedFact<T> unknown() {
        return new ExtractedFact<>(null, FactStatus.UNKNOWN, 0, List.of(), null);
    }
}
