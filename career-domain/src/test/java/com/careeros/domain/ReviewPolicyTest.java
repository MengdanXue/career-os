package com.careeros.domain;

import static com.careeros.domain.DomainEnums.FactStatus;
import static com.careeros.domain.DomainEnums.OrganizationType;
import static com.careeros.domain.DomainEnums.ParserQuality;
import static com.careeros.domain.DomainEnums.ReviewReasonCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewPolicyTest {
    private final ReviewPolicy policy = new ReviewPolicy(0.90);

    @Test
    void explicitWellSupportedProposalCanBeAutomaticallyVerified() {
        ReviewPolicy.Evaluation result = policy.evaluate(
            ExtractionFixtures.parsed(ParserQuality.ACCEPTABLE),
            ExtractionFixtures.proposal(),
            true);

        assertThat(result.autoVerified()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void lowTextQualityLowConfidenceAndUnknownOrganizationRequireReview() {
        RecruitmentExtractionProposal valid = ExtractionFixtures.proposal();
        var unknownOrganization = new RecruitmentExtractionProposal.OrganizationProposal(
            valid.organization().name(),
            new ExtractedFact<>(OrganizationType.UNKNOWN, FactStatus.EXPLICIT, 0.99,
                List.of(ExtractionFixtures.FRAGMENT_ID), null));
        var uncertain = new RecruitmentExtractionProposal(
            valid.schemaVersion(), valid.source(), unknownOrganization, valid.recruitmentEvent(),
            valid.jobs(), valid.warnings(), 0.72, valid.completeSnapshot());

        ReviewPolicy.Evaluation result = policy.evaluate(
            ExtractionFixtures.parsed(ParserQuality.LOW_TEXT_QUALITY), uncertain, true);

        assertThat(result.autoVerified()).isFalse();
        assertThat(result.issues()).extracting(ReviewIssue::reasonCode)
            .contains(
                ReviewReasonCode.LOW_TEXT_QUALITY,
                ReviewReasonCode.LOW_CONFIDENCE,
                ReviewReasonCode.ORGANIZATION_TYPE_UNKNOWN);
    }

    @Test
    void interpretedRestrictiveFactRequiresReviewEvenAtHighConfidence() {
        RecruitmentExtractionProposal valid = ExtractionFixtures.proposal();
        RecruitmentExtractionProposal.JobProposal original = valid.jobs().getFirst();
        var interpretedEmployment = new ExtractedFact<>(
            original.employmentType().value(), FactStatus.INTERPRETED, 0.99,
            original.employmentType().evidenceFragmentIds(), "模型根据上下文推断");
        var changedJob = new RecruitmentExtractionProposal.JobProposal(
            original.title(), original.externalJobCode(), original.headcount(), interpretedEmployment,
            original.location(), original.minimumEducation(), original.degree(), original.majorText(),
            original.maximumAge(), original.acceptedGraduationYears(), original.minimumExperienceYears(),
            original.jobFamily(), original.duties());
        var proposal = new RecruitmentExtractionProposal(
            valid.schemaVersion(), valid.source(), valid.organization(), valid.recruitmentEvent(),
            List.of(changedJob), valid.warnings(), 0.99, valid.completeSnapshot());

        ReviewPolicy.Evaluation result = policy.evaluate(
            ExtractionFixtures.parsed(ParserQuality.ACCEPTABLE), proposal, true);

        assertThat(result.autoVerified()).isFalse();
        assertThat(result.issues()).extracting(ReviewIssue::reasonCode)
            .contains(ReviewReasonCode.RESTRICTIVE_FACT_FROM_LLM);
    }

    @Test
    void failedEvidenceVerificationRequiresReview() {
        ReviewPolicy.Evaluation result = policy.evaluate(
            ExtractionFixtures.parsed(ParserQuality.ACCEPTABLE),
            ExtractionFixtures.proposal(),
            false);

        assertThat(result.autoVerified()).isFalse();
        assertThat(result.issues()).extracting(ReviewIssue::reasonCode)
            .contains(ReviewReasonCode.MISSING_EVIDENCE);
    }

    @Test
    void unknownHeadcountCannotBeAutomaticallyVerified() {
        RecruitmentExtractionProposal valid = ExtractionFixtures.proposal();
        var original = valid.jobs().getFirst();
        var changedJob = new RecruitmentExtractionProposal.JobProposal(
            original.title(), original.externalJobCode(), ExtractionFixtures.unknown(),
            original.employmentType(), original.location(), original.minimumEducation(),
            original.degree(), original.majorText(), original.maximumAge(),
            original.acceptedGraduationYears(), original.minimumExperienceYears(),
            original.jobFamily(), original.duties());
        var proposal = new RecruitmentExtractionProposal(
            valid.schemaVersion(), valid.source(), valid.organization(), valid.recruitmentEvent(),
            List.of(changedJob), valid.warnings(), valid.confidence(), valid.completeSnapshot());

        ReviewPolicy.Evaluation result = policy.evaluate(
            ExtractionFixtures.parsed(ParserQuality.ACCEPTABLE), proposal, true);

        assertThat(result.autoVerified()).isFalse();
        assertThat(result.issues()).extracting(ReviewIssue::fieldPath)
            .contains("jobs[0].headcount");
    }
}
