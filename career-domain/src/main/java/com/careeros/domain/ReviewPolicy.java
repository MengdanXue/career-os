package com.careeros.domain;

import static com.careeros.domain.DomainEnums.FactStatus.INTERPRETED;
import static com.careeros.domain.DomainEnums.FactStatus.UNKNOWN;

import com.careeros.domain.DomainEnums.OrganizationType;
import com.careeros.domain.DomainEnums.ParserQuality;
import com.careeros.domain.DomainEnums.ReviewReasonCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ReviewPolicy {
    private final double autoVerifyThreshold;

    public ReviewPolicy(double autoVerifyThreshold) {
        if (!Double.isFinite(autoVerifyThreshold) || autoVerifyThreshold < 0 || autoVerifyThreshold > 1) {
            throw new IllegalArgumentException("autoVerifyThreshold must be between 0 and 1");
        }
        this.autoVerifyThreshold = autoVerifyThreshold;
    }

    public Evaluation evaluate(
        ParsedDocument document,
        RecruitmentExtractionProposal proposal,
        boolean evidenceVerified
    ) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(proposal, "proposal");
        UUID reviewItemId = UUID.randomUUID();
        List<ReviewIssue> issues = new ArrayList<>();

        if (document.quality() == ParserQuality.LOW_TEXT_QUALITY) {
            add(issues, reviewItemId, ReviewReasonCode.LOW_TEXT_QUALITY, "document.quality",
                "Document text quality is too low for automatic verification", null);
        }
        if (proposal.confidence() < autoVerifyThreshold) {
            add(issues, reviewItemId, ReviewReasonCode.LOW_CONFIDENCE, "confidence",
                "Proposal confidence is below the automatic verification threshold", null);
        }
        if (proposal.organization().organizationType().value() == OrganizationType.UNKNOWN
            || proposal.organization().organizationType().factStatus() == UNKNOWN) {
            add(issues, reviewItemId, ReviewReasonCode.ORGANIZATION_TYPE_UNKNOWN,
                "organization.organizationType", "Organization type is unknown", firstEvidence(proposal.organization().organizationType()));
        }
        if (!evidenceVerified) {
            add(issues, reviewItemId, ReviewReasonCode.MISSING_EVIDENCE, "proposal",
                "One or more restrictive facts are not supported by source evidence", null);
        }

        checkRestrictive(issues, reviewItemId, "recruitmentEvent.publishedOn", proposal.recruitmentEvent().publishedOn(), false);
        checkRestrictive(issues, reviewItemId, "recruitmentEvent.applicationStartsOn", proposal.recruitmentEvent().applicationStartsOn(), false);
        checkRestrictive(issues, reviewItemId, "recruitmentEvent.applicationEndsOn", proposal.recruitmentEvent().applicationEndsOn(), false);
        for (int i = 0; i < proposal.jobs().size(); i++) {
            var job = proposal.jobs().get(i);
            String path = "jobs[" + i + "]";
            checkRestrictive(issues, reviewItemId, path + ".title", job.title(), true);
            checkRestrictive(issues, reviewItemId, path + ".employmentType", job.employmentType(), true);
            checkRestrictive(issues, reviewItemId, path + ".minimumEducation", job.minimumEducation(), false);
            checkRestrictive(issues, reviewItemId, path + ".degree", job.degree(), false);
            checkRestrictive(issues, reviewItemId, path + ".majorText", job.majorText(), false);
            checkRestrictive(issues, reviewItemId, path + ".maximumAge", job.maximumAge(), false);
            checkRestrictive(issues, reviewItemId, path + ".acceptedGraduationYears", job.acceptedGraduationYears(), false);
            checkRestrictive(issues, reviewItemId, path + ".minimumExperienceYears", job.minimumExperienceYears(), false);
        }

        return new Evaluation(issues.isEmpty(), issues.isEmpty() ? null : reviewItemId, issues);
    }

    private static void checkRestrictive(
        List<ReviewIssue> issues,
        UUID reviewItemId,
        String fieldPath,
        ExtractedFact<?> fact,
        boolean mandatory
    ) {
        if (mandatory && fact.factStatus() == UNKNOWN) {
            add(issues, reviewItemId, ReviewReasonCode.MISSING_EVIDENCE, fieldPath,
                "Mandatory fact is unknown", null);
            return;
        }
        if (fact.factStatus() == UNKNOWN) return;
        if (fact.evidenceFragmentIds().isEmpty()) {
            add(issues, reviewItemId, ReviewReasonCode.MISSING_EVIDENCE, fieldPath,
                "Restrictive fact has no evidence fragment", null);
        }
        if (fact.factStatus() == INTERPRETED) {
            add(issues, reviewItemId, ReviewReasonCode.RESTRICTIVE_FACT_FROM_LLM, fieldPath,
                "Restrictive fact is interpreted rather than explicit", firstEvidence(fact));
        }
    }

    private static UUID firstEvidence(ExtractedFact<?> fact) {
        return fact.evidenceFragmentIds().isEmpty() ? null : fact.evidenceFragmentIds().getFirst();
    }

    private static void add(
        List<ReviewIssue> issues,
        UUID reviewItemId,
        ReviewReasonCode code,
        String fieldPath,
        String message,
        UUID evidenceFragmentId
    ) {
        issues.add(new ReviewIssue(UUID.randomUUID(), reviewItemId, code, fieldPath, message, evidenceFragmentId));
    }

    public record Evaluation(boolean autoVerified, UUID reviewItemId, List<ReviewIssue> issues) {
        public Evaluation {
            issues = issues == null ? List.of() : List.copyOf(issues);
            if (autoVerified && !issues.isEmpty()) {
                throw new IllegalArgumentException("auto-verified evaluation cannot contain issues");
            }
            if (!issues.isEmpty()) Objects.requireNonNull(reviewItemId, "reviewItemId");
        }
    }
}
