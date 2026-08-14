package com.careeros.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GoldenDatasetTest {
    @Test void goldenDatasetContainsThirtyUniqueTraceableJobs() throws Exception {
        try (var stream = getClass().getResourceAsStream("/golden/golden-jobs.json")) {
            assertThat(stream).isNotNull();
            JsonNode root = new ObjectMapper().readTree(stream);
            JsonNode jobs = root.path("jobs");
            assertThat(jobs.isArray()).isTrue();
            assertThat(jobs.size()).isBetween(30, 50);
            var ids = new HashSet<String>();
            for (JsonNode job : jobs) {
                assertThat(job.path("id").asText()).startsWith("job_");
                assertThat(job.path("sample").asText()).startsWith("S");
                assertThat(job.path("employer").asText()).isNotBlank();
                assertThat(job.path("title").asText()).isNotBlank();
                assertThat(job.path("expected").path("itRelated").isBoolean()).isTrue();
                assertThat(ids.add(job.path("id").asText())).isTrue();
            }
        }
    }
}
