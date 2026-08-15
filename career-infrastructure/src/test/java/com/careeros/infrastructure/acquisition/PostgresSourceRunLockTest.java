package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.acquisition.SourceCrawlRun;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresSourceRunLockTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os").withUsername("career_os").withPassword("career_os");

    @Test
    void aSecondInstanceSkipsWhileTheFirstSessionOwnsTheSourceLock() throws Exception {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(POSTGRES.getJdbcUrl());
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());
        try (var first = new PostgresSourceRunLock(properties, 1, 1000, 25);
             var second = new PostgresSourceRunLock(properties, 1, 1000, 25);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch acquired = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var owner = executor.submit(() -> first.tryExecute("ZJ_HRSS_INSTITUTION", Duration.ofSeconds(2), () -> {
                acquired.countDown();
                try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                return run();
            }));
            acquired.await();

            assertThat(second.tryExecute("ZJ_HRSS_INSTITUTION", Duration.ofMillis(150), PostgresSourceRunLockTest::run))
                .isEmpty();
            release.countDown();
            assertThat(owner.get()).isPresent();
            assertThat(second.tryExecute("ZJ_HRSS_INSTITUTION", Duration.ofSeconds(1), PostgresSourceRunLockTest::run))
                .isPresent();
        }
    }

    private static SourceCrawlRun run() {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        return SourceCrawlRun.running(UUID.randomUUID(), UUID.randomUUID(), RunTrigger.MANUAL, now);
    }
}
