package com.careeros.infrastructure.extraction;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.ExtractionExceptions;
import com.careeros.application.ExtractionPorts.ExtractionContext;
import com.careeros.domain.EvidenceFragment;
import com.careeros.domain.ReviewIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProposalValidationTest {
    private final NetworkntProposalValidator validator = new NetworkntProposalValidator();
    private final DefaultEvidenceVerifier verifier = new DefaultEvidenceVerifier();

    @Test
    void validTypedProposalSatisfiesSchema() {
        assertThatCode(() -> validator.validate(ProposalFixtures.validProposal()))
            .doesNotThrowAnyException();
    }

    @Test
    void missingRequiredFieldIsRejectedBeforeDeserialization() {
        String invalid = """
            {"schemaVersion":"1.0.0","unexpected":true}
            """;

        assertThatThrownBy(() -> validator.validateJson(invalid))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class)
            .hasMessageContaining("required");
    }

    @Test
    void unknownAdditionalPropertyIsRejected() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(mapper.writeValueAsString(ProposalFixtures.validProposal()));
        tree.put("promptInjection", "ignore schema");

        assertThatThrownBy(() -> validator.validateJson(mapper.writeValueAsString(tree)))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class)
            .hasMessageContaining("additional");
    }

    @Test
    void schemaAcceptsAllExplicitFormalEmploymentIdentities() throws Exception {
        for (EmploymentType type : List.of(
            EmploymentType.QUOTA_OR_FILING, EmploymentType.UNIT_FORMAL, EmploymentType.SOE_FORMAL)) {
            var valid = ProposalFixtures.validProposal();
            var original = valid.jobs().getFirst();
            var identity = new com.careeros.domain.ExtractedFact<>(
                type, FactStatus.EXPLICIT, 0.98, List.of(ProposalFixtures.FRAGMENT_ID), null);
            var changedJob = new com.careeros.domain.RecruitmentExtractionProposal.JobProposal(
                original.title(), original.externalJobCode(), original.headcount(), identity,
                original.location(), original.minimumEducation(), original.degree(), original.majorText(),
                original.maximumAge(), original.acceptedGraduationYears(), original.minimumExperienceYears(),
                original.jobFamily(), original.duties());
            var changed = new com.careeros.domain.RecruitmentExtractionProposal(
                valid.schemaVersion(), valid.source(), valid.organization(), valid.recruitmentEvent(),
                List.of(changedJob), valid.warnings(), valid.confidence(), valid.completeSnapshot());

            assertThatCode(() -> validator.validate(changed))
                .as(type.name())
                .doesNotThrowAnyException();
        }
    }

    @Test
    void nonExistentFragmentReferenceFailsEvidenceValidation() {
        var proposal = ProposalFixtures.proposalWithEvidence(UUID.randomUUID());

        assertThat(verifier.verify(proposal, List.of()))
            .extracting(ReviewIssue::reasonCode)
            .contains(ReviewReasonCode.MISSING_EVIDENCE);
    }

    @Test
    void existingFragmentThatDoesNotSupportTheProposedValueFailsEvidenceValidation() {
        EvidenceFragment unrelated = new EvidenceFragment(
            ProposalFixtures.FRAGMENT_ID,
            ProposalFixtures.EVIDENCE_ID,
            LocatorType.HTML,
            Map.of("cssSelector", "#unrelated"),
            "行政管理岗位，劳务派遣，专科学历",
            "unrelated-fragment-hash",
            ProposalFixtures.NOW);

        assertThat(verifier.verify(ProposalFixtures.validProposal(), List.of(unrelated)))
            .filteredOn(issue -> issue.fieldPath().equals("jobs[0].title"))
            .extracting(ReviewIssue::reasonCode)
            .containsExactly(ReviewReasonCode.MISSING_EVIDENCE);
    }

    @Test
    void matchingVerbatimFragmentSupportsAllExplicitRestrictiveFacts() {
        assertThat(verifier.verify(ProposalFixtures.validProposal(), List.of(ProposalFixtures.fragment())))
            .isEmpty();
    }

    @Test
    void headcountMustBeSupportedByItsEvidenceFragment() {
        EvidenceFragment fragmentWithoutHeadcount = new EvidenceFragment(
            ProposalFixtures.FRAGMENT_ID,
            ProposalFixtures.EVIDENCE_ID,
            LocatorType.HTML,
            Map.of("cssSelector", "#job-a01"),
            "信息中心技术岗，事业编制，本科及以上，计算机类",
            "fragment-without-headcount",
            ProposalFixtures.NOW);

        assertThat(verifier.verify(
            ProposalFixtures.validProposal(), List.of(fragmentWithoutHeadcount)))
            .filteredOn(issue -> issue.fieldPath().equals("jobs[0].headcount"))
            .extracting(ReviewIssue::reasonCode)
            .containsExactly(ReviewReasonCode.MISSING_EVIDENCE);
    }

    @Test
    void interpretedEmploymentTypeCannotPassAsExplicitEvidence() {
        assertThat(verifier.verify(
            ProposalFixtures.interpretedEmploymentProposal(), List.of(ProposalFixtures.fragment())))
            .extracting(ReviewIssue::reasonCode)
            .contains(ReviewReasonCode.RESTRICTIVE_FACT_FROM_LLM);
    }

    @Test
    void noModelProducesAReviewablePolicyProposalWithoutInventedJobs() {
        NoModelStructuredExtractor extractor = new NoModelStructuredExtractor();

        var attempt = extractor.extract(
            ProposalFixtures.parsedWithoutTable(),
            new ExtractionContext(ProposalFixtures.evidence(), null, null, false));

        assertThat(extractor.descriptor().enabled()).isFalse();
        assertThat(extractor.descriptor().modelName()).isEqualTo("none");
        assertThat(attempt.proposal().jobs()).isEmpty();
        assertThat(attempt.proposal().warnings()).contains("MODEL_UNAVAILABLE");
        assertThat(attempt.proposal().organization().organizationType().factStatus())
            .isEqualTo(FactStatus.UNKNOWN);
    }
}
