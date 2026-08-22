package com.careeros.domain;

import static com.careeros.domain.CandidateFacts.CandidateFactKey.BIRTH_DATE;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.EDUCATION_RECORDS;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.SKILLS;
import static com.careeros.domain.CandidateFacts.CandidateFactSource.USER_CONFIRMED;
import static com.careeros.domain.CandidateFacts.CandidateFactStatus.CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.EducationRecord.CompletionStatus;
import com.careeros.domain.EducationRecord.CredentialVerificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateFactsTest {
    @Test
    void rejectsAConfirmationWhoseFingerprintBelongsToAnOlderValue() {
        var candidate = candidate(new PartialDate(1992, 12, null));
        var staleFingerprint = CandidateFacts.fingerprint(candidate(new PartialDate(1991, 9, null)), BIRTH_DATE);
        var stored = new CandidateFacts.CandidateFactConfirmation(
            candidate.id(), BIRTH_DATE, CONFIRMED, staleFingerprint, USER_CONFIRMED,
            Instant.parse("2026-08-20T12:00:00Z"), Instant.parse("2026-08-20T12:00:00Z")
        );

        var facts = CandidateFacts.resolve(candidate, List.of(stored));

        assertThat(facts.isConfirmed(BIRTH_DATE)).isFalse();
        assertThat(facts.status(BIRTH_DATE)).isEqualTo(CandidateFacts.CandidateFactStatus.UNCONFIRMED);
    }

    @Test
    void collectionFingerprintDistinguishesElementsFromEmbeddedSeparators() {
        var separateElements = candidateWithSkills(Set.of("A", "B"));
        var embeddedSeparator = candidateWithSkills(Set.of("A\nB"));

        assertThat(CandidateFacts.fingerprint(separateElements, SKILLS))
            .isNotEqualTo(CandidateFacts.fingerprint(embeddedSeparator, SKILLS));
    }

    @Test
    void educationFingerprintChangesWhenExpectedDegreeBecomesCompleted() {
        var expected = candidateWithEducation(CompletionStatus.EXPECTED);
        var completed = candidateWithEducation(CompletionStatus.COMPLETED);

        assertThat(CandidateFacts.fingerprint(expected, EDUCATION_RECORDS))
            .isNotEqualTo(CandidateFacts.fingerprint(completed, EDUCATION_RECORDS));
    }

    @Test
    void educationFingerprintKeepsInstitutionAndCountryFieldMeaning() {
        var original = candidateWithEducationLocation("示例海外大学", "示例国");
        var swapped = candidateWithEducationLocation("示例国", "示例海外大学");

        assertThat(CandidateFacts.fingerprint(original, EDUCATION_RECORDS))
            .isNotEqualTo(CandidateFacts.fingerprint(swapped, EDUCATION_RECORDS));
    }

    private static CandidateProfile candidate(PartialDate birthDate) {
        return new CandidateProfile(
            UUID.fromString("01992f09-0000-7000-8000-000000000001"), "候选人", birthDate,
            EducationLevel.MASTER, Set.of("计算机科学与技术"), 2018, 6, Set.of(),
            List.of("杭州"), Set.of(), "profile-v1"
        );
    }

    private static CandidateProfile candidateWithSkills(Set<String> skills) {
        var value = candidate(new PartialDate(1992, 12, null));
        return new CandidateProfile(
            value.id(), value.displayName(), value.birthDate(), value.highestEducation(), value.majors(),
            value.graduationYear(), value.experienceYears(), value.professionalTitles(), value.preferredLocations(),
            value.acceptedEmploymentTypes(), value.profileVersion(), skills, Set.of(), Set.of(), Set.of()
        );
    }

    private static CandidateProfile candidateWithEducation(CompletionStatus status) {
        var value = candidate(new PartialDate(1992, 12, null));
        var education = List.of(
            new EducationRecord(null, null, EducationLevel.BACHELOR, "计算机科学与技术", 2014,
                null, CompletionStatus.COMPLETED, CredentialVerificationStatus.NOT_REQUIRED),
            new EducationRecord("示例海外大学", "示例国", EducationLevel.MASTER, "计算机科学", 2027,
                null, status, CredentialVerificationStatus.PLANNED)
        );
        return new CandidateProfile(
            value.id(), value.displayName(), value.birthDate(), value.highestEducation(), value.majors(),
            value.graduationYear(), value.experienceYears(), value.professionalTitles(), value.preferredLocations(),
            value.acceptedEmploymentTypes(), value.profileVersion(), value.skills(), value.researchKeywords(),
            value.targetJobFamilies(), value.preferredOrganizationTypes(), education
        );
    }

    private static CandidateProfile candidateWithEducationLocation(String institution, String country) {
        var value = candidate(new PartialDate(1992, 12, null));
        var education = List.of(new EducationRecord(
            institution, country, EducationLevel.MASTER, "计算机科学", 2027, null,
            CompletionStatus.EXPECTED, CredentialVerificationStatus.PLANNED
        ));
        return new CandidateProfile(
            value.id(), value.displayName(), value.birthDate(), value.highestEducation(), value.majors(),
            value.graduationYear(), value.experienceYears(), value.professionalTitles(), value.preferredLocations(),
            value.acceptedEmploymentTypes(), value.profileVersion(), value.skills(), value.researchKeywords(),
            value.targetJobFamilies(), value.preferredOrganizationTypes(), education
        );
    }
}
