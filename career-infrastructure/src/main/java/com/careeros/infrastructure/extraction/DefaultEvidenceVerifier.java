package com.careeros.infrastructure.extraction;

import static com.careeros.domain.DomainEnums.FactStatus.INTERPRETED;
import static com.careeros.domain.DomainEnums.FactStatus.UNKNOWN;
import static com.careeros.domain.DomainEnums.ReviewReasonCode.MISSING_EVIDENCE;
import static com.careeros.domain.DomainEnums.ReviewReasonCode.RESTRICTIVE_FACT_FROM_LLM;

import com.careeros.application.ExtractionPorts.EvidenceVerifier;
import com.careeros.domain.EvidenceFragment;
import com.careeros.domain.ExtractedFact;
import com.careeros.domain.RecruitmentExtractionProposal;
import com.careeros.domain.ReviewIssue;
import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.OrganizationType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DefaultEvidenceVerifier implements EvidenceVerifier {
    @Override
    public List<ReviewIssue> verify(
        RecruitmentExtractionProposal proposal,
        List<EvidenceFragment> fragments
    ) {
        Objects.requireNonNull(proposal, "proposal");
        Map<UUID, EvidenceFragment> available = new HashMap<>();
        if (fragments != null) fragments.forEach(fragment -> available.put(fragment.id(), fragment));
        UUID reviewId = UUID.randomUUID();
        List<ReviewIssue> issues = new ArrayList<>();

        verifyFact(issues, reviewId, available, "organization.organizationType", proposal.organization().organizationType());
        verifyFact(issues, reviewId, available, "recruitmentEvent.publishedOn", proposal.recruitmentEvent().publishedOn());
        verifyFact(issues, reviewId, available, "recruitmentEvent.applicationStartsOn", proposal.recruitmentEvent().applicationStartsOn());
        verifyFact(issues, reviewId, available, "recruitmentEvent.applicationEndsOn", proposal.recruitmentEvent().applicationEndsOn());
        for (int index = 0; index < proposal.jobs().size(); index++) {
            var job = proposal.jobs().get(index);
            String path = "jobs[" + index + "]";
            verifyFact(issues, reviewId, available, path + ".title", job.title());
            verifyFact(issues, reviewId, available, path + ".headcount", job.headcount());
            verifyFact(issues, reviewId, available, path + ".employmentType", job.employmentType());
            verifyFact(issues, reviewId, available, path + ".minimumEducation", job.minimumEducation());
            verifyFact(issues, reviewId, available, path + ".degree", job.degree());
            verifyFact(issues, reviewId, available, path + ".majorText", job.majorText());
            verifyFact(issues, reviewId, available, path + ".maximumAge", job.maximumAge());
            verifyFact(issues, reviewId, available, path + ".acceptedGraduationYears", job.acceptedGraduationYears());
            verifyFact(issues, reviewId, available, path + ".minimumExperienceYears", job.minimumExperienceYears());
        }
        return List.copyOf(issues);
    }

    private static void verifyFact(
        List<ReviewIssue> issues,
        UUID reviewId,
        Map<UUID, EvidenceFragment> available,
        String path,
        ExtractedFact<?> fact
    ) {
        if (fact.factStatus() == UNKNOWN) return;
        if (fact.factStatus() == INTERPRETED) {
            issues.add(issue(reviewId, RESTRICTIVE_FACT_FROM_LLM, path,
                "Restrictive fact is interpreted rather than explicit", first(fact)));
        }
        if (fact.evidenceFragmentIds().isEmpty()) {
            issues.add(issue(reviewId, MISSING_EVIDENCE, path,
                "Restrictive fact has no evidence fragment", null));
            return;
        }
        int existingReferences = 0;
        boolean supported = false;
        UUID firstExistingReference = null;
        for (UUID fragmentId : fact.evidenceFragmentIds()) {
            EvidenceFragment fragment = available.get(fragmentId);
            if (fragment == null) {
                issues.add(issue(reviewId, MISSING_EVIDENCE, path,
                    "Referenced evidence fragment does not exist: " + fragmentId, fragmentId));
                continue;
            }
            existingReferences++;
            if (firstExistingReference == null) firstExistingReference = fragmentId;
            supported |= supports(fact.value(), fragment.verbatimText());
        }
        if (existingReferences > 0 && !supported) {
            issues.add(issue(reviewId, MISSING_EVIDENCE, path,
                "Referenced evidence does not contain the proposed value", firstExistingReference));
        }
    }

    private static boolean supports(Object value, String verbatimText) {
        if (value == null) return false;
        String normalizedText = normalize(verbatimText);
        if (value instanceof String stringValue) {
            return normalizedText.contains(normalize(stringValue));
        }
        if (value instanceof LocalDate date) {
            return normalizedText.contains(normalize(date.toString()))
                || normalizedText.contains(normalize(
                    date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日"));
        }
        if (value instanceof Number number) {
            return Pattern.compile("(?<!\\d)" + Pattern.quote(number.toString()) + "(?!\\d)")
                .matcher(verbatimText)
                .find();
        }
        if (value instanceof Collection<?> values) {
            return !values.isEmpty() && values.stream().allMatch(item -> supports(item, verbatimText));
        }
        if (value instanceof EmploymentType employmentType) {
            return switch (employmentType) {
                case ESTABLISHMENT -> containsAny(normalizedText, "事业编制", "事业编", "编内");
                case PUBLIC_INSTITUTION_FORMAL -> containsAny(normalizedText, "事业单位公开招聘", "签订聘用合同", "岗位聘用");
                case PERSONNEL_AGENCY -> containsAny(normalizedText, "人事代理");
                case LABOR_DISPATCH -> containsAny(normalizedText, "劳务派遣", "派遣制");
                case CONTRACT -> containsAny(normalizedText, "合同制", "合同聘用");
                case PROJECT_BASED -> containsAny(normalizedText, "项目制", "项目聘用");
                case UNKNOWN -> false;
            };
        }
        if (value instanceof EducationLevel educationLevel) {
            return switch (educationLevel) {
                case HIGH_SCHOOL -> containsAny(normalizedText, "高中", "中专");
                case ASSOCIATE -> containsAny(normalizedText, "专科", "大专");
                case BACHELOR -> containsAny(normalizedText, "本科", "学士");
                case MASTER -> containsAny(normalizedText, "硕士", "研究生");
                case DOCTORATE -> containsAny(normalizedText, "博士");
                case UNKNOWN -> false;
            };
        }
        if (value instanceof OrganizationType organizationType) {
            return switch (organizationType) {
                case GOVERNMENT -> containsAny(normalizedText, "政府", "人民政府", "行政机关");
                case PUBLIC_INSTITUTION -> containsAny(normalizedText, "事业单位", "事业编制", "事业编");
                case STATE_OWNED_ENTERPRISE -> containsAny(normalizedText, "国有企业", "国企", "国有独资", "国有控股");
                case UNIVERSITY -> containsAny(normalizedText, "大学", "学院", "高等学校");
                case HOSPITAL -> containsAny(normalizedText, "医院", "医疗中心");
                case RESEARCH_INSTITUTE -> containsAny(normalizedText, "研究院", "研究所", "实验室");
                case OTHER, UNKNOWN -> false;
            };
        }
        return normalizedText.contains(normalize(value.toString()));
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(normalize(candidate))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private static ReviewIssue issue(
        UUID reviewId,
        com.careeros.domain.DomainEnums.ReviewReasonCode code,
        String fieldPath,
        String message,
        UUID evidenceId
    ) {
        return new ReviewIssue(UUID.randomUUID(), reviewId, code, fieldPath, message, evidenceId);
    }

    private static UUID first(ExtractedFact<?> fact) {
        return fact.evidenceFragmentIds().isEmpty() ? null : fact.evidenceFragmentIds().getFirst();
    }
}
