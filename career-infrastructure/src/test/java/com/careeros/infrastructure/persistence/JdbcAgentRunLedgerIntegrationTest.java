package com.careeros.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.agent.AgentRunLedger.EvidenceRef;
import com.careeros.application.agent.AgentRunLedger.ModelTurnRecord;
import com.careeros.application.agent.AgentRunLedger.RunRecord;
import com.careeros.application.agent.AgentRunLedger.StepRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = JdbcAgentRunLedgerIntegrationTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class JdbcAgentRunLedgerIntegrationTest {
    /** 迁移里的种子候选人。 */
    private static final UUID CANDIDATE = UUID.fromString("01992f09-0000-7000-8000-000000000001");
    private static final Instant STARTED = Instant.parse("2026-09-24T08:00:00Z");

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

    /** 整份运行写进去、原样读回来：步骤顺序、参数、观测数据、证据引用、模型原文，一样都不能少。 */
    @Test
    void aRunIsStoredWholeAndReadBackUnchanged(@Autowired JdbcAgentRunLedger ledger) {
        var run = run(UUID.randomUUID(), STARTED);

        ledger.record(run);

        var read = ledger.find(CANDIDATE, run.runId()).orElseThrow();
        assertThat(read).isEqualTo(run);
        assertThat(read.evidence()).extracting(EvidenceRef::kind).containsExactly("OFFICIAL_SOURCE", "FIELD");
    }

    @Test
    void recentRunsAreNewestFirstWithStepAndEvidenceCounts(@Autowired JdbcAgentRunLedger ledger) {
        var older = run(UUID.randomUUID(), STARTED);
        var newer = run(UUID.randomUUID(), STARTED.plusSeconds(60));
        ledger.record(older);
        ledger.record(newer);

        var recent = ledger.recent(CANDIDATE, 10);

        assertThat(recent).extracting(summary -> summary.runId()).containsSubsequence(newer.runId(), older.runId());
        assertThat(recent.getFirst().stepCount()).isEqualTo(2);
        assertThat(recent.getFirst().evidenceCount()).isEqualTo(2);
        assertThat(recent.getFirst().plannerIdentity()).isEqualTo("model:deepseek-chat@api.deepseek.com");
    }

    /** 别的候选人拿同一个编号取不到这次运行。 */
    @Test
    void anotherCandidateCannotReadTheRun(@Autowired JdbcAgentRunLedger ledger) {
        var run = run(UUID.randomUUID(), STARTED);
        ledger.record(run);

        assertThat(ledger.find(UUID.randomUUID(), run.runId())).isEmpty();
    }

    private static RunRecord run(UUID runId, Instant startedAt) {
        var announcement = new EvidenceRef("OFFICIAL_SOURCE", "01992f09-0000-7000-8000-0000000000aa", null,
            "https://hrss.hangzhou.gov.cn/art/2026/notice.html", null, null, null);
        var field = new EvidenceRef("FIELD", "01992f09-0000-7000-8000-0000000000aa", "otherRequirements",
            "https://hrss.hangzhou.gov.cn/jobs.xlsx", "Sheet1!H12", "中共党员", "01992f09-0000-7000-8000-0000000000ff");
        var steps = List.of(
            new StepRecord(1, "update_profile", Map.of(), null, false, "未注册的工具", 0, 1, false,
                "没有这个工具，或者它不在只读工具面里。", Map.of(), List.of()),
            new StepRecord(2, "job_facts", Map.of("jobId", "01992f09-0000-7000-8000-0000000000aa"), "看限制条件",
                true, "ok", 1, 2, true, "信息中心工作人员 的结论已取到。",
                Map.of("jobPostingId", "01992f09-0000-7000-8000-0000000000aa", "restrictionCount", 1),
                List.of(announcement, field)));
        var turns = List.of(
            new ModelTurnRecord(1, "a".repeat(64), "用户问题：杭州的信息中心岗", "TOOL update_profile\nWHY 想改资料"),
            new ModelTurnRecord(2, "a".repeat(64), "用户问题：杭州的信息中心岗", "FINISH SINGLE_JOB\nBASIS 2"));
        return new RunRecord(runId, CANDIDATE, null, "杭州的信息中心岗怎么样", "model:deepseek-chat@api.deepseek.com",
            "FINISHED", "下面是这个岗位的情况；资格、限制条件和截止日见下方事实块。", null, List.of(), List.of(2),
            2, 50, startedAt, startedAt.plusSeconds(3), steps, turns);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.careeros.infrastructure.persistence")
    @EnableJpaRepositories(basePackages = "com.careeros.infrastructure.persistence")
    @Import(JdbcAgentRunLedger.class)
    static class TestApplication {}
}
