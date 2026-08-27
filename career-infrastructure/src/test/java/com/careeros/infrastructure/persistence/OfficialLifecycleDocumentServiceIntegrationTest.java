package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.EmploymentType.UNKNOWN;
import static com.careeros.domain.DomainEnums.EventType.PUBLIC_INSTITUTION;
import static com.careeros.infrastructure.persistence.OfficialLifecycleDocumentService.MatchStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = OfficialLifecycleDocumentServiceIntegrationTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@Rollback
class OfficialLifecycleDocumentServiceIntegrationTest {
    private static final String CAMPAIGN =
        "杭州市西湖区2025年度部分事业单位公开招聘工作人员";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os").withUsername("career_os").withPassword("career_os");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    void uniquelyMatchedMultiStageNoticeUpdatesTheOriginalEventIdempotently(
        @Autowired OfficialLifecycleDocumentService service,
        @Autowired RecruitmentEventJpaRepository events,
        @Autowired JdbcTemplate jdbc
    ) {
        var event = event(events, CAMPAIGN + "公告", "https://www.hzxh.gov.cn/original");
        UUID evidenceId = evidence(jdbc, "https://www.hzxh.gov.cn/lifecycle/one");

        var first = service.recordIfLifecycle(
            CAMPAIGN + "体检、考察对象名单",
            "https://www.hzxh.gov.cn/lifecycle/one", 2025, LocalDate.of(2025, 7, 1), evidenceId).orElseThrow();
        var second = service.recordIfLifecycle(
            CAMPAIGN + "体检、考察对象名单",
            "https://www.hzxh.gov.cn/lifecycle/one", 2025, LocalDate.of(2025, 7, 1), evidenceId).orElseThrow();

        assertThat(first.status()).isEqualTo(MATCHED);
        assertThat(first.matchedEventId()).isEqualTo(event.id);
        assertThat(first.stageCount()).isEqualTo(2);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject(
            "select count(*) from recruitment_lifecycle_document where source_url=?",
            Integer.class, "https://www.hzxh.gov.cn/lifecycle/one")).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
            select physical_exam_state, investigation_state, physical_exam_rule, investigation_rule
            from recruitment_event where id=?
            """, event.id))
            .containsEntry("physical_exam_state", "CONFIRMED")
            .containsEntry("investigation_state", "CONFIRMED")
            .allSatisfy((key, value) -> {
                if (key.endsWith("_rule")) assertThat(value.toString()).contains("hzxh.gov.cn/lifecycle/one");
            });
    }

    @Test
    void retainsAnUnmatchedLifecycleNoticeWithoutUpdatingAnyEvent(
        @Autowired OfficialLifecycleDocumentService service,
        @Autowired JdbcTemplate jdbc
    ) {
        UUID evidenceId = evidence(jdbc, "https://www.hzxh.gov.cn/lifecycle/unmatched");

        var result = service.recordIfLifecycle(
            CAMPAIGN + "面试通知", "https://www.hzxh.gov.cn/lifecycle/unmatched",
            2025, LocalDate.of(2025, 6, 1), evidenceId).orElseThrow();

        assertThat(result.status()).isEqualTo(UNMATCHED);
        assertThat(result.matchedEventId()).isNull();
        assertThat(jdbc.queryForObject("""
            select matched_event_id from recruitment_lifecycle_document where source_url=?
            """, UUID.class, "https://www.hzxh.gov.cn/lifecycle/unmatched")).isNull();
    }

    @Test
    void refusesToChooseBetweenMultipleEqualCampaignCandidates(
        @Autowired OfficialLifecycleDocumentService service,
        @Autowired RecruitmentEventJpaRepository events,
        @Autowired JdbcTemplate jdbc
    ) {
        event(events, CAMPAIGN + "公告", "https://www.hzxh.gov.cn/original/a");
        event(events, CAMPAIGN + "通知", "https://www.hzxh.gov.cn/original/b");
        UUID evidenceId = evidence(jdbc, "https://www.hzxh.gov.cn/lifecycle/ambiguous");

        var result = service.recordIfLifecycle(
            CAMPAIGN + "资格复审通知", "https://www.hzxh.gov.cn/lifecycle/ambiguous",
            2025, LocalDate.of(2025, 5, 1), evidenceId).orElseThrow();

        assertThat(result.status()).isEqualTo(AMBIGUOUS);
        assertThat(result.matchedEventId()).isNull();
        assertThat(jdbc.queryForObject("""
            select count(*) from recruitment_event where qualification_review_state='CONFIRMED'
            """, Integer.class)).isZero();
    }

    @Test
    void prefersTheSingleHtmlNoticeWhenItsWorkbookCreatedTheSameCampaignEvent(
        @Autowired OfficialLifecycleDocumentService service,
        @Autowired RecruitmentEventJpaRepository events,
        @Autowired JdbcTemplate jdbc
    ) {
        var htmlEvent = event(events, CAMPAIGN + "公告",
            "https://www.hzxh.gov.cn/col/col1/art/2025/art_campaign.html");
        event(events, CAMPAIGN + "公告",
            "https://www.hzxh.gov.cn/api-gateway/front/document/download?fileName=jobs.xls");
        UUID evidenceId = evidence(jdbc, "https://www.hzxh.gov.cn/lifecycle/score");

        var result = service.recordIfLifecycle(
            CAMPAIGN + "综合成绩公示", "https://www.hzxh.gov.cn/lifecycle/score",
            2025, LocalDate.of(2025, 6, 15), evidenceId).orElseThrow();

        assertThat(result.status()).isEqualTo(MATCHED);
        assertThat(result.matchedEventId()).isEqualTo(htmlEvent.id);
    }

    @Test
    void ignoresAnInitialRecruitmentNotice(
        @Autowired OfficialLifecycleDocumentService service,
        @Autowired JdbcTemplate jdbc
    ) {
        UUID evidenceId = evidence(jdbc, "https://www.hzxh.gov.cn/original-only");

        assertThat(service.recordIfLifecycle(
            CAMPAIGN + "公告", "https://www.hzxh.gov.cn/original-only",
            2025, LocalDate.of(2025, 4, 1), evidenceId)).isEmpty();
        assertThat(jdbc.queryForObject(
            "select count(*) from recruitment_lifecycle_document", Integer.class)).isZero();
    }

    private static JpaModels.RecruitmentEventEntity event(
        RecruitmentEventJpaRepository events, String title, String sourceUrl
    ) {
        var event = new JpaModels.RecruitmentEventEntity();
        event.id = UUID.randomUUID();
        event.title = title;
        event.recruitmentYear = 2025;
        event.eventType = PUBLIC_INSTITUTION;
        event.sourceUrl = sourceUrl;
        event.defaultEmploymentType = UNKNOWN;
        event.evidenceIds = new ArrayList<>();
        return events.saveAndFlush(event);
    }

    private static UUID evidence(JdbcTemplate jdbc, String sourceUrl) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into evidence (id, evidence_type, source_url, source_title, content_hash, captured_at)
            values (?, 'OFFICIAL_NOTICE', ?, '生命周期公告', ?, ?)
            """, id, sourceUrl, "f".repeat(64), Timestamp.from(Instant.parse("2025-07-01T00:00:00Z")));
        return id;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.careeros.infrastructure.persistence")
    @EnableJpaRepositories(basePackages = "com.careeros.infrastructure.persistence")
    @Import(OfficialLifecycleDocumentService.class)
    static class TestApplication {
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2025-07-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
