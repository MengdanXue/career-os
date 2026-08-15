package com.careeros;

import static org.mockito.Mockito.*;

import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.application.AcquisitionService;
import com.careeros.domain.acquisition.RecruitmentSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AcquisitionSchedulerTest {
    @Test
    void dispatchesEveryDueSourceOnce() {
        AcquisitionStore store = mock(AcquisitionStore.class);
        AcquisitionService service = mock(AcquisitionService.class);
        RecruitmentSource first = mock(RecruitmentSource.class);
        RecruitmentSource second = mock(RecruitmentSource.class);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(first.id()).thenReturn(firstId);
        when(second.id()).thenReturn(secondId);
        when(store.findDueSources(Instant.parse("2026-08-15T00:00:00Z"), 20))
            .thenReturn(List.of(first, second));
        var scheduler = new AcquisitionScheduler(store, service,
            Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC), 20);

        scheduler.dispatchDueSources();

        verify(service).run(firstId, com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger.SCHEDULED);
        verify(service).run(secondId, com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger.SCHEDULED);
    }
}
