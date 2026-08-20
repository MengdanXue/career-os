package com.careeros.domain;

import static com.careeros.domain.CandidateFacts.CandidateFactKey.BIRTH_DATE;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.SKILLS;
import static com.careeros.domain.CandidateFacts.CandidateFactSource.USER_CONFIRMED;
import static com.careeros.domain.CandidateFacts.CandidateFactStatus.CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.DomainEnums.EducationLevel;
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
}
