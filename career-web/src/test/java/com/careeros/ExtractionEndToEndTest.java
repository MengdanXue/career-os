package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careeros.infrastructure.extraction.ExtractionRunJpaRepository;
import com.careeros.infrastructure.persistence.JobPostingJpaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "career-os.acquisition.scheduling-enabled=false")
@AutoConfigureMockMvc
@Import(TestSecurityDefaults.class)
@Testcontainers(disabledWithoutDocker = true)
class ExtractionEndToEndTest {
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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("career-os.extraction.llm.enabled", () -> "false");
        registry.add("spring.ai.model.chat", () -> "none");
    }

    @Test
    void realPdfGuideProducesEvidenceAndDoesNotCreateFormalJobs(
        @Autowired MockMvc mvc,
        @Autowired ObjectMapper json,
        @Autowired JdbcTemplate jdbc,
        @Autowired JobPostingJpaRepository jobs
    ) throws Exception {
        Path fixture = requiredTrackedFixture("08-zj-2025-applicant-guide.pdf",
            "59b0ccb3f374b2e60abf31532bed5c1c097c3db6bdaedc30cd01a6118513635b");
        long jobsBefore = jobs.count();

        JsonNode submitted = upload(
            mvc, json, fixture, "application/pdf",
            "https://rlsbt.zj.gov.cn/2025/applicant-guide", "2025年浙江省属事业单位报名指南");
        JsonNode extraction = lookup(mvc, json, submitted.path("id").asText());

        assertThat(extraction.path("status").asText())
            .isIn("NORMALIZED", "REVIEW_REQUIRED", "VERIFIED");
        assertThat(extraction.path("proposal").path("jobs").size()).isZero();
        assertThat(jobs.count()).isEqualTo(jobsBefore);
        Long fragmentCount = jdbc.queryForObject(
            "select count(*) from evidence_fragment where evidence_id = ?",
            Long.class,
            java.util.UUID.fromString(extraction.path("evidenceId").asText()));
        assertThat(fragmentCount).isNotNull().isGreaterThan(0);
    }

    @Test
    void repeatRealHtmlReusesTheSameExtractionRun(
        @Autowired MockMvc mvc,
        @Autowired ObjectMapper json,
        @Autowired ExtractionRunJpaRepository runs
    ) throws Exception {
        Path fixture = findWorkspaceFile("output/career-os-samples/raw/09-hz-capital-recruiting.html");
        Assumptions.assumeTrue(Files.exists(fixture), "local official HTML fixture is not available");
        long runsBefore = runs.count();

        JsonNode first = upload(
            mvc, json, fixture, "text/html",
            "https://www.hzcapital.com/recruiting", "杭州资本招聘公告");
        JsonNode second = upload(
            mvc, json, fixture, "text/html",
            "https://www.hzcapital.com/recruiting", "杭州资本招聘公告");

        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(first.path("reused").asBoolean()).isFalse();
        assertThat(second.path("reused").asBoolean()).isTrue();
        assertThat(runs.count()).isEqualTo(runsBefore + 1);
    }

    @Test
    void concurrentIdenticalOfficialHtmlCreatesOnePostgresRun(
        @Autowired MockMvc mvc,
        @Autowired ObjectMapper json,
        @Autowired ExtractionRunJpaRepository runs
    ) throws Exception {
        Path fixture = requiredTrackedFixture("10-hzfi-social-recruiting.html",
            "138dd654aaf75e4260c91f318e642c7ec0dbabcd51297596772fb093f147056e");
        long runsBefore = runs.count();
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return upload(mvc, json, fixture, "text/html",
                    "https://www.hzfi.com/social-recruiting", "杭州金投社会招聘");
            });
            var second = executor.submit(() -> {
                start.await();
                return upload(mvc, json, fixture, "text/html",
                    "https://www.hzfi.com/social-recruiting", "杭州金投社会招聘");
            });
            start.countDown();
            JsonNode one = first.get();
            JsonNode two = second.get();

            assertThat(two.path("id").asText()).isEqualTo(one.path("id").asText());
            assertThat(List.of(one.path("reused").asBoolean(), two.path("reused").asBoolean()))
                .containsExactlyInAnyOrder(false, true);
        }
        assertThat(runs.count()).isEqualTo(runsBefore + 1);
    }

    private JsonNode upload(
        MockMvc mvc,
        ObjectMapper json,
        Path fixture,
        String mediaType,
        String sourceUrl,
        String sourceTitle
    ) throws Exception {
        byte[] metadata = json.writeValueAsBytes(java.util.Map.of(
            "sourceUrl", sourceUrl,
            "sourceTitle", sourceTitle,
            "capturedAt", "2026-08-14T15:00:00Z",
            "requireModel", false));
        var result = mvc.perform(multipart("/api/v1/extractions")
                .file(new MockMultipartFile(
                    "document", fixture.getFileName().toString(), mediaType, Files.readAllBytes(fixture)))
                .file(new MockMultipartFile(
                    "metadata", "metadata.json", "application/json", metadata)))
            .andExpect(status().is2xxSuccessful())
            .andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode lookup(MockMvc mvc, ObjectMapper json, String id) throws Exception {
        var result = mvc.perform(get("/api/v1/extractions/{id}", id))
            .andExpect(status().isOk())
            .andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    /** Reuse preserved, hash-verified official samples already tracked in this repository. */
    private Path requiredTrackedFixture(String name, String expectedSha256) throws Exception {
        Path fixture = findWorkspaceFile("career-infrastructure/src/test/resources/fixtures/extraction/" + name);
        assertThat(Files.isRegularFile(fixture)).as("required tracked official fixture: %s", name).isTrue();
        String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(fixture)));
        assertThat(actual).as("preserved official fixture SHA-256: %s", name).isEqualTo(expectedSha256);
        return fixture;
    }

    private Path findWorkspaceFile(String relative) {
        Path current = Path.of("").toAbsolutePath();
        for (int level = 0; level < 5 && current != null; level++, current = current.getParent()) {
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        return Path.of(relative);
    }
}
