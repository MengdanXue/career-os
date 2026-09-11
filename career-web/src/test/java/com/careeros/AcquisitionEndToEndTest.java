package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careeros.application.AcquiredDocumentProcessor;
import com.careeros.application.AcquisitionHttpPorts.AttachmentDiscoverer;
import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.DocumentFetcher;
import com.careeros.application.AcquisitionHttpPorts.FetchedDocument;
import com.careeros.application.AcquisitionHttpPorts.FetchFailedException;
import com.careeros.application.AcquisitionHttpPorts.SourceDiscoverer;
import com.careeros.application.AcquisitionPorts.NextRunCalculator;
import com.careeros.application.AcquisitionPorts.SourceRunLock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "career-os.acquisition.scheduling-enabled=false")
@AutoConfigureMockMvc
@Import({AcquisitionEndToEndTest.AcquisitionTestConfiguration.class, TestSecurityDefaults.class})
@Testcontainers(disabledWithoutDocker = true)
class AcquisitionEndToEndTest {
    private static final URI DETAIL = URI.create("https://rlsbt.zj.gov.cn/art/2026/8/15/art_1229743683_700001.html");
    private static final URI ATTACHMENT = URI.create("https://rlsbt.zj.gov.cn/module/download/downfile.jsp?filename=jobs.xlsx");
    private static final URI FAILING_DETAIL = URI.create("https://rlsbt.zj.gov.cn/art/2026/8/15/art_1229743683_700002.html");
    private static final Path ARTIFACTS = tempArtifacts();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os")
        .withUsername("career_os")
        .withPassword("career_os");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("career-os.artifacts.root", ARTIFACTS::toString);
        registry.add("career-os.acquisition.dispatch-delay-ms", () -> "3600000");
        registry.add("career-os.extraction.llm.enabled", () -> "false");
        registry.add("spring.ai.model.chat", () -> "none");
    }

    @Test
    void repeatedApiRunsPublishOnlyAddedThenUpdatedChanges(
        @Autowired MockMvc mvc,
        @Autowired ObjectMapper json,
        @Autowired MutableDocumentFetcher fetcher,
        @Autowired MutableSourceDiscoverer discoverer,
        @Autowired MutableClock clock,
        @Autowired JdbcTemplate jdbc
    ) throws Exception {
        JsonNode sources = response(mvc.perform(get("/api/acquisition/sources")), json, 200);
        JsonNode source = findSource(sources, "ZJ_HRSS_INSTITUTION");
        String sourceId = source.path("id").asText();

        JsonNode first = response(mvc.perform(post("/api/acquisition/sources/{sourceId}/runs", sourceId)), json, 202);
        JsonNode second = response(mvc.perform(post("/api/acquisition/sources/{sourceId}/runs", sourceId)), json, 202);

        assertThat(first.path("addedCount").asInt()).as(first.toPrettyString()).isEqualTo(2);
        assertThat(second.path("unchangedCount").asInt()).isEqualTo(2);
        assertThat(response(mvc.perform(get("/api/acquisition/changes").param("sourceId", sourceId)), json, 200)
            .path("items")).hasSize(2);

        fetcher.content = "<html><body>2026 年事业单位招聘公告（修订版）</body></html>"
            .getBytes(StandardCharsets.UTF_8);
        JsonNode third = response(mvc.perform(post("/api/acquisition/sources/{sourceId}/runs", sourceId)), json, 202);
        JsonNode changes = response(mvc.perform(get("/api/acquisition/changes")
            .param("sourceId", sourceId).param("size", "50")), json, 200);

        assertThat(third.path("updatedCount").asInt()).isEqualTo(1);
        assertThat(changes.path("items")).hasSize(3);
        assertThat(changes.path("items").findValuesAsText("changeType"))
            .containsExactlyInAnyOrder("ADDED", "ADDED", "UPDATED");

        fetcher.gone = true;
        JsonNode firstGone = response(mvc.perform(post("/api/acquisition/sources/{sourceId}/runs", sourceId)), json, 202);
        clock.advance(Duration.ofHours(6));
        JsonNode secondGone = response(mvc.perform(post("/api/acquisition/sources/{sourceId}/runs", sourceId)), json, 202);

        assertThat(firstGone.path("deactivatedCount").asInt()).isZero();
        assertThat(secondGone.path("deactivatedCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from acquired_document where source_id = ?",
            Long.class, UUID.fromString(sourceId))).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from acquisition_change where source_id = ?",
            Long.class, UUID.fromString(sourceId))).isEqualTo(4L);

        JsonNode hzSource = findSource(sources, "HZ_HRSS_INSTITUTION");
        fetcher.gone = false;
        discoverer.includeFailingDetail = true;
        JsonNode partial = response(mvc.perform(post("/api/acquisition/sources/{sourceId}/runs",
            hzSource.path("id").asText())), json, 202);
        assertThat(partial.path("status").asText()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(partial.path("failedCount").asInt()).isEqualTo(1);
    }

    private static JsonNode response(
        org.springframework.test.web.servlet.ResultActions action, ObjectMapper json, int status
    ) throws Exception {
        var result = action.andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private static JsonNode findSource(JsonNode sources, String code) {
        for (JsonNode source : sources) if (code.equals(source.path("code").asText())) return source;
        throw new AssertionError("Missing seeded source " + code);
    }

    private static Path tempArtifacts() {
        try { return Files.createTempDirectory("career-os-acquisition-e2e-"); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @TestConfiguration
    static class AcquisitionTestConfiguration {
        @Bean @Primary MutableClock fixedAcquisitionClock() {
            return new MutableClock(Instant.parse("2026-08-15T08:00:00Z"));
        }

        @Bean @Primary MutableSourceDiscoverer fixedSourceDiscoverer() {
            return new MutableSourceDiscoverer();
        }

        @Bean @Primary AttachmentDiscoverer noAttachments() {
            return (source, pageUri, html) -> List.of(new DiscoveredLink(ATTACHMENT, "招聘岗位计划表.xlsx"));
        }

        @Bean @Primary MutableDocumentFetcher mutableDocumentFetcher() {
            return new MutableDocumentFetcher();
        }

        @Bean @Primary AcquiredDocumentProcessor successfulProcessor() {
            return command -> AcquiredDocumentProcessor.ProcessingResult.extracted(
                UUID.nameUUIDFromBytes(command.content()));
        }

        @Bean @Primary SourceRunLock inProcessSourceLock() {
            return (sourceCode, wait, work) -> Optional.of(work.get());
        }

        @Bean @Primary NextRunCalculator fixedNextRun() {
            return (source, after) -> after.plus(Duration.ofDays(1));
        }
    }

    static final class MutableDocumentFetcher implements DocumentFetcher {
        volatile byte[] content = "<html><body>2026 年事业单位招聘公告</body></html>"
            .getBytes(StandardCharsets.UTF_8);
        volatile boolean gone;

        @Override
        public FetchedDocument fetch(com.careeros.application.AcquisitionHttpPorts.FetchRequest request) {
            if (FAILING_DETAIL.equals(request.uri())) throw new FetchFailedException("fixture failure");
            if (DETAIL.equals(request.uri())) {
                if (gone) return new FetchedDocument(DETAIL, 410, null, new byte[0], null, null);
                return new FetchedDocument(DETAIL, 200, "text/html", content, null, null);
            }
            if (ATTACHMENT.equals(request.uri())) {
                return new FetchedDocument(ATTACHMENT, 200,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    new byte[] {0x50, 0x4b, 0x03, 0x04, 1, 2, 3}, null, null);
            }
            return new FetchedDocument(request.uri(), 200, "text/html",
                ("<html><body>count=\"1\"<ul><li><a href=\"" + DETAIL
                    + "\">2026 年事业单位招聘公告</a></li></ul></body></html>")
                    .getBytes(StandardCharsets.UTF_8), null, null);
        }
    }

    static final class MutableSourceDiscoverer implements SourceDiscoverer {
        volatile boolean includeFailingDetail;
        @Override public List<DiscoveredLink> discover(
            com.careeros.domain.acquisition.RecruitmentSource source, URI pageUri, byte[] html
        ) {
            var first = new DiscoveredLink(DETAIL, "2026 年事业单位招聘公告");
            return includeFailingDetail
                ? List.of(first, new DiscoveredLink(FAILING_DETAIL, "2026 年失败夹具"))
                : List.of(first);
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
