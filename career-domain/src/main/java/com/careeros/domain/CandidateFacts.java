package com.careeros.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;

public final class CandidateFacts {
    public enum CandidateFactKey {
        BIRTH_DATE,
        HIGHEST_EDUCATION,
        MAJORS,
        GRADUATION_YEAR,
        EXPERIENCE_YEARS,
        PROFESSIONAL_TITLES,
        PREFERRED_LOCATIONS,
        ACCEPTED_EMPLOYMENT_TYPES,
        SKILLS,
        RESEARCH_KEYWORDS,
        TARGET_JOB_FAMILIES,
        PREFERRED_ORGANIZATION_TYPES,
        EDUCATION_RECORDS,
        GENDER,
        POLITICAL_AFFILIATION,
        EMPLOYMENT_HISTORY
    }

    public enum CandidateFactStatus { UNCONFIRMED, CONFIRMED, UNKNOWN }
    public enum CandidateFactSource { SEEDED, IMPORTED, USER_EDITED, USER_CONFIRMED }

    public record CandidateFactConfirmation(
        UUID candidateProfileId,
        CandidateFactKey factKey,
        CandidateFactStatus status,
        String valueFingerprint,
        CandidateFactSource source,
        Instant confirmedAt,
        Instant updatedAt
    ) {
        public CandidateFactConfirmation {
            Objects.requireNonNull(candidateProfileId);
            Objects.requireNonNull(factKey);
            Objects.requireNonNull(status);
            requireFingerprint(valueFingerprint);
            Objects.requireNonNull(source);
            Objects.requireNonNull(updatedAt);
            if (status == CandidateFactStatus.CONFIRMED && confirmedAt == null) {
                throw new IllegalArgumentException("confirmedAt is required for a confirmed fact");
            }
            if (status != CandidateFactStatus.CONFIRMED) confirmedAt = null;
        }

        private static void requireFingerprint(String value) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("valueFingerprint must be a lowercase SHA-256 value");
            }
        }
    }

    public static final EnumSet<CandidateFactKey> HARD_QUALIFICATION_KEYS = EnumSet.of(
        CandidateFactKey.BIRTH_DATE,
        CandidateFactKey.HIGHEST_EDUCATION,
        CandidateFactKey.MAJORS,
        CandidateFactKey.GRADUATION_YEAR,
        CandidateFactKey.EXPERIENCE_YEARS,
        CandidateFactKey.PROFESSIONAL_TITLES,
        CandidateFactKey.EDUCATION_RECORDS
    );

    private final CandidateProfile candidate;
    private final Map<CandidateFactKey, CandidateFactConfirmation> confirmations;

    private CandidateFacts(CandidateProfile candidate, Map<CandidateFactKey, CandidateFactConfirmation> confirmations) {
        this.candidate = candidate;
        this.confirmations = Map.copyOf(confirmations);
    }

    public static CandidateFacts resolve(CandidateProfile candidate, Collection<CandidateFactConfirmation> stored) {
        Objects.requireNonNull(candidate);
        var resolved = new EnumMap<CandidateFactKey, CandidateFactConfirmation>(CandidateFactKey.class);
        if (stored != null) {
            for (var fact : stored) {
                if (fact.candidateProfileId().equals(candidate.id())) resolved.put(fact.factKey(), fact);
            }
        }
        return new CandidateFacts(candidate, resolved);
    }

    public static CandidateFacts confirmed(CandidateProfile candidate) {
        var values = new EnumMap<CandidateFactKey, CandidateFactConfirmation>(CandidateFactKey.class);
        for (var key : CandidateFactKey.values()) {
            values.put(key, new CandidateFactConfirmation(
                candidate.id(), key, CandidateFactStatus.CONFIRMED, fingerprint(candidate, key),
                CandidateFactSource.USER_CONFIRMED, Instant.EPOCH, Instant.EPOCH
            ));
        }
        return new CandidateFacts(candidate, values);
    }

    public CandidateFactStatus status(CandidateFactKey key) {
        var confirmation = confirmations.get(key);
        if (confirmation == null) return hasRecordedValue(candidate, key)
            ? CandidateFactStatus.UNCONFIRMED : CandidateFactStatus.UNKNOWN;
        if (!confirmation.valueFingerprint().equals(fingerprint(candidate, key))) {
            return CandidateFactStatus.UNCONFIRMED;
        }
        return confirmation.status();
    }

    public boolean isConfirmed(CandidateFactKey key) {
        return status(key) == CandidateFactStatus.CONFIRMED;
    }

    public boolean hardQualificationsConfirmed() {
        return HARD_QUALIFICATION_KEYS.stream().allMatch(this::isConfirmed);
    }

    public static String fingerprint(CandidateProfile candidate, CandidateFactKey key) {
        String canonical = switch (key) {
            case BIRTH_DATE -> candidate.birthDate().year() + "-" + candidate.birthDate().month() + "-" + candidate.birthDate().day();
            case HIGHEST_EDUCATION -> candidate.highestEducation().name();
            case MAJORS -> canonical(candidate.majors());
            case GRADUATION_YEAR -> String.valueOf(candidate.graduationYear());
            case EXPERIENCE_YEARS -> String.valueOf(candidate.experienceYears());
            case PROFESSIONAL_TITLES -> canonical(candidate.professionalTitles());
            case PREFERRED_LOCATIONS -> canonical(candidate.preferredLocations());
            case ACCEPTED_EMPLOYMENT_TYPES -> canonical(candidate.acceptedEmploymentTypes());
            case SKILLS -> canonical(candidate.skills());
            case RESEARCH_KEYWORDS -> canonical(candidate.researchKeywords());
            case TARGET_JOB_FAMILIES -> canonical(candidate.targetJobFamilies());
            case PREFERRED_ORGANIZATION_TYPES -> canonical(candidate.preferredOrganizationTypes());
            case EDUCATION_RECORDS -> canonicalEducation(candidate.educationRecords());
            case GENDER -> candidate.gender().name();
            case POLITICAL_AFFILIATION -> candidate.politicalAffiliation().name();
            case EMPLOYMENT_HISTORY -> canonicalEmployment(candidate.employmentRecords());
        };
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean hasRecordedValue(CandidateProfile candidate, CandidateFactKey key) {
        return switch (key) {
            case BIRTH_DATE -> true;
            case HIGHEST_EDUCATION -> candidate.highestEducation() != DomainEnums.EducationLevel.UNKNOWN;
            case MAJORS -> !candidate.majors().isEmpty();
            case GRADUATION_YEAR -> candidate.graduationYear() != null;
            case EXPERIENCE_YEARS -> candidate.experienceYears() != null;
            case PROFESSIONAL_TITLES -> !candidate.professionalTitles().isEmpty();
            case PREFERRED_LOCATIONS -> !candidate.preferredLocations().isEmpty();
            case ACCEPTED_EMPLOYMENT_TYPES -> !candidate.acceptedEmploymentTypes().isEmpty();
            case SKILLS -> !candidate.skills().isEmpty();
            case RESEARCH_KEYWORDS -> !candidate.researchKeywords().isEmpty();
            case TARGET_JOB_FAMILIES -> !candidate.targetJobFamilies().isEmpty();
            case PREFERRED_ORGANIZATION_TYPES -> !candidate.preferredOrganizationTypes().isEmpty();
            case EDUCATION_RECORDS -> !candidate.educationRecords().isEmpty();
            case GENDER -> candidate.gender() != DomainEnums.Gender.UNKNOWN;
            case POLITICAL_AFFILIATION -> candidate.politicalAffiliation() != DomainEnums.PoliticalAffiliation.UNKNOWN;
            case EMPLOYMENT_HISTORY -> !candidate.employmentRecords().isEmpty();
        };
    }

    private static String canonical(Collection<?> values) {
        var normalized = new ArrayList<String>();
        values.stream().map(String::valueOf).map(CandidateFacts::normalize).sorted().forEach(normalized::add);
        return encodeOrdered(normalized);
    }

    private static String encodeOrdered(Collection<String> normalized) {
        var encoded = new StringBuilder().append(normalized.size()).append(':');
        for (var value : normalized) encoded.append(value.length()).append(':').append(value);
        return encoded.toString();
    }

    private static String canonicalEducation(Collection<EducationRecord> values) {
        var encodedRecords = new ArrayList<String>();
        for (var value : values) {
            encodedRecords.add(encodeOrdered(java.util.List.of(
                nullable(value.institutionName()), nullable(value.countryOrRegion()), value.educationLevel().name(),
                value.majorName(), nullable(value.graduationYear()), nullable(value.graduationMonth()),
                value.completionStatus().name(), value.credentialVerificationStatus().name()
            )));
        }
        encodedRecords.replaceAll(CandidateFacts::normalize);
        encodedRecords.sort(String::compareTo);
        return encodeOrdered(encodedRecords);
    }

    private static String canonicalEmployment(Collection<CandidateEmploymentRecord> values) {
        var encodedRecords = new ArrayList<String>();
        for (var value : values) {
            encodedRecords.add(encodeOrdered(java.util.List.of(
                value.employerName(), value.roleTitle(), value.startsOn().toString(), nullable(value.endsOn()),
                value.employmentMode().name(), value.verificationStatus().name(), canonical(value.evidenceTypes())
            )));
        }
        encodedRecords.replaceAll(CandidateFacts::normalize);
        encodedRecords.sort(String::compareTo);
        return encodeOrdered(encodedRecords);
    }

    private static String nullable(Object value) { return value == null ? "<null>" : String.valueOf(value); }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFKC);
    }
}
