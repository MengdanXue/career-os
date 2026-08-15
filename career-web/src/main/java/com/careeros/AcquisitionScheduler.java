package com.careeros;

import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.application.AcquisitionService;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "career-os.acquisition.scheduling-enabled", havingValue = "true", matchIfMissing = true)
final class AcquisitionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AcquisitionScheduler.class);
    private final AcquisitionStore store;
    private final AcquisitionService service;
    private final Clock clock;
    private final int batchSize;

    AcquisitionScheduler(
        AcquisitionStore store,
        AcquisitionService service,
        Clock clock,
        @Value("${career-os.acquisition.dispatch-batch-size:20}") int batchSize
    ) {
        this.store=java.util.Objects.requireNonNull(store);
        this.service=java.util.Objects.requireNonNull(service);
        this.clock=java.util.Objects.requireNonNull(clock);
        if (batchSize < 1 || batchSize > 200) throw new IllegalArgumentException("batchSize must be between 1 and 200");
        this.batchSize=batchSize;
    }

    @Scheduled(fixedDelayString = "${career-os.acquisition.dispatch-delay-ms:60000}")
    void dispatchDueSources() {
        for (var source : store.findDueSources(clock.instant(), batchSize)) {
            try { service.run(source.id(), RunTrigger.SCHEDULED); }
            catch (RuntimeException failure) {
                LOG.error("Scheduled acquisition failed sourceCode={} sourceId={}",
                    source.code(), source.id(), failure);
            }
        }
    }
}
