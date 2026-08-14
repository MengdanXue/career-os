package com.careeros;

import com.careeros.application.ExtractionPorts.ExtractionObserver;
import com.careeros.application.ExtractionPorts.ReviewPersistence;
import com.careeros.domain.DomainEnums.ReviewStatus;
import com.careeros.domain.ExtractionRun;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
final class ExtractionMetrics implements ExtractionObserver {
    private final MeterRegistry registry;
    private final ReviewPersistence reviews;

    ExtractionMetrics(MeterRegistry registry, ReviewPersistence reviews) {
        this.registry = registry;
        this.reviews = reviews;
        Gauge.builder("review_queue_size", this, ExtractionMetrics::pendingReviewCount)
            .description("Number of extraction proposals awaiting human review")
            .register(registry);
    }

    @Override
    public void completed(ExtractionRun run, boolean reused, Duration duration) {
        registry.counter(
            "extraction_runs",
            "source", run.sourceType().name(),
            "status", run.status().name()
        ).increment();
        if (reused) {
            registry.counter("extraction_reuse").increment();
        }
        Timer.builder("extraction_duration")
            .tag("parser", run.parserName())
            .register(registry)
            .record(duration);
    }

    @Override
    public void modelCall(String model, String result) {
        registry.counter("llm_calls", "model", model, "result", result).increment();
        if ("schema_failure".equals(result)) {
            registry.counter("llm_schema_failures").increment();
        }
    }

    private double pendingReviewCount() {
        try {
            return reviews.findPage(ReviewStatus.PENDING, 0, 1).totalElements();
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }
}
