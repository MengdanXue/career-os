package com.careeros.infrastructure.persistence;

import static com.careeros.application.JobUpsertService.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.ExtractionExceptions;
import com.careeros.application.ExtractionPorts.VerifiedProposalWriter;
import com.careeros.application.JobUpsertService;
import com.careeros.domain.ExtractedFact;
import com.careeros.domain.RecruitmentExtractionProposal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultJobUpsertService implements JobUpsertService, VerifiedProposalWriter {
    private final JobPostingJpaRepository jobs;
    private final RecruitmentEventJpaRepository events;
    private final OrganizationJpaRepository organizations;
    private final Clock clock;
    private final PostgresEventSourceLock eventSourceLock;

    @Autowired
    public DefaultJobUpsertService(
        JobPostingJpaRepository jobs,
        RecruitmentEventJpaRepository events,
        OrganizationJpaRepository organizations,
        PostgresEventSourceLock eventSourceLock
    ) {
        this(jobs, events, organizations, Clock.systemUTC(), eventSourceLock);
    }

    DefaultJobUpsertService(
        JobPostingJpaRepository jobs,
        RecruitmentEventJpaRepository events,
        OrganizationJpaRepository organizations,
        Clock clock
    ) {
        this(jobs, events, organizations, clock, null);
    }

    private DefaultJobUpsertService(
        JobPostingJpaRepository jobs,
        RecruitmentEventJpaRepository events,
        OrganizationJpaRepository organizations,
        Clock clock,
        PostgresEventSourceLock eventSourceLock
    ) {
        this.jobs = Objects.requireNonNull(jobs);
        this.events = Objects.requireNonNull(events);
        this.organizations = Objects.requireNonNull(organizations);
        this.clock = Objects.requireNonNull(clock);
        this.eventSourceLock = eventSourceLock;
    }

    @Override
    @Transactional
    public JobUpsertResult upsert(JobUpsertBatch batch) {
        Objects.requireNonNull(batch, "batch");
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int deactivated = 0;
        Instant now = clock.instant();
        Set<String> seen = new HashSet<>();
        List<UUID> ids = new ArrayList<>();

        for (NormalizedJob job : batch.jobs()) {
            if (!batch.recruitmentEventId().equals(job.recruitmentEventId())) {
                throw new IllegalArgumentException("Job belongs to another recruitment event");
            }
            if (!batch.sourceUrl().equals(job.sourceUrl())) {
                throw new IllegalArgumentException("Job source URL differs from its batch");
            }
            String key = stableKey(job);
            if (!seen.add(key)) throw new IllegalArgumentException("Batch contains duplicate stable job key: " + key);
            String fingerprint = contentFingerprint(job);
            var existing = jobs.findByStableJobKey(key);
            if (existing.isPresent() && fingerprint.equals(existing.orElseThrow().contentFingerprint)) {
                JpaModels.JobPostingEntity entity = existing.orElseThrow();
                entity.active = true;
                entity.lastSeenAt = now;
                jobs.save(entity);
                ids.add(entity.id);
                unchanged++;
                continue;
            }

            JpaModels.JobPostingEntity entity = existing.orElseGet(JpaModels.JobPostingEntity::new);
            if (existing.isEmpty()) {
                entity.id = UUID.randomUUID();
                entity.firstSeenAt = now;
                inserted++;
            } else {
                updated++;
            }
            copy(job, entity);
            entity.stableJobKey = key;
            entity.contentFingerprint = fingerprint;
            entity.active = true;
            entity.lastSeenAt = now;
            jobs.save(entity);
            ids.add(entity.id);
        }

        if (batch.completeSnapshot() && batch.validationErrors().isEmpty()) {
            for (JpaModels.JobPostingEntity existing : jobs.findByRecruitmentEventId(batch.recruitmentEventId())) {
                if (existing.stableJobKey != null && !seen.contains(existing.stableJobKey) && existing.active) {
                    existing.active = false;
                    existing.lastSeenAt = now;
                    jobs.save(existing);
                    deactivated++;
                }
            }
        }
        return new JobUpsertResult(inserted, updated, unchanged, deactivated, ids);
    }

    @Override
    public String stableKey(NormalizedJob job) {
        Objects.requireNonNull(job, "job");
        String codeOrTitle = blank(job.externalJobCode()) ? job.title() : job.externalJobCode();
        return sha256(job.sourceUrl() + "|" + normalizeIdentity(job.organizationName())
            + "|" + normalizeIdentity(codeOrTitle));
    }

    @Override
    public String contentFingerprint(NormalizedJob job) {
        Objects.requireNonNull(job, "job");
        return sha256(String.join("|",
            normalizeIdentity(job.externalJobCode()), normalizeContent(job.title()), job.jobFamily().name(),
            job.employmentType().name(), normalizeContent(job.location()), Integer.toString(job.headcount()),
            job.minimumEducation().name(), canonical(job.exactMajors()), canonical(job.acceptedGraduationYears()),
            value(job.maximumAge()), value(job.ageReferenceDate()), value(job.minimumExperienceYears()),
            canonical(job.requiredProfessionalTitles()), normalizeContent(job.duties())));
    }

    @Override
    @Transactional
    public JobUpsertResult write(RecruitmentExtractionProposal proposal, List<UUID> evidenceIds) {
        Objects.requireNonNull(proposal, "proposal");
        if (evidenceIds == null || !evidenceIds.contains(proposal.source().evidenceId())) {
            throw new ExtractionExceptions.InvalidProposalException(
                "Verified proposal source evidence is not part of the extraction");
        }
        rejectInterpretedFacts(proposal);
        if (eventSourceLock != null) eventSourceLock.lock(proposal.source().sourceUrl());
        JpaModels.OrganizationEntity organization = organizations.findFirstByName(proposal.organization().name())
            .orElseGet(() -> createOrganization(proposal));
        JpaModels.RecruitmentEventEntity event = events.findFirstBySourceUrl(proposal.source().sourceUrl())
            .map(existing -> refreshEvent(existing, proposal, evidenceIds))
            .orElseGet(() -> createEvent(proposal));

        List<NormalizedJob> normalized = proposal.jobs().stream()
            .map(job -> new NormalizedJob(
                event.id, organization.id, organization.name, job.externalJobCode(), required(job.title(), "title"),
                job.jobFamily(), valueOr(job.employmentType(), EmploymentType.UNKNOWN), job.location(),
                required(job.headcount(), "headcount"), valueOr(job.minimumEducation(), EducationLevel.UNKNOWN),
                splitMajors(valueOr(job.majorText(), null)), valueOr(job.acceptedGraduationYears(), Set.of()),
                valueOr(job.maximumAge(), null), valueOr(proposal.recruitmentEvent().applicationEndsOn(), null),
                valueOr(job.minimumExperienceYears(), null), Set.of(), job.duties(), proposal.source().sourceUrl(),
                evidenceIds))
            .toList();
        return upsert(new JobUpsertBatch(
            event.id, proposal.source().sourceUrl(), normalized, false, List.of()));
    }

    private JpaModels.OrganizationEntity createOrganization(RecruitmentExtractionProposal proposal) {
        var entity = new JpaModels.OrganizationEntity();
        entity.id = UUID.randomUUID();
        entity.name = proposal.organization().name();
        entity.organizationType = valueOr(
            proposal.organization().organizationType(), OrganizationType.UNKNOWN);
        return organizations.save(entity);
    }

    private JpaModels.RecruitmentEventEntity createEvent(RecruitmentExtractionProposal proposal) {
        var source = proposal.source();
        var event = proposal.recruitmentEvent();
        var entity = new JpaModels.RecruitmentEventEntity();
        entity.id = UUID.randomUUID();
        entity.title = event.title();
        entity.recruitmentYear = event.recruitmentYear();
        entity.eventType = event.eventType();
        entity.publishedOn = valueOr(event.publishedOn(), null);
        entity.applicationStartsOn = valueOr(event.applicationStartsOn(), null);
        entity.applicationEndsOn = valueOr(event.applicationEndsOn(), null);
        entity.sourceUrl = source.sourceUrl();
        entity.defaultEmploymentType = EmploymentType.UNKNOWN;
        entity.evidenceIds = new ArrayList<>(List.of(source.evidenceId()));
        return events.save(entity);
    }

    private JpaModels.RecruitmentEventEntity refreshEvent(
        JpaModels.RecruitmentEventEntity entity,
        RecruitmentExtractionProposal proposal,
        List<UUID> evidenceIds
    ) {
        var event = proposal.recruitmentEvent();
        entity.title = event.title();
        entity.recruitmentYear = event.recruitmentYear();
        entity.eventType = event.eventType();
        if (event.publishedOn().value() != null) entity.publishedOn = event.publishedOn().value();
        if (event.applicationStartsOn().value() != null) {
            entity.applicationStartsOn = event.applicationStartsOn().value();
        }
        if (event.applicationEndsOn().value() != null) {
            entity.applicationEndsOn = event.applicationEndsOn().value();
        }
        LinkedHashSet<UUID> mergedEvidence = new LinkedHashSet<>();
        if (entity.evidenceIds != null) mergedEvidence.addAll(entity.evidenceIds);
        mergedEvidence.add(proposal.source().evidenceId());
        mergedEvidence.addAll(evidenceIds);
        entity.evidenceIds = new ArrayList<>(mergedEvidence);
        return events.save(entity);
    }

    private static void copy(NormalizedJob source, JpaModels.JobPostingEntity target) {
        target.recruitmentEventId = source.recruitmentEventId();
        target.organizationId = source.organizationId();
        target.externalJobCode = emptyToNull(source.externalJobCode());
        target.title = source.title();
        target.jobFamily = source.jobFamily();
        target.employmentType = source.employmentType();
        target.location = source.location();
        target.headcount = source.headcount();
        target.minimumEducation = source.minimumEducation();
        target.exactMajors = new LinkedHashSet<>(source.exactMajors());
        target.acceptedGraduationYears = new LinkedHashSet<>(source.acceptedGraduationYears());
        target.maximumAge = source.maximumAge();
        target.ageReferenceDate = source.ageReferenceDate();
        target.minimumExperienceYears = source.minimumExperienceYears();
        target.requiredProfessionalTitles = new LinkedHashSet<>(source.requiredProfessionalTitles());
        target.duties = source.duties();
        target.sourceUrl = source.sourceUrl();
        target.evidenceIds = new ArrayList<>(source.evidenceIds());
    }

    private static void rejectInterpretedFacts(RecruitmentExtractionProposal proposal) {
        reject(proposal.organization().organizationType(), "organization.organizationType");
        reject(proposal.recruitmentEvent().publishedOn(), "recruitmentEvent.publishedOn");
        reject(proposal.recruitmentEvent().applicationStartsOn(), "recruitmentEvent.applicationStartsOn");
        reject(proposal.recruitmentEvent().applicationEndsOn(), "recruitmentEvent.applicationEndsOn");
        for (int index = 0; index < proposal.jobs().size(); index++) {
            var job = proposal.jobs().get(index);
            String path = "jobs[" + index + "]";
            reject(job.title(), path + ".title");
            reject(job.headcount(), path + ".headcount");
            reject(job.employmentType(), path + ".employmentType");
            reject(job.minimumEducation(), path + ".minimumEducation");
            reject(job.degree(), path + ".degree");
            reject(job.majorText(), path + ".majorText");
            reject(job.maximumAge(), path + ".maximumAge");
            reject(job.acceptedGraduationYears(), path + ".acceptedGraduationYears");
            reject(job.minimumExperienceYears(), path + ".minimumExperienceYears");
        }
    }

    private static void reject(ExtractedFact<?> fact, String path) {
        if (fact.factStatus() == FactStatus.INTERPRETED) {
            throw new ExtractionExceptions.InvalidProposalException(
                "Verified proposal contains interpreted fact: " + path);
        }
    }

    private static <T> T required(ExtractedFact<T> fact, String field) {
        if (fact.value() == null) {
            throw new ExtractionExceptions.InvalidProposalException("Verified proposal is missing " + field);
        }
        return fact.value();
    }

    private static <T> T valueOr(ExtractedFact<T> fact, T fallback) {
        return fact.value() == null ? fallback : fact.value();
    }

    private static Set<String> splitMajors(String text) {
        if (blank(text)) return Set.of();
        return java.util.Arrays.stream(text.split("[、,，;；/\\n]"))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String canonical(Set<?> values) {
        return values.stream().map(value -> normalizeContent(String.valueOf(value)))
            .sorted(Comparator.naturalOrder()).collect(Collectors.joining(","));
    }

    private static String normalizeIdentity(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private static String normalizeContent(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String emptyToNull(String value) { return blank(value) ? null : value; }
}
