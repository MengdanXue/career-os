package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.application.AcquisitionPorts.ChangeCursor;
import com.careeros.domain.acquisition.AcquiredDocument;
import com.careeros.domain.acquisition.AcquiredDocument.DocumentKind;
import com.careeros.domain.acquisition.AcquiredDocument.DocumentState;
import com.careeros.domain.acquisition.AcquisitionChange;
import com.careeros.domain.acquisition.AcquisitionChange.ChangeType;
import com.careeros.domain.acquisition.ArtifactImportFailure;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint;
import com.careeros.domain.acquisition.SourceCrawlRun;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import com.careeros.domain.acquisition.SourceYearCoverage;
import com.careeros.domain.acquisition.SourceYearCoverage.CoverageStatus;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = JpaAcquisitionStoreTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class JpaAcquisitionStoreTest {
    private static final UUID SOURCE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000301");
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

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
    void seededSourcesAreEnabledAndDue(@Autowired AcquisitionStore store) {
        assertThat(store.findSources()).extracting(source -> source.code())
            .containsExactlyInAnyOrder("ZJ_HRSS_INSTITUTION", "HZ_HRSS_INSTITUTION",
                "HDU_RECRUITMENT", "ZJGSU_RECRUITMENT", "HZ_FIRST_HOSPITAL");
        assertThat(store.findDueSources(Instant.now().plusSeconds(60), 10)).hasSize(5);
    }

    @Test
    void readsSeededCoverageAndPersistsProgressWithoutClaimingFalseAbsence(@Autowired AcquisitionStore store) {
        assertThat(store.findSourceYearCoverage(SOURCE_ID, null))
            .extracting(SourceYearCoverage::recruitmentYear, SourceYearCoverage::status)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(2024, CoverageStatus.NOT_DISCOVERED),
                org.assertj.core.groups.Tuple.tuple(2025, CoverageStatus.NOT_DISCOVERED),
                org.assertj.core.groups.Tuple.tuple(2026, CoverageStatus.NOT_DISCOVERED));

        SourceYearCoverage failed = new SourceYearCoverage(SOURCE_ID, 2025, CoverageStatus.ACCESS_FAILED,
            12, 0, 0, 0, null, null, NOW,
            3, 9, 2, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "REMOTE_ACCESS_FAILED");

        assertThat(store.saveSourceYearCoverage(failed)).isEqualTo(failed);
        assertThat(store.findSourceYearCoverage(SOURCE_ID, 2025)).singleElement()
            .satisfies(value -> {
                assertThat(value.status()).isEqualTo(CoverageStatus.ACCESS_FAILED);
                assertThat(value.listingPageCount()).isEqualTo(3);
                assertThat(value.failedCount()).isEqualTo(2);
                assertThat(value.stopReason()).isEqualTo("REMOTE_ACCESS_FAILED");
                assertThat(value.supportsAbsenceConclusion()).isFalse();
            });
    }

    @Test
    void persistsOnboardingEvidenceAndRowLevelFailures(@Autowired AcquisitionStore store) {
        SourceCrawlRun run = store.saveRun(SourceCrawlRun.running(UUID.randomUUID(), SOURCE_ID, RunTrigger.MANUAL, NOW));
        AcquiredDocument document = store.saveDocument(document(UUID.randomUUID()));
        var checkpoint = new SourceOnboardingCheckpoint(SOURCE_ID,
            SourceOnboardingCheckpoint.Checkpoint.LIVE_SMOKE_VERIFIED,
            SourceOnboardingCheckpoint.CheckpointStatus.VERIFIED,
            "2026-08-24 官方列表页返回 200，识别 3 条公告", NOW);
        var failure = new ArtifactImportFailure(UUID.randomUUID(), run.id(), SOURCE_ID, document.id(),
            ArtifactImportFailure.FailureStage.ROW_PARSE_FAILED, "岗位计划", 17,
            "MISSING_ORGANIZATION", "招聘单位为空", NOW);

        assertThat(store.saveCheckpoint(checkpoint)).isEqualTo(checkpoint);
        assertThat(store.saveImportFailures(List.of(failure))).containsExactly(failure);
        assertThat(store.findCheckpoints(SOURCE_ID)).containsExactly(checkpoint);
        assertThat(store.findImportFailures(SOURCE_ID, run.id())).containsExactly(failure);
    }

    @Test
    void countsOnlyActiveTechnicalJobsBackedByThisSourcesAnnouncements(
        @Autowired AcquisitionStore store, @Autowired JdbcTemplate jdbc
    ) {
        AcquiredDocument announcement = store.saveDocument(document(UUID.randomUUID()));
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        jdbc.update("insert into recruitment_event(id,title,recruitment_year,event_type,source_url) values (?,?,?,?,?)",
            eventId, "2025年公开招聘", 2025, "PUBLIC_INSTITUTION", announcement.canonicalUri().toString());
        jdbc.update("insert into organization(id,name,organization_type) values (?,?,?)",
            organizationId, "测试单位", "PUBLIC_INSTITUTION");
        insertJob(jdbc, eventId, organizationId, announcement.canonicalUri().toString(), "SOFTWARE", true);
        insertJob(jdbc, eventId, organizationId, announcement.canonicalUri().toString(), "OTHER", true);
        insertJob(jdbc, eventId, organizationId, announcement.canonicalUri().toString(), "DATA", false);

        assertThat(store.countActiveTargetJobs(SOURCE_ID, 2025)).isEqualTo(1);
        assertThat(store.countActiveTargetJobs(SOURCE_ID, 2024)).isZero();
    }

    private static void insertJob(
        JdbcTemplate jdbc, UUID eventId, UUID organizationId, String sourceUrl,
        String family, boolean active
    ) {
        jdbc.update("""
            insert into job_posting(
                id,recruitment_event_id,organization_id,title,job_family,minimum_education,
                source_url,active
            ) values (?,?,?,?,?,?,?,?)
            """, UUID.randomUUID(), eventId, organizationId, family + "岗位", family,
            "BACHELOR", sourceUrl, active);
    }

    @Test
    void savesAndFindsDocumentByCanonicalUri(@Autowired AcquisitionStore store) {
        var document = document(UUID.fromString("01992f09-0000-7000-8000-000000000401"));

        AcquiredDocument saved = store.saveDocument(document);

        assertThat(store.findDocument(SOURCE_ID, document.canonicalUri())).contains(saved);
    }

    @Test
    void cursorUsesIdAsTieBreakerAndDuplicateNaturalChangeIsRejected(@Autowired AcquisitionStore store) {
        SourceCrawlRun run = store.saveRun(SourceCrawlRun.running(
            UUID.fromString("01992f09-0000-7000-8000-000000000501"), SOURCE_ID, RunTrigger.MANUAL, NOW));
        AcquiredDocument document = store.saveDocument(document(
            UUID.fromString("01992f09-0000-7000-8000-000000000401")));
        UUID firstId = UUID.fromString("01992f09-0000-7000-8000-000000000601");
        UUID secondId = UUID.fromString("01992f09-0000-7000-8000-000000000602");
        AcquisitionChange first = change(firstId, run, document, ChangeType.ADDED, "a".repeat(64));
        AcquisitionChange second = change(secondId, run, document, ChangeType.UPDATED, "b".repeat(64));
        store.appendChange(first);
        store.appendChange(second);

        var firstPage = store.findChanges(null, SOURCE_ID, Set.of(), 1);
        var secondPage = store.findChanges(firstPage.nextCursor(), SOURCE_ID, Set.of(), 1);

        assertThat(firstPage.items()).extracting(AcquisitionChange::id).containsExactly(firstId);
        assertThat(secondPage.items()).extracting(AcquisitionChange::id).containsExactly(secondId);
        assertThatThrownBy(() -> store.appendChange(new AcquisitionChange(
            UUID.randomUUID(), run.id(), SOURCE_ID, document.id(), ChangeType.UPDATED,
            "a".repeat(64), "b".repeat(64), document.canonicalUri(), Map.of(), NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cursorCanResumeAfterExactTimestampAndId(@Autowired AcquisitionStore store) {
        SourceCrawlRun run = store.saveRun(SourceCrawlRun.running(UUID.randomUUID(), SOURCE_ID, RunTrigger.MANUAL, NOW));
        AcquiredDocument document = store.saveDocument(document(UUID.randomUUID()));
        UUID before = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID after = UUID.fromString("00000000-0000-0000-0000-000000000002");
        store.appendChange(change(before, run, document, ChangeType.ADDED, "c".repeat(64)));
        store.appendChange(change(after, run, document, ChangeType.UPDATED, "d".repeat(64)));

        var page = store.findChanges(new ChangeCursor(NOW, before), SOURCE_ID, Set.of(), 10);

        assertThat(page.items()).extracting(AcquisitionChange::id).containsExactly(after);
    }

    private static AcquiredDocument document(UUID id) {
        return new AcquiredDocument(id, SOURCE_ID,
            URI.create("https://rlsbt.zj.gov.cn/art/2026/3/17/art_1229743683_58950000.html?doc=" + id),
            null, DocumentKind.ANNOUNCEMENT, "text/html", "a".repeat(64), "etag", null,
            URI.create("file:///artifacts/" + id), DocumentState.ACTIVE, NOW, NOW, NOW,
            null, 0, 200, "a".repeat(64), 0);
    }

    private static AcquisitionChange change(
        UUID id, SourceCrawlRun run, AcquiredDocument document, ChangeType type, String fingerprint
    ) {
        return new AcquisitionChange(id, run.id(), SOURCE_ID, document.id(), type,
            type == ChangeType.ADDED ? null : "a".repeat(64), fingerprint,
            document.canonicalUri(), Map.of("inserted", 1), NOW);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.careeros.infrastructure.acquisition")
    @EnableJpaRepositories(basePackages = "com.careeros.infrastructure.acquisition")
    @Import(JpaAcquisitionStore.class)
    static class TestApplication {}
}
