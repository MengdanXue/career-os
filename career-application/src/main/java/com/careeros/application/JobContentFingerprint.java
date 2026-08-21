package com.careeros.application;

import com.careeros.application.JobUpsertService.NormalizedJob;
import com.careeros.domain.JobPosting;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class JobContentFingerprint {
    private JobContentFingerprint() {}

    public static String of(JobPosting job) {
        return fingerprint(
            job.recruitmentEventId(), job.organizationId(), job.externalJobCode(), job.title(),
            job.jobFamily().name(), job.employmentType().name(),
            job.location(), job.headcount(), job.minimumEducation().name(), job.exactMajors(),
            job.acceptedGraduationYears(), job.maximumAge(), job.ageReferenceDate(),
            job.minimumExperienceYears(), job.requiredProfessionalTitles(), job.duties(),
            job.sourceUrl(), job.evidenceIds(), job.supervisingDepartment(), job.jobCategory(), job.jobGrade(),
            job.educationRequirementText(), job.degreeRequirement(), job.majorRequirementText(),
            job.ageRequirementText(), job.genderRequirement(), job.candidateScope(), job.otherRequirements(),
            job.originalRequirementText(), job.interviewRatio(), job.professionalTestRequired(), job.contactPhone());
    }

    public static String of(NormalizedJob job) {
        return fingerprint(
            job.recruitmentEventId(), job.organizationId(), job.externalJobCode(), job.title(),
            job.jobFamily().name(), job.employmentType().name(),
            job.location(), job.headcount(), job.minimumEducation().name(), job.exactMajors(),
            job.acceptedGraduationYears(), job.maximumAge(), job.ageReferenceDate(),
            job.minimumExperienceYears(), job.requiredProfessionalTitles(), job.duties(),
            job.sourceUrl(), job.evidenceIds(), job.supervisingDepartment(), job.jobCategory(), job.jobGrade(),
            job.educationRequirementText(), job.degreeRequirement(), job.majorRequirementText(),
            job.ageRequirementText(), job.genderRequirement(), job.candidateScope(), job.otherRequirements(),
            job.originalRequirementText(), job.interviewRatio(), job.professionalTestRequired(), job.contactPhone());
    }

    private static String fingerprint(
        Object recruitmentEventId, Object organizationId, String externalJobCode, String title,
        String jobFamily, String employmentType,
        String location, int headcount, String minimumEducation, Set<?> exactMajors,
        Set<?> acceptedGraduationYears, Object maximumAge, Object ageReferenceDate,
        Object minimumExperienceYears, Set<?> requiredProfessionalTitles, String duties,
        String sourceUrl, Collection<?> evidenceIds,
        Object supervisingDepartment, Object jobCategory, Object jobGrade, Object educationRequirementText,
        Object degreeRequirement, Object majorRequirementText, Object ageRequirementText, Object genderRequirement,
        Object candidateScope, Object otherRequirements, Object originalRequirementText, Object interviewRatio,
        Object professionalTestRequired, Object contactPhone
    ) {
        MessageDigest digest = sha256();
        put(digest, value(organizationId));
        put(digest, normalizeIdentity(externalJobCode));
        put(digest, normalizeContent(title));
        put(digest, jobFamily);
        put(digest, employmentType);
        put(digest, normalizeContent(location));
        put(digest, Integer.toString(headcount));
        put(digest, minimumEducation);
        putCollection(digest, exactMajors);
        putCollection(digest, acceptedGraduationYears);
        put(digest, value(maximumAge));
        put(digest, value(ageReferenceDate));
        put(digest, value(minimumExperienceYears));
        putCollection(digest, requiredProfessionalTitles);
        put(digest, normalizeContent(duties));
        put(digest, normalizeUrl(sourceUrl));
        put(digest, Boolean.toString(evidenceIds != null && !evidenceIds.isEmpty()));
        put(digest, normalizeContent(value(supervisingDepartment)));
        put(digest, normalizeContent(value(jobCategory)));
        put(digest, normalizeContent(value(jobGrade)));
        put(digest, normalizeContent(value(educationRequirementText)));
        put(digest, normalizeContent(value(degreeRequirement)));
        put(digest, normalizeContent(value(majorRequirementText)));
        put(digest, normalizeContent(value(ageRequirementText)));
        put(digest, normalizeContent(value(genderRequirement)));
        put(digest, normalizeContent(value(candidateScope)));
        put(digest, normalizeContent(value(otherRequirements)));
        put(digest, normalizeContent(value(originalRequirementText)));
        put(digest, normalizeContent(value(interviewRatio)));
        put(digest, value(professionalTestRequired));
        put(digest, normalizeContent(value(contactPhone)));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void putCollection(MessageDigest digest, Collection<?> values) {
        List<String> canonical = values.stream()
            .map(value -> normalizeContent(String.valueOf(value)))
            .sorted()
            .toList();
        putInt(digest, canonical.size());
        canonical.forEach(value -> put(digest, value));
    }

    private static void put(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static String normalizeIdentity(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private static String normalizeContent(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String normalizeUrl(String value) {
        if (value == null) return "";
        String trimmed = value.strip();
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null || uri.isOpaque() || uri.getHost() == null) return trimmed;
            StringBuilder normalized = new StringBuilder(uri.getScheme().toLowerCase(Locale.ROOT)).append("://");
            if (uri.getRawUserInfo() != null) normalized.append(uri.getRawUserInfo()).append('@');
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            normalized.append(host.indexOf(':') >= 0 ? "[" + host + "]" : host);
            if (uri.getPort() >= 0) normalized.append(':').append(uri.getPort());
            if (uri.getRawPath() != null) normalized.append(uri.getRawPath());
            if (uri.getRawQuery() != null) normalized.append('?').append(uri.getRawQuery());
            if (uri.getRawFragment() != null) normalized.append('#').append(uri.getRawFragment());
            return normalized.toString();
        } catch (IllegalArgumentException ignored) {
            return trimmed;
        }
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
