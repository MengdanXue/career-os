package com.careeros.domain;

import static com.careeros.domain.DomainEnums.*;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.*;

public final class FitEvaluator {
    public static final String VERSION = "fit-v1";

    public FitAssessment evaluate(CandidateProfile candidate, JobPosting job, Organization organization, String jobFingerprint, Instant now) {
        return evaluate(candidate, CandidateFacts.confirmed(candidate), job, organization, jobFingerprint, now);
    }

    public FitAssessment evaluate(CandidateProfile candidate, CandidateFacts facts, JobPosting job, Organization organization, String jobFingerprint, Instant now) {
        return evaluate(candidate, facts, job, organization, jobFingerprint,
            now.atZone(java.time.ZoneOffset.UTC).toLocalDate(), now);
    }

    public FitAssessment evaluate(CandidateProfile candidate, CandidateFacts facts, JobPosting job,
                                  Organization organization, String jobFingerprint,
                                  LocalDate qualificationAsOf, Instant assessedAt) {
        var dimensions = new ArrayList<AssessmentDimension>();
        dimensions.add(overlap(AssessmentDimensionType.MAJOR_FIT, 25, confirmed(facts, MAJORS, candidate.majors()), job.exactMajors(), job.evidenceIds(), "MAJOR"));
        dimensions.add(textOverlap(AssessmentDimensionType.SKILL_FIT, 20, confirmed(facts, SKILLS, candidate.skills()), jobText(job), job.evidenceIds(), "SKILL"));
        dimensions.add(experience(candidate, facts, job, qualificationAsOf));
        dimensions.add(textOverlap(AssessmentDimensionType.RESEARCH_FIT, 10, confirmed(facts, RESEARCH_KEYWORDS, candidate.researchKeywords()), jobText(job), job.evidenceIds(), "RESEARCH"));
        dimensions.add(overlap(AssessmentDimensionType.PROFESSIONAL_TITLE_FIT, 10, confirmed(facts, PROFESSIONAL_TITLES, candidate.professionalTitles()), job.requiredProfessionalTitles(), job.evidenceIds(), "TITLE"));
        dimensions.add(preference(candidate, facts, job, organization));
        return new FitAssessment(UUID.randomUUID(), candidate.id(), job.id(), dimensions, VERSION, candidate.profileVersion(), jobFingerprint, assessedAt);
    }

    private AssessmentDimension experience(CandidateProfile candidate, CandidateFacts facts, JobPosting job, LocalDate qualificationAsOf) {
        if (job.minimumExperienceYears() == null) return unknown(AssessmentDimensionType.EXPERIENCE_FIT, 20, "EXPERIENCE_REQUIREMENT_UNKNOWN");
        if (qualificationAsOf == null) return unknown(AssessmentDimensionType.EXPERIENCE_FIT, 20, "QUALIFICATION_CUTOFF_UNKNOWN");
        var years = CandidateEmploymentExperience.completedYears(candidate, facts, qualificationAsOf);
        if (years.isEmpty()) return unknown(AssessmentDimensionType.EXPERIENCE_FIT, 20, "VERIFIED_EMPLOYMENT_UNKNOWN");
        int points = years.getAsInt() >= job.minimumExperienceYears() ? 20 : 0;
        return known(AssessmentDimensionType.EXPERIENCE_FIT, points, 20, "VERIFIED_EMPLOYMENT_INTERVALS",
            "工作年限按已确认、逐段核验的全职区间合并计算", job.evidenceIds());
    }

    private AssessmentDimension preference(CandidateProfile candidate, CandidateFacts facts, JobPosting job, Organization organization) {
        var locations = confirmed(facts, PREFERRED_LOCATIONS, Set.copyOf(candidate.preferredLocations()));
        var employmentTypes = confirmed(facts, ACCEPTED_EMPLOYMENT_TYPES, candidate.acceptedEmploymentTypes());
        var jobFamilies = confirmed(facts, TARGET_JOB_FAMILIES, candidate.targetJobFamilies());
        var organizationTypes = confirmed(facts, PREFERRED_ORGANIZATION_TYPES, candidate.preferredOrganizationTypes());
        boolean hasAny = !locations.isEmpty() || !employmentTypes.isEmpty() || !jobFamilies.isEmpty() || !organizationTypes.isEmpty();
        if (!hasAny) return unknown(AssessmentDimensionType.PREFERENCE_FIT, 15, "PREFERENCE_UNKNOWN");
        int points = 0;
        if (locations.stream().anyMatch(p -> contains(job.location(), p))) points += 5;
        if (employmentTypes.contains(job.employmentType())) points += 5;
        if (jobFamilies.contains(job.jobFamily())) points += 3;
        if (organizationTypes.contains(organization.organizationType())) points += 2;
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
    private static <T> Set<T> confirmed(CandidateFacts facts, CandidateFacts.CandidateFactKey key, Set<T> values) { return facts.isConfirmed(key) ? values : Set.of(); }
    private static boolean contains(String text, String token) { return text != null && token != null && normalize(text).contains(normalize(token)); }
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s：，。；、]+", ""); }
    private static AssessmentDimension unknown(AssessmentDimensionType type, int maximum, String reason) { return new AssessmentDimension(type, 0, maximum, AssessmentFactStatus.UNKNOWN, reason, "信息不足，未计正向分", List.of()); }
    private static AssessmentDimension known(AssessmentDimensionType type, int points, int maximum, String reason, String explanation, Collection<UUID> evidenceIds) { return new AssessmentDimension(type, points, maximum, AssessmentFactStatus.EXPLICIT, reason, explanation, List.copyOf(evidenceIds)); }
}
