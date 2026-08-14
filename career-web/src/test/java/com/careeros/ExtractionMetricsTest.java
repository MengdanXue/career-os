package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.careeros.application.ExtractionPorts.ReviewPage;
import com.careeros.application.ExtractionPorts.ReviewPersistence;
import com.careeros.domain.DomainEnums.ReviewStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractionMetricsTest {
    @Test
    void observerPublishesRunsReuseModelSchemaTimerAndQueueMetrics() {
        var registry = new SimpleMeterRegistry();
        ReviewPersistence reviews = mock(ReviewPersistence.class);
        when(reviews.findPage(ReviewStatus.PENDING, 0, 1))
            .thenReturn(new ReviewPage(List.of(), 0, 1, 3));
        var metrics = new ExtractionMetrics(registry, reviews);

        metrics.completed(ApiTestFixtures.run(), true, Duration.ofMillis(250));
        metrics.modelCall("gpt-test", "schema_failure");

        assertThat(registry.get("extraction_runs")
            .tags("source", "HTML", "status", "REVIEW_REQUIRED").counter().count()).isEqualTo(1);
        assertThat(registry.get("extraction_reuse").counter().count()).isEqualTo(1);
        assertThat(registry.get("llm_calls")
            .tags("model", "gpt-test", "result", "schema_failure").counter().count()).isEqualTo(1);
        assertThat(registry.get("llm_schema_failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("extraction_duration").tag("parser", "jsoup").timer().count()).isEqualTo(1);
        assertThat(registry.get("review_queue_size").gauge().value()).isEqualTo(3);
    }
}
