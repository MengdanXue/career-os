package com.careeros;

import com.careeros.application.AcquisitionPorts.AcquisitionObserver;
import com.careeros.domain.acquisition.SourceCrawlRun;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
final class AcquisitionMetrics implements AcquisitionObserver {
    private final MeterRegistry registry;

    AcquisitionMetrics(MeterRegistry registry) { this.registry = registry; }

    @Override public void runCompleted(String sourceCode, SourceCrawlRun run) {
        registry.counter("careeros.acquisition.runs", "source", sourceCode, "status", run.status().name())
            .increment();
    }
    @Override public void document(String sourceCode, String result) {
        registry.counter("careeros.acquisition.documents", "source", sourceCode, "result", result).increment();
    }
    @Override public void fetch(String sourceCode, Duration duration) {
        Timer.builder("careeros.acquisition.fetch.duration").tag("source", sourceCode)
            .register(registry).record(duration);
    }
    @Override public void processingFailure(String sourceCode, String mediaType) {
        registry.counter("careeros.acquisition.processing.failures", "source", sourceCode,
            "media_type", mediaType == null ? "unknown" : mediaType).increment();
    }
    @Override public void lockSkipped(String sourceCode) {
        registry.counter("careeros.acquisition.lock.skipped", "source", sourceCode).increment();
    }
}
