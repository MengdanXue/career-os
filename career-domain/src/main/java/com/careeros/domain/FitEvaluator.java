package com.careeros.domain;

import static com.careeros.domain.DomainEnums.*;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class FitEvaluator {
    public static final String VERSION = "fit-v1";

    public FitAssessment evaluate(CandidateProfile candidate, JobPosting job, Organization organization, String jobFingerprint, Instant now) {
        var dimensions = new ArrayList<AssessmentDimension>();
        dimensions.add(overlap(AssessmentDimensionType.MAJOR_FIT, 25, candidate.majors(), job.exactMajors(), job.evidenceIds(), "MAJOR"));
        dimensions.add(textOverlap(AssessmentDimensionType.SKILL_FIT, 20, candidate.skills(), jobText(job), job.evidenceIds(), "SKILL"));
        dimensions.add(experience(candidate, job));
        dimensions.add(textOverlap(AssessmentDimensionType.RESEARCH_FIT, 10, candidate.researchKeywords(), jobText(job), job.evidenceIds(), "RESEARCH"));
        dimensions.add(overlap(AssessmentDimensionType.PROFESSIONAL_TITLE_FIT, 10, candidate.professionalTitles(), job.requiredProfessionalTitles(), job.evidenceIds(), "TITLE"));
        dimensions.add(preference(candidate, job, organization));
        return new FitAssessment(UUID.randomUUID(), candidate.id(), job.id(), dimensions, VERSION, candidate.profileVersion(), jobFingerprint, now);
    }

    private AssessmentDimension experience(CandidateProfile candidate, JobPosting job) {
        if (job.minimumExperienceYears() == null || candidate.experienceYears() == null) return unknown(AssessmentDimensionType.EXPERIENCE_FIT, 20, "EXPERIENCE_UNKNOWN");
        int points = candidate.experienceYears() >= job.minimumExperienceYears() ? 20 : 0;
        return known(AssessmentDimensionType.EXPERIENCE_FIT, points, 20, "EXPERIENCE_NUMERIC", "工作年限按明确数值比较", job.evidenceIds());
    }

    private AssessmentDimension preference(CandidateProfile candidate, JobPosting job, Organization organization) {
        boolean hasAny = !candidate.preferredLocations().isEmpty() || !candidate.acceptedEmploymentTypes().isEmpty()
            || !candidate.targetJobFamilies().isEmpty() || !candidate.preferredOrganizationTypes().isEmpty();
        if (!hasAny) return unknown(AssessmentDimensionType.PREFERENCE_FIT, 15, "PREFERENCE_UNKNOWN");
        int points = 0;
        if (candidate.preferredLocations().stream().anyMatch(p -> contains(job.location(), p))) points += 5;
        if (candidate.acceptedEmploymentTypes().contains(job.employmentType())) points += 5;
        if (candidate.targetJobFamilies().contains(job.jobFamily())) points += 3;
        if (candidate.preferredOrganizationTypes().contains(organization.organizationType())) points += 2;
        return known(AssessmentDimensionType.PREFERENCE_FIT, points, 15, "PREFERENCE_EXPLICIT", "地点、用工、岗位族和单位类型偏好", job.evidenceIds());
    }

    private AssessmentDimension overlap(AssessmentDimensionType type, int maximum, Set<String> candidateValues, Set<String> requirements, List<UUID> evidenceIds, String prefix) {
        if (candidateValues.isEmpty() || requirements.isEmpty()) return unknown(type, maximum, prefix + "_UNKNOWN");
        long matches = requirements.stream().filter(required -> candidateValues.stream().anyMatch(value -> normalize(value).equals(normalize(required)))).count();
        int points = Math.round(maximum * matches / (float) requirements.size());
        return known(type, points, maximum, prefix + "_EXACT", "按规范化后的明确字段匹配", evidenceIds);
    }

    private AssessmentDimension textOverlap(AssessmentDimensionType type, int maximum, Set<String> candidateValues, String text, List<UUID> evidenceIds, String prefix) {
        if (candidateValues.isEmpty() || text == null || text.isBlank()) return unknown(type, maximum, prefix + "_UNKNOWN");
        long matches = candidateValues.stream().filter(value -> contains(text, value)).count();
        int points = Math.round(maximum * matches / (float) candidateValues.size());
        return known(type, points, maximum, prefix + "_TEXT", "候选人关键词在岗位标题或职责中有明确文本依据", evidenceIds);
    }

    private static String jobText(JobPosting job) { return (job.title() == null ? "" : job.title()) + " " + (job.duties() == null ? "" : job.duties()); }
    private static boolean contains(String text, String token) { return text != null && token != null && normalize(text).contains(normalize(token)); }
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s：，。；、]+", ""); }
    private static AssessmentDimension unknown(AssessmentDimensionType type, int maximum, String reason) { return new AssessmentDimension(type, 0, maximum, AssessmentFactStatus.UNKNOWN, reason, "信息不足，未计正向分", List.of()); }
    private static AssessmentDimension known(AssessmentDimensionType type, int points, int maximum, String reason, String explanation, Collection<UUID> evidenceIds) { return new AssessmentDimension(type, points, maximum, AssessmentFactStatus.EXPLICIT, reason, explanation, List.copyOf(evidenceIds)); }
}
