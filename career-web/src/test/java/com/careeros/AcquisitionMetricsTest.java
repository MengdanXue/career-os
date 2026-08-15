package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.acquisition.SourceCrawlRun;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AcquisitionMetricsTest {
    @Test void recordsRunDocumentFetchFailureAndLockMetricsWithBoundedTags() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AcquisitionMetrics(registry);
        var run = new SourceCrawlRun(UUID.randomUUID(), UUID.randomUUID(), RunTrigger.SCHEDULED,
            RunStatus.PARTIALLY_SUCCEEDED, Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
            2, 1, 0, 1, 0, 0, 1, "DOCUMENT_FAILURE", "one failed");

        metrics.runCompleted("ZJ_HRSS_INSTITUTION", run);
        metrics.document("ZJ_HRSS_INSTITUTION", "ADDED");
        metrics.fetch("ZJ_HRSS_INSTITUTION", Duration.ofMillis(250));
        metrics.processingFailure("ZJ_HRSS_INSTITUTION", "application/pdf");
        metrics.lockSkipped("ZJ_HRSS_INSTITUTION");

        assertThat(registry.get("careeros.acquisition.runs")
            .tags("source", "ZJ_HRSS_INSTITUTION", "status", "PARTIALLY_SUCCEEDED").counter().count()).isEqualTo(1);
        assertThat(registry.get("careeros.acquisition.documents")
            .tags("source", "ZJ_HRSS_INSTITUTION", "result", "ADDED").counter().count()).isEqualTo(1);
        assertThat(registry.get("careeros.acquisition.fetch.duration")
            .tag("source", "ZJ_HRSS_INSTITUTION").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
            .isEqualTo(250);
        assertThat(registry.get("careeros.acquisition.processing.failures")
            .tags("source", "ZJ_HRSS_INSTITUTION", "media_type", "application/pdf").counter().count()).isEqualTo(1);
        assertThat(registry.get("careeros.acquisition.lock.skipped")
            .tag("source", "ZJ_HRSS_INSTITUTION").counter().count()).isEqualTo(1);
    }
}
