package com.careeros.application;

import static com.careeros.domain.CandidateFacts.*;

import com.careeros.domain.CandidateProfile;
import com.careeros.domain.CandidateFacts;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class CandidateProfileService {
    private final RepositoryPorts.CandidateProfiles profiles;
    private final RepositoryPorts.CandidateFactConfirmations confirmations;
    private final Clock clock;

    public CandidateProfileService(
        RepositoryPorts.CandidateProfiles profiles,
        RepositoryPorts.CandidateFactConfirmations confirmations,
        Clock clock
    ) {
        this.profiles = Objects.requireNonNull(profiles);
        this.confirmations = Objects.requireNonNull(confirmations);
        this.clock = Objects.requireNonNull(clock);
    }

    public CandidateProfile saveDraft(UUID candidateId, CandidateProfile draft) {
        Objects.requireNonNull(candidateId);
        Objects.requireNonNull(draft);
        requireLockedProfile(candidateId);
        if (!candidateId.equals(draft.id())) throw new IllegalArgumentException("candidate id does not match path");
        Instant now = clock.instant();
        var stored = byKey(confirmations.findByCandidateId(candidateId));
        var saved = profiles.save(withVersion(draft, newVersion()));
        confirmations.saveAll(reconcile(saved, stored, now));
        return saved;
    }

    public CandidateProfile create(CandidateProfile draft) {
        Objects.requireNonNull(draft);
        var saved = profiles.save(withVersion(draft, newVersion()));
        confirmations.saveAll(reconcile(saved, Map.of(), clock.instant()));
        return saved;
    }

    public CandidateProfileFacts confirm(UUID candidateId, Set<CandidateFactKey> requestedKeys) {
        var current = requireLockedProfile(candidateId);
        if (requestedKeys == null || requestedKeys.isEmpty()) {
            throw new IllegalArgumentException("at least one fact key is required");
        }
        Instant now = clock.instant();
        var stored = byKey(confirmations.findByCandidateId(candidateId));
        var next = new ArrayList<CandidateFactConfirmation>();
        for (var key : CandidateFactKey.values()) {
            String currentFingerprint = fingerprint(current, key);
            if (requestedKeys.contains(key)) {
                var currentValueStatus = CandidateFacts.resolve(current, List.of()).status(key);
                var confirmedStatus = currentValueStatus == CandidateFactStatus.UNKNOWN
                    ? CandidateFactStatus.UNKNOWN : CandidateFactStatus.CONFIRMED;
                next.add(new CandidateFactConfirmation(candidateId, key, confirmedStatus,
                    currentFingerprint, CandidateFactSource.USER_CONFIRMED,
                    confirmedStatus == CandidateFactStatus.CONFIRMED ? now : null, now));
            } else {
                next.add(currentState(current, key, stored.get(key), now));
            }
        }
        var saved = profiles.save(withVersion(current, newVersion()));
        confirmations.saveAll(next);
        return snapshot(saved, next);
    }

    public CandidateProfileFacts facts(UUID candidateId) {
        var profile = requireProfile(candidateId);
        return snapshot(profile, confirmations.findByCandidateId(candidateId));
    }

    private CandidateProfile requireProfile(UUID id) {
        return profiles.findById(id).orElseThrow(() -> new CandidateProfileNotFoundException("Candidate not found: " + id));
    }

    private CandidateProfile requireLockedProfile(UUID id) {
        return profiles.findByIdForUpdate(id)
            .orElseThrow(() -> new CandidateProfileNotFoundException("Candidate not found: " + id));
    }

    private CandidateProfileFacts snapshot(CandidateProfile profile, List<CandidateFactConfirmation> stored) {
        var resolved = CandidateFacts.resolve(profile, stored);
        var statuses = new EnumMap<CandidateFactKey, CandidateFactStatus>(CandidateFactKey.class);
        for (var key : CandidateFactKey.values()) statuses.put(key, resolved.status(key));
        long confirmed = statuses.values().stream().filter(value -> value == CandidateFactStatus.CONFIRMED).count();
        long unconfirmed = statuses.values().stream().filter(value -> value == CandidateFactStatus.UNCONFIRMED).count();
        long unknown = statuses.values().stream().filter(value -> value == CandidateFactStatus.UNKNOWN).count();
        return new CandidateProfileFacts(profile, statuses, confirmed, unconfirmed, unknown, resolved.hardQualificationsConfirmed());
    }

    private List<CandidateFactConfirmation> reconcile(
        CandidateProfile profile,
        Map<CandidateFactKey, CandidateFactConfirmation> stored,
        Instant now
    ) {
        var next = new ArrayList<CandidateFactConfirmation>();
        for (var key : CandidateFactKey.values()) next.add(currentState(profile, key, stored.get(key), now));
        return next;
    }

    private CandidateFactConfirmation currentState(
        CandidateProfile profile,
        CandidateFactKey key,
        CandidateFactConfirmation previous,
        Instant now
    ) {
        String currentFingerprint = fingerprint(profile, key);
        if (previous != null && previous.valueFingerprint().equals(currentFingerprint)) return previous;
        var defaultStatus = CandidateFacts.resolve(profile, List.of()).status(key);
        return new CandidateFactConfirmation(profile.id(), key, defaultStatus, currentFingerprint,
            CandidateFactSource.USER_EDITED, null, now);
    }

    private static Map<CandidateFactKey, CandidateFactConfirmation> byKey(List<CandidateFactConfirmation> values) {
        var result = new EnumMap<CandidateFactKey, CandidateFactConfirmation>(CandidateFactKey.class);
        values.forEach(value -> result.put(value.factKey(), value));
        return result;
    }

    private static CandidateProfile withVersion(CandidateProfile value, String version) {
        return new CandidateProfile(
            value.id(), value.displayName(), value.birthDate(), value.highestEducation(), value.majors(),
            value.graduationYear(), value.experienceYears(), value.professionalTitles(), value.preferredLocations(),
            value.acceptedEmploymentTypes(), version, value.skills(), value.researchKeywords(), value.targetJobFamilies(),
            value.preferredOrganizationTypes(), value.educationRecords(), value.gender(), value.politicalAffiliation(),
            value.employmentRecords()
        );
    }

    private static String newVersion() { return "profile-" + UUID.randomUUID(); }

    public record CandidateProfileFacts(
        CandidateProfile profile,
        Map<CandidateFactKey, CandidateFactStatus> statuses,
        long confirmedCount,
        long unconfirmedCount,
        long unknownCount,
        boolean decisionReady
    ) {
        public CandidateProfileFacts {
            Objects.requireNonNull(profile);
            statuses = Map.copyOf(statuses);
        }
    }

    public static final class CandidateProfileNotFoundException extends RuntimeException {
        public CandidateProfileNotFoundException(String message) { super(message); }
    }
}
