package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.careeros.application.JobUpsertService.JobUpsertBatch;
import com.careeros.application.JobUpsertService.NormalizedJob;
import com.careeros.application.ExtractionExceptions;
import com.careeros.domain.ExtractedFact;
import com.careeros.domain.RecruitmentExtractionProposal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultJobUpsertServiceTest {
    private static final UUID EVENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-14T13:00:00Z");
    private final Map<String, JpaModels.JobPostingEntity> stored = new HashMap<>();
    private RecruitmentEventJpaRepository eventRepo;
    private OrganizationJpaRepository organizationRepo;
    private DefaultJobUpsertService service;

    @BeforeEach
    void setUp() {
        JobPostingJpaRepository jobs = mock(JobPostingJpaRepository.class);
        when(jobs.findByStableJobKey(any())).thenAnswer(invocation ->
            Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(jobs.findByRecruitmentEventId(EVENT_ID)).thenAnswer(invocation -> new ArrayList<>(stored.values()));
        when(jobs.save(any())).thenAnswer(invocation -> {
            JpaModels.JobPostingEntity entity = invocation.getArgument(0);
            stored.put(entity.stableJobKey, entity);
            return entity;
        });
        eventRepo = mock(RecruitmentEventJpaRepository.class);
        organizationRepo = mock(OrganizationJpaRepository.class);
        when(eventRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizationRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new DefaultJobUpsertService(
            jobs, eventRepo, organizationRepo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void stableKeyIsSharedByExcelAndExtractionRepresentations() {
        NormalizedJob excel = job(2);
        NormalizedJob extraction = new NormalizedJob(
            excel.recruitmentEventId(), excel.organizationId(), " 杭州市测试信息中心 ",
            " A-01 ", excel.title(), excel.jobFamily(), excel.employmentType(), excel.location(),
            excel.headcount(), excel.minimumEducation(), excel.exactMajors(),
            excel.acceptedGraduationYears(), excel.maximumAge(), excel.ageReferenceDate(),
            excel.minimumExperienceYears(), excel.requiredProfessionalTitles(), excel.duties(),
            excel.sourceUrl(), excel.evidenceIds());

        String expected = sha256(excel.sourceUrl() + "|杭州市测试信息中心|a01");
        assertThat(service.stableKey(excel)).isEqualTo(expected);
        assertThat(service.stableKey(extraction)).isEqualTo(expected);
        assertThat(service.contentFingerprint(extraction)).isEqualTo(service.contentFingerprint(excel));
    }

    @Test
    void evidenceAndOrganizationChangesInvalidateTheContentFingerprint() {
        NormalizedJob original = job(2);
        NormalizedJob evidenceChanged = copyWithAssociations(
            original, original.organizationId(), List.of(UUID.randomUUID()));
        NormalizedJob organizationChanged = copyWithAssociations(
            original, UUID.randomUUID(), original.evidenceIds());

        assertThat(service.contentFingerprint(evidenceChanged))
            .isNotEqualTo(service.contentFingerprint(original));
        assertThat(service.contentFingerprint(organizationChanged))
            .isNotEqualTo(service.contentFingerprint(original));
    }

    @Test
    void collectionBoundariesAndUrlPathCaseCannotCollideInTheContentFingerprint() {
        NormalizedJob original = job(2);
        NormalizedJob separateMajors = copyWithContent(
            original, Set.of("计算机科学与技术", "软件工程"), original.sourceUrl());
        NormalizedJob commaJoinedMajor = copyWithContent(
            original, Set.of("计算机科学与技术,软件工程"), original.sourceUrl());
        NormalizedJob upperCaseUrlPath = copyWithContent(
            original, original.exactMajors(), "https://example.gov.cn/Notices/Job?Token=AbC");
        NormalizedJob lowerCaseUrlPath = copyWithContent(
            original, original.exactMajors(), "https://example.gov.cn/notices/job?Token=abc");

        assertThat(service.contentFingerprint(separateMajors))
            .isNotEqualTo(service.contentFingerprint(commaJoinedMajor));
        assertThat(service.contentFingerprint(upperCaseUrlPath))
            .isNotEqualTo(service.contentFingerprint(lowerCaseUrlPath));
    }

    @Test
    void repeatIsUnchangedChangedContentUpdatesAndOnlyCleanCompleteSnapshotDeactivates() {
        var first = service.upsert(batch(List.of(job(2)), true, List.of()));
        assertThat(first.inserted()).isEqualTo(1);

        var repeat = service.upsert(batch(List.of(job(2)), true, List.of()));
        assertThat(repeat.unchanged()).isEqualTo(1);

        var changed = service.upsert(batch(List.of(job(3)), true, List.of()));
        assertThat(changed.updated()).isEqualTo(1);

        assertThat(service.upsert(batch(List.of(), false, List.of())).deactivated()).isZero();
        assertThat(service.upsert(batch(List.of(), true, List.of("第 3 行解析失败"))).deactivated()).isZero();
        assertThat(service.upsert(batch(List.of(), true, List.of())).deactivated()).isEqualTo(1);
        assertThat(stored.values()).allMatch(entity -> !entity.active);
    }

    @Test
    void verifiedAgentProposalUsesTheSameIncrementalPipeline() {
        var organization = new JpaModels.OrganizationEntity();
        organization.id = ORGANIZATION_ID;
        organization.name = "杭州市测试信息中心";
        organization.organizationType = OrganizationType.PUBLIC_INSTITUTION;
        var event = new JpaModels.RecruitmentEventEntity();
        event.id = EVENT_ID;
        event.sourceUrl = "https://example.gov.cn/notices/2026-1";
        when(organizationRepo.findFirstByName(organization.name)).thenReturn(Optional.of(organization));
        when(eventRepo.findFirstBySourceUrl(event.sourceUrl)).thenReturn(Optional.of(event));

        var first = service.write(proposal(), job(2).evidenceIds());
        var repeat = service.write(proposal(), job(2).evidenceIds());

        assertThat(first.inserted()).isEqualTo(1);
        assertThat(repeat.unchanged()).isEqualTo(1);
        assertThat(stored).containsKey(service.stableKey(job(2)));
    }

    @Test
    void interpretedRestrictiveFactCannotEnterVerifiedJobStorage() {
        assertThatThrownBy(() -> service.write(
            proposalWithInterpretedEmployment(), job(2).evidenceIds()))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class)
            .hasMessageContaining("employmentType");
        assertThat(stored).isEmpty();
    }

    @Test
    void verifiedAgentProposalCannotInventMissingHeadcount() {
        stubExistingOrganizationAndEvent();

        assertThatThrownBy(() -> service.write(
            proposalWithUnknownHeadcount(), job(2).evidenceIds()))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class)
            .hasMessageContaining("headcount");
        assertThat(stored).isEmpty();
    }

    @Test
    void agentCompleteSnapshotFlagCannotDeactivateExistingJobs() {
        stubExistingOrganizationAndEvent();
        service.upsert(batch(List.of(job(2)), true, List.of()));

        var result = service.write(proposalWithoutJobs(), job(2).evidenceIds());

        assertThat(result.deactivated()).isZero();
        assertThat(stored.values()).allMatch(entity -> entity.active);
    }

    @Test
    void verifiedRepeatRefreshesEventFieldsAndAppendsEvidence() {
        RecruitmentExtractionProposal proposal = proposal();
        var organization = new JpaModels.OrganizationEntity();
        organization.id = ORGANIZATION_ID;
        organization.name = proposal.organization().name();
        organization.organizationType = OrganizationType.PUBLIC_INSTITUTION;
        var event = new JpaModels.RecruitmentEventEntity();
        event.id = EVENT_ID;
        event.title = "旧公告标题";
        event.recruitmentYear = 2025;
        event.eventType = EventType.OTHER;
        event.sourceUrl = proposal.source().sourceUrl();
        UUID oldEvidence = UUID.randomUUID();
        event.evidenceIds = new ArrayList<>(List.of(oldEvidence));
        when(organizationRepo.findFirstByName(organization.name)).thenReturn(Optional.of(organization));
        when(eventRepo.findFirstBySourceUrl(event.sourceUrl)).thenReturn(Optional.of(event));
        when(eventRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.write(proposal, List.of(proposal.source().evidenceId()));

        assertThat(event.title).isEqualTo(proposal.recruitmentEvent().title());
        assertThat(event.recruitmentYear).isEqualTo(proposal.recruitmentEvent().recruitmentYear());
        assertThat(event.eventType).isEqualTo(proposal.recruitmentEvent().eventType());
        assertThat(event.applicationEndsOn)
            .isEqualTo(proposal.recruitmentEvent().applicationEndsOn().value());
        assertThat(event.evidenceIds)
            .containsExactly(oldEvidence, proposal.source().evidenceId());
    }

    private static JobUpsertBatch batch(
        List<NormalizedJob> jobs,
        boolean completeSnapshot,
        List<String> errors
    ) {
        return new JobUpsertBatch(
            EVENT_ID, "https://example.gov.cn/notices/2026-1", jobs, completeSnapshot, errors);
    }

    private static NormalizedJob job(int headcount) {
        return new NormalizedJob(
            EVENT_ID, ORGANIZATION_ID, "杭州市测试信息中心", "A01", "信息中心技术岗",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, "浙江杭州", headcount,
            EducationLevel.BACHELOR, new LinkedHashSet<>(Set.of("计算机科学与技术")),
            new LinkedHashSet<>(Set.of(2026)), 35, LocalDate.of(2026, 8, 20), 2,
            new LinkedHashSet<>(), "系统建设与运维", "https://example.gov.cn/notices/2026-1",
            List.of(UUID.fromString("30000000-0000-0000-0000-000000000003")));
    }

    private static NormalizedJob copyWithAssociations(
        NormalizedJob job, UUID organizationId, List<UUID> evidenceIds
    ) {
        return new NormalizedJob(
            job.recruitmentEventId(), organizationId, job.organizationName(), job.externalJobCode(),
            job.title(), job.jobFamily(), job.employmentType(), job.location(), job.headcount(),
            job.minimumEducation(), job.exactMajors(), job.acceptedGraduationYears(),
            job.maximumAge(), job.ageReferenceDate(), job.minimumExperienceYears(),
            job.requiredProfessionalTitles(), job.duties(), job.sourceUrl(), evidenceIds);
    }

    private static NormalizedJob copyWithContent(
        NormalizedJob job, Set<String> exactMajors, String sourceUrl
    ) {
        return new NormalizedJob(
            job.recruitmentEventId(), job.organizationId(), job.organizationName(),
            job.externalJobCode(), job.title(), job.jobFamily(), job.employmentType(),
            job.location(), job.headcount(), job.minimumEducation(), exactMajors,
            job.acceptedGraduationYears(), job.maximumAge(), job.ageReferenceDate(),
            job.minimumExperienceYears(), job.requiredProfessionalTitles(), job.duties(),
            sourceUrl, job.evidenceIds());
    }

    private static RecruitmentExtractionProposal proposal() {
        UUID fragmentId = UUID.fromString("30000000-0000-0000-0000-000000000004");
        var source = new RecruitmentExtractionProposal.SourceProposal(
            job(2).evidenceIds().getFirst(), "https://example.gov.cn/notices/2026-1", "公开招聘公告");
        var organization = new RecruitmentExtractionProposal.OrganizationProposal(
            "杭州市测试信息中心", explicit(OrganizationType.PUBLIC_INSTITUTION, fragmentId));
        var event = new RecruitmentExtractionProposal.EventProposal(
            "2026年公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            unknown(), unknown(), explicit(LocalDate.of(2026, 8, 20), fragmentId));
        var job = new RecruitmentExtractionProposal.JobProposal(
            explicit("信息中心技术岗", fragmentId), "A01", explicit(2, fragmentId),
            explicit(EmploymentType.ESTABLISHMENT, fragmentId), "浙江杭州",
            explicit(EducationLevel.BACHELOR, fragmentId), unknown(),
            explicit("计算机科学与技术", fragmentId), explicit(35, fragmentId),
            explicit(Set.of(2026), fragmentId), explicit(2, fragmentId),
            JobFamily.INFORMATION_SYSTEMS, "系统建设与运维");
        return new RecruitmentExtractionProposal(
            RecruitmentExtractionProposal.SCHEMA_VERSION, source, organization, event,
            List.of(job), List.of(), 0.98, true);
    }

    private static RecruitmentExtractionProposal proposalWithInterpretedEmployment() {
        RecruitmentExtractionProposal proposal = proposal();
        var original = proposal.jobs().getFirst();
        var interpreted = new ExtractedFact<>(
            EmploymentType.ESTABLISHMENT, FactStatus.INTERPRETED, 0.80,
            original.employmentType().evidenceFragmentIds(), "根据上下文推断");
        var changed = new RecruitmentExtractionProposal.JobProposal(
            original.title(), original.externalJobCode(), original.headcount(), interpreted,
            original.location(), original.minimumEducation(), original.degree(), original.majorText(),
            original.maximumAge(), original.acceptedGraduationYears(), original.minimumExperienceYears(),
            original.jobFamily(), original.duties());
        return new RecruitmentExtractionProposal(
            proposal.schemaVersion(), proposal.source(), proposal.organization(), proposal.recruitmentEvent(),
            List.of(changed), proposal.warnings(), proposal.confidence(), proposal.completeSnapshot());
    }

    private RecruitmentExtractionProposal proposalWithUnknownHeadcount() {
        RecruitmentExtractionProposal proposal = proposal();
        var original = proposal.jobs().getFirst();
        var changed = new RecruitmentExtractionProposal.JobProposal(
            original.title(), original.externalJobCode(), unknown(), original.employmentType(),
            original.location(), original.minimumEducation(), original.degree(), original.majorText(),
            original.maximumAge(), original.acceptedGraduationYears(), original.minimumExperienceYears(),
            original.jobFamily(), original.duties());
        return new RecruitmentExtractionProposal(
            proposal.schemaVersion(), proposal.source(), proposal.organization(), proposal.recruitmentEvent(),
            List.of(changed), proposal.warnings(), proposal.confidence(), true);
    }

    private RecruitmentExtractionProposal proposalWithoutJobs() {
        RecruitmentExtractionProposal proposal = proposal();
        return new RecruitmentExtractionProposal(
            proposal.schemaVersion(), proposal.source(), proposal.organization(), proposal.recruitmentEvent(),
            List.of(), proposal.warnings(), proposal.confidence(), true);
    }

    private void stubExistingOrganizationAndEvent() {
        RecruitmentExtractionProposal proposal = proposal();
        var organization = new JpaModels.OrganizationEntity();
        organization.id = ORGANIZATION_ID;
        organization.name = proposal.organization().name();
        organization.organizationType = OrganizationType.PUBLIC_INSTITUTION;
        var event = new JpaModels.RecruitmentEventEntity();
        event.id = EVENT_ID;
        event.sourceUrl = proposal.source().sourceUrl();
        when(organizationRepo.findFirstByName(organization.name)).thenReturn(Optional.of(organization));
        when(eventRepo.findFirstBySourceUrl(event.sourceUrl)).thenReturn(Optional.of(event));
    }

    private static <T> ExtractedFact<T> explicit(T value, UUID fragmentId) {
        return new ExtractedFact<>(value, FactStatus.EXPLICIT, 0.98, List.of(fragmentId), null);
    }

    private static <T> ExtractedFact<T> unknown() {
        return new ExtractedFact<>(null, FactStatus.UNKNOWN, 0, List.of(), null);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
