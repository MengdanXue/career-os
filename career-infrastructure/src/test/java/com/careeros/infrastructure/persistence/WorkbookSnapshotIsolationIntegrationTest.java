package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.JobUpsertService;
import com.careeros.application.JobUpsertService.JobUpsertBatch;
import com.careeros.application.JobUpsertService.NormalizedJob;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = WorkbookSnapshotIsolationIntegrationTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class WorkbookSnapshotIsolationIntegrationTest {
    private static final String ANNOUNCEMENT = "https://example.gov.cn/notices/2026-one";
    private static final String FIRST_WORKBOOK = "https://example.gov.cn/files/plan-a.xlsx";
    private static final String SECOND_WORKBOOK = "https://example.gov.cn/files/plan-b.xlsx";
    private static final String LEGACY_ADOPTION_WORKBOOK = "https://example.gov.cn/files/legacy-adoption.xlsx";
    private static final String EMPTY_LEGACY_ANNOUNCEMENT = "https://example.gov.cn/notices/legacy-empty";
    private static final String EMPTY_LEGACY_WORKBOOK = "https://example.gov.cn/files/legacy-empty.xlsx";
    private static final String AMBIGUOUS_ANNOUNCEMENT = "https://example.gov.cn/notices/legacy-ambiguous";
    private static final String AMBIGUOUS_WORKBOOK = "https://example.gov.cn/files/legacy-ambiguous-a.xlsx";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os")
        .withUsername("career_os")
        .withPassword("career_os");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    void twoAttachmentsWithTheSameOrganizationAndCodeOwnIndependentActiveSnapshots(
        @Autowired JobUpsertService upserts,
        @Autowired OrganizationJpaRepository organizations,
        @Autowired RecruitmentEventJpaRepository events,
        @Autowired JobPostingJpaRepository jobs
    ) {
        var organization = new JpaModels.OrganizationEntity();
        organization.id = UUID.randomUUID();
        organization.name = "杭州市同代码测试单位";
        organization.organizationType = OrganizationType.PUBLIC_INSTITUTION;
        organizations.save(organization);

        var firstEvent = event("岗位计划 A", FIRST_WORKBOOK);
        var secondEvent = event("岗位计划 B", SECOND_WORKBOOK);
        events.save(firstEvent);
        events.save(secondEvent);

        var first = upserts.upsert(batch(
            firstEvent.id, job(firstEvent.id, organization, FIRST_WORKBOOK, "信息中心岗 A")));
        var second = upserts.upsert(batch(
            secondEvent.id, job(secondEvent.id, organization, SECOND_WORKBOOK, "信息中心岗 B")));

        assertThat(first.inserted()).isEqualTo(1);
        assertThat(second.inserted()).isEqualTo(1);
        assertThat(second.updated()).isZero();
        assertThat(second.deactivated()).isZero();
        assertThat(jobs.findByRecruitmentEventId(firstEvent.id)).singleElement().satisfies(job ->
            assertThat(job.active).isTrue());
        assertThat(jobs.findByRecruitmentEventId(secondEvent.id)).singleElement().satisfies(job ->
            assertThat(job.active).isTrue());

        var firstEmptySnapshot = upserts.upsert(new JobUpsertBatch(
            firstEvent.id, ANNOUNCEMENT, List.of(), true, List.of()));

        assertThat(firstEmptySnapshot.deactivated()).isEqualTo(1);
        assertThat(jobs.findByRecruitmentEventId(firstEvent.id)).singleElement().satisfies(job ->
            assertThat(job.active).isFalse());
        assertThat(jobs.findByRecruitmentEventId(secondEvent.id)).singleElement().satisfies(job ->
            assertThat(job.active).isTrue());
    }

    @Test
    void firstAttachmentImportAdoptsTheLegacyParentUrlKeyWithoutCreatingADuplicate(
        @Autowired JobUpsertService upserts,
        @Autowired OrganizationJpaRepository organizations,
        @Autowired RecruitmentEventJpaRepository events,
        @Autowired JobPostingJpaRepository jobs
    ) {
        var organization = new JpaModels.OrganizationEntity();
        organization.id = UUID.randomUUID();
        organization.name = "杭州市历史升级测试单位";
        organization.organizationType = OrganizationType.PUBLIC_INSTITUTION;
        organizations.save(organization);

        var legacyEvent = event("旧版公告级快照", ANNOUNCEMENT);
        var attachmentEvent = event("新版附件级快照", LEGACY_ADOPTION_WORKBOOK);
        events.save(legacyEvent);
        events.save(attachmentEvent);
        var legacyJob = new NormalizedJob(
            legacyEvent.id, organization.id, organization.name, "A01", "信息中心岗",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.UNKNOWN, "杭州", 1,
            EducationLevel.BACHELOR, Set.of("计算机科学与技术"), Set.of(), 35,
            LocalDate.of(2026, 8, 20), null, Set.of(), "信息系统建设与运维",
            ANNOUNCEMENT, List.of());
        var removedLegacyJob = new NormalizedJob(
            legacyEvent.id, organization.id, organization.name, "A02", "已从附件删除的岗位",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.UNKNOWN, "杭州", 1,
            EducationLevel.BACHELOR, Set.of("计算机科学与技术"), Set.of(), 35,
            LocalDate.of(2026, 8, 20), null, Set.of(), "旧版快照岗位",
            ANNOUNCEMENT, List.of());
        assertThat(upserts.upsert(new JobUpsertBatch(
            legacyEvent.id, ANNOUNCEMENT, List.of(legacyJob, removedLegacyJob), true, List.of())).inserted())
            .isEqualTo(2);

        var attachmentJob = new NormalizedJob(
            attachmentEvent.id, organization.id, organization.name, "A01", "信息中心岗",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.UNKNOWN, "杭州", 1,
            EducationLevel.BACHELOR, Set.of("计算机科学与技术"), Set.of(), 35,
            LocalDate.of(2026, 8, 20), null, Set.of(), "信息系统建设与运维",
            ANNOUNCEMENT, LEGACY_ADOPTION_WORKBOOK, ANNOUNCEMENT, List.of());
        var adopted = upserts.upsert(new JobUpsertBatch(
            attachmentEvent.id, ANNOUNCEMENT, List.of(attachmentJob), true, List.of(), legacyEvent.id));

        assertThat(adopted.inserted()).isZero();
        assertThat(adopted.updated()).isEqualTo(1);
        assertThat(adopted.deactivated()).isZero();
        assertThat(jobs.findAll().stream()
            .filter(job -> organization.id.equals(job.organizationId) && job.active))
            .hasSize(2);
        assertThat(jobs.findByRecruitmentEventId(legacyEvent.id)).singleElement().satisfies(job ->
            assertThat(job.active).isTrue());
        assertThat(jobs.findByRecruitmentEventId(attachmentEvent.id)).singleElement().satisfies(job -> {
            assertThat(job.active).isTrue();
            assertThat(job.stableJobKey).isEqualTo(upserts.stableKey(attachmentJob));
        });
    }

    @Test
    void emptyAttachmentSnapshotDoesNotGuessLegacyOwnershipWithoutAnAdoptedRow(
        @Autowired JobUpsertService upserts,
        @Autowired OrganizationJpaRepository organizations,
        @Autowired RecruitmentEventJpaRepository events,
        @Autowired JobPostingJpaRepository jobs
    ) {
        var organization = new JpaModels.OrganizationEntity();
        organization.id = UUID.randomUUID();
        organization.name = "杭州市空快照升级测试单位";
        organization.organizationType = OrganizationType.PUBLIC_INSTITUTION;
        organizations.save(organization);
        var legacyEvent = event("旧版空快照公告", EMPTY_LEGACY_ANNOUNCEMENT);
        var attachmentEvent = event("新版空附件", EMPTY_LEGACY_WORKBOOK);
        events.save(legacyEvent);
        events.save(attachmentEvent);
        var legacyJob = new NormalizedJob(
            legacyEvent.id, organization.id, organization.name, "E01", "旧岗位",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.UNKNOWN, "杭州", 1,
            EducationLevel.BACHELOR, Set.of(), Set.of(), null, null, null, Set.of(),
            "旧版岗位", EMPTY_LEGACY_ANNOUNCEMENT, List.of());
        upserts.upsert(new JobUpsertBatch(
            legacyEvent.id, EMPTY_LEGACY_ANNOUNCEMENT, List.of(legacyJob), true, List.of()));

        var empty = upserts.upsert(new JobUpsertBatch(
            attachmentEvent.id, EMPTY_LEGACY_ANNOUNCEMENT, List.of(), true, List.of(), legacyEvent.id));

        assertThat(empty.deactivated()).isZero();
        assertThat(jobs.findByRecruitmentEventId(legacyEvent.id)).singleElement().satisfies(job ->
            assertThat(job.active).isTrue());
        assertThat(jobs.findByRecruitmentEventId(attachmentEvent.id)).isEmpty();
    }

    @Test
    void sameCodeWithDifferentTitleDoesNotAdoptOrCleanAnAmbiguousLegacyAttachment(
        @Autowired JobUpsertService upserts,
        @Autowired OrganizationJpaRepository organizations,
        @Autowired RecruitmentEventJpaRepository events,
        @Autowired JobPostingJpaRepository jobs
    ) {
        var organization = new JpaModels.OrganizationEntity();
        organization.id = UUID.randomUUID();
        organization.name = "杭州市歧义升级测试单位";
        organization.organizationType = OrganizationType.PUBLIC_INSTITUTION;
        organizations.save(organization);
        var legacyEvent = event("旧版附件 B", AMBIGUOUS_ANNOUNCEMENT);
        var attachmentEvent = event("新版附件 A", AMBIGUOUS_WORKBOOK);
        events.save(legacyEvent);
        events.save(attachmentEvent);
        var legacyB = new NormalizedJob(
            legacyEvent.id, organization.id, organization.name, "A01", "财务管理岗",
            JobFamily.OTHER, EmploymentType.UNKNOWN, "杭州", 1, EducationLevel.BACHELOR,
            Set.of(), Set.of(), null, null, null, Set.of(), "附件 B 的历史岗位",
            AMBIGUOUS_ANNOUNCEMENT, List.of());
        upserts.upsert(new JobUpsertBatch(
            legacyEvent.id, AMBIGUOUS_ANNOUNCEMENT, List.of(legacyB), true, List.of()));
        var newA = new NormalizedJob(
            attachmentEvent.id, organization.id, organization.name, "A01", "信息中心岗",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.UNKNOWN, "杭州", 1,
            EducationLevel.BACHELOR, Set.of("计算机科学与技术"), Set.of(), null, null,
            null, Set.of(), "附件 A 的技术岗位", AMBIGUOUS_ANNOUNCEMENT,
            AMBIGUOUS_WORKBOOK, AMBIGUOUS_ANNOUNCEMENT, List.of());

        var result = upserts.upsert(new JobUpsertBatch(
            attachmentEvent.id, AMBIGUOUS_ANNOUNCEMENT, List.of(newA), true, List.of(), legacyEvent.id));

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.deactivated()).isZero();
        assertThat(jobs.findByRecruitmentEventId(legacyEvent.id)).singleElement().satisfies(job -> {
            assertThat(job.title).isEqualTo("财务管理岗");
            assertThat(job.active).isTrue();
        });
        assertThat(jobs.findByRecruitmentEventId(attachmentEvent.id)).singleElement().satisfies(job -> {
            assertThat(job.title).isEqualTo("信息中心岗");
            assertThat(job.active).isTrue();
        });
    }

    private static JpaModels.RecruitmentEventEntity event(String title, String workbookSourceUrl) {
        var event = new JpaModels.RecruitmentEventEntity();
        event.id = UUID.randomUUID();
        event.title = title;
        event.recruitmentYear = 2026;
        event.eventType = EventType.PUBLIC_INSTITUTION;
        event.publishedOn = LocalDate.of(2026, 8, 20);
        event.sourceUrl = workbookSourceUrl;
        event.defaultEmploymentType = EmploymentType.UNKNOWN;
        event.evidenceIds = new ArrayList<>();
        return event;
    }

    private static NormalizedJob job(
        UUID eventId,
        JpaModels.OrganizationEntity organization,
        String workbookSourceUrl,
        String title
    ) {
        return new NormalizedJob(
            eventId, organization.id, organization.name, "A01", title,
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.UNKNOWN, "杭州", 1,
            EducationLevel.BACHELOR, Set.of("计算机科学与技术"), Set.of(), 35,
            LocalDate.of(2026, 8, 20), null, Set.of(), "信息系统建设与运维",
            ANNOUNCEMENT, workbookSourceUrl, List.of());
    }

    private static JobUpsertBatch batch(UUID eventId, NormalizedJob job) {
        return new JobUpsertBatch(eventId, ANNOUNCEMENT, List.of(job), true, List.of());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.careeros.infrastructure.persistence")
    @EnableJpaRepositories(basePackages = "com.careeros.infrastructure.persistence")
    @Import({DefaultJobUpsertService.class, PostgresEventSourceLock.class})
    static class TestApplication {}
}
