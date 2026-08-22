package com.careeros.application;

import static com.careeros.domain.CandidateFacts.CandidateFactKey.*;
import static com.careeros.domain.CandidateFacts.CandidateFactStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.CandidateFacts.CandidateFactConfirmation;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.DomainEnums.*;
import com.careeros.domain.EducationRecord;
import com.careeros.domain.EducationRecord.CompletionStatus;
import com.careeros.domain.EducationRecord.CredentialVerificationStatus;
import com.careeros.domain.PartialDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateProfileServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void serverConfirmationPersistsAndOwnsTheNewProfileVersion() {
        var profiles = new Profiles(candidate("client-controlled", Set.of("Java")));
        var confirmations = new Confirmations();
        var service = service(profiles, confirmations);

        var result = service.confirm(profiles.profile.id(), EnumSet.allOf(CandidateFacts.CandidateFactKey.class));
        var reread = service.facts(profiles.profile.id());

        assertThat(result.profile().profileVersion()).startsWith("profile-").isNotEqualTo("client-controlled");
        assertThat(reread.profile().profileVersion()).isEqualTo(result.profile().profileVersion());
        assertThat(reread.statuses()).containsEntry(BIRTH_DATE, CONFIRMED).containsEntry(SKILLS, CONFIRMED);
        assertThat(reread.decisionReady()).isTrue();
    }

    @Test
    void editingOneConfirmedValueInvalidatesOnlyThatFact() {
        var original = candidate("seed-v1", Set.of("Java"));
        var profiles = new Profiles(original);
        var confirmations = new Confirmations();
        var service = service(profiles, confirmations);
        service.confirm(original.id(), EnumSet.allOf(CandidateFacts.CandidateFactKey.class));
        var edited = candidate("malicious-client-version", Set.of("Java", "Spring Boot"));

        var saved = service.saveDraft(original.id(), edited);
        var facts = service.facts(original.id());

        assertThat(saved.profileVersion()).startsWith("profile-").isNotEqualTo("malicious-client-version");
        assertThat(facts.statuses()).containsEntry(SKILLS, UNCONFIRMED);
        assertThat(facts.statuses()).containsEntry(BIRTH_DATE, CONFIRMED);
        assertThat(facts.statuses()).containsEntry(HIGHEST_EDUCATION, CONFIRMED);
    }

    @Test
    void savingDraftLocksTheCurrentCandidateBeforeReconcilingConfirmations() {
        var profiles = new Profiles(candidate("seed-v1", Set.of("Java")));
        var service = service(profiles, new Confirmations());

        service.saveDraft(profiles.profile.id(), candidate("client-v2", Set.of("Java", "Spring Boot")));

        assertThat(profiles.lockedReads).isEqualTo(1);
    }

    @Test
    void confirmingFactsLocksTheCurrentCandidateBeforeWritingFingerprints() {
        var profiles = new Profiles(candidate("seed-v1", Set.of("Java")));
        var service = service(profiles, new Confirmations());

        service.confirm(profiles.profile.id(), Set.of(SKILLS));

        assertThat(profiles.lockedReads).isEqualTo(1);
    }

    @Test
    void editingExpectedEducationPreservesBothRecordsAndInvalidatesOnlyEducationFacts() {
        var original = candidate("seed-v1", Set.of("Java"), CompletionStatus.EXPECTED);
        var profiles = new Profiles(original);
        var confirmations = new Confirmations();
        var service = service(profiles, confirmations);
        service.confirm(original.id(), EnumSet.allOf(CandidateFacts.CandidateFactKey.class));

        var saved = service.saveDraft(
            original.id(), candidate("client-version", Set.of("Java"), CompletionStatus.COMPLETED));
        var facts = service.facts(original.id());

        assertThat(saved.educationRecords()).hasSize(2);
        assertThat(saved.educationRecords().get(1).completionStatus()).isEqualTo(CompletionStatus.COMPLETED);
        assertThat(facts.statuses()).containsEntry(EDUCATION_RECORDS, UNCONFIRMED);
        assertThat(facts.statuses()).containsEntry(SKILLS, CONFIRMED);
    }

    private static CandidateProfileService service(Profiles profiles, Confirmations confirmations) {
        return new CandidateProfileService(profiles, confirmations, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CandidateProfile candidate(String version, Set<String> skills) {
        return candidate(version, skills, CompletionStatus.EXPECTED);
    }

    private static CandidateProfile candidate(String version, Set<String> skills, CompletionStatus masterStatus) {
        return new CandidateProfile(
            UUID.fromString("01992f09-0000-7000-8000-000000000001"), "候选人",
            new PartialDate(1992, 12, null), EducationLevel.MASTER, Set.of("计算机科学与技术"),
            2018, 6, Set.of("中级：计算机应用"), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), version, skills, Set.of("数据治理"),
            Set.of(JobFamily.INFORMATION_SYSTEMS), Set.of(OrganizationType.PUBLIC_INSTITUTION),
            List.of(
                new EducationRecord(null, null, EducationLevel.BACHELOR, "计算机科学与技术", 2014,
                    null, CompletionStatus.COMPLETED, CredentialVerificationStatus.UNKNOWN),
                new EducationRecord("示例海外大学", "示例国", EducationLevel.MASTER, "计算机科学", 2027,
                    null, masterStatus, CredentialVerificationStatus.PLANNED)
            )
        );
    }

    private static final class Profiles implements RepositoryPorts.CandidateProfiles {
        private CandidateProfile profile;
        private int lockedReads;
        private Profiles(CandidateProfile profile) { this.profile = profile; }
        public CandidateProfile save(CandidateProfile value) { profile = value; return value; }
        public Optional<CandidateProfile> findById(UUID id) { return profile.id().equals(id) ? Optional.of(profile) : Optional.empty(); }
        public Optional<CandidateProfile> findByIdForUpdate(UUID id) {
            lockedReads++;
            return findById(id);
        }
        public List<CandidateProfile> findAll() { return List.of(profile); }
        public void deleteById(UUID id) { if (profile.id().equals(id)) profile = null; }
    }

    private static final class Confirmations implements RepositoryPorts.CandidateFactConfirmations {
        private final Map<String, CandidateFactConfirmation> rows = new LinkedHashMap<>();
        public List<CandidateFactConfirmation> findByCandidateId(UUID candidateId) {
            return rows.values().stream().filter(value -> value.candidateProfileId().equals(candidateId)).toList();
        }
        public List<CandidateFactConfirmation> saveAll(List<CandidateFactConfirmation> values) {
            values.forEach(value -> rows.put(value.candidateProfileId() + ":" + value.factKey(), value));
            return new ArrayList<>(values);
        }
    }
}
