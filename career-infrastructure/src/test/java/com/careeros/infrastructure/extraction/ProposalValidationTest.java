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

    /** 历史载荷必须仍能校验通过，否则升版本会让库里已有的 ReviewItem 读不出来。 */
    @Test
    void previousSchemaVersionStillValidates() {
        assertThatCode(() -> validator.validateJson(payload("1.0.0", "ESTABLISHMENT")))
            .doesNotThrowAnyException();
    }

    @Test
    void currentSchemaVersionAcceptsTheWidenedEmploymentTypes() {
        for (String employment : List.of("AUTHORIZED_HEADCOUNT", "SCHOOL_HIRED", "STATE_OWNED_REGULAR")) {
            assertThatCode(() -> validator.validateJson(payload("1.1.0", employment)))
                .doesNotThrowAnyException();
        }
    }

    /** 版本之间必须真的隔离：1.1.0 才有的取值不能悄悄在 1.0.0 下通过。 */
    @Test
    void widenedEmploymentTypesAreRejectedUnderThePreviousVersion() {
        assertThatThrownBy(() -> validator.validateJson(payload("1.0.0", "STATE_OWNED_REGULAR")))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class)
            .hasMessageContaining("employmentType");
    }

    @Test
    void unknownSchemaVersionIsRejectedWithItsOwnReason() {
        assertThatThrownBy(() -> validator.validateJson(payload("9.9.9", "ESTABLISHMENT")))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class)
            .hasMessageContaining("Unsupported proposal schemaVersion 9.9.9");
    }

    @Test
    void payloadWithoutSchemaVersionIsRejected() {
        assertThatThrownBy(() -> validator.validateJson("{\"source\":{}}"))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class)
            .hasMessageContaining("schemaVersion");
    }

    private static String payload(String schemaVersion, String employmentType) {
        return ProposalFixtures.json(schemaVersion, employmentType);
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
