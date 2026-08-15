package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.ExtractionPorts.VerifiedProposalWriter;
import com.careeros.domain.ExtractedFact;
import com.careeros.domain.RecruitmentExtractionProposal;
import com.careeros.infrastructure.extraction.PostgresFingerprintLock;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = EventSourceConcurrencyIntegrationTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class EventSourceConcurrencyIntegrationTest {
    private static final String SOURCE_URL = "https://example.gov.cn/notices/concurrent-event";

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    void concurrentDifferentContentsForOneSourceMergeIntoOneEvent(
        @Autowired VerifiedProposalWriter writer,
        @Autowired RecruitmentEventJpaRepository events
    ) throws Exception {
        UUID firstEvidence = UUID.randomUUID();
        UUID secondEvidence = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> writeAfterBarrier(
                writer, proposal(firstEvidence, "2026年公开招聘公告（初版）"), firstEvidence, ready, start));
            var second = executor.submit(() -> writeAfterBarrier(
                writer, proposal(secondEvidence, "2026年公开招聘公告（修订版）"), secondEvidence, ready, start));
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }

        var matching = events.findAll().stream()
            .filter(event -> SOURCE_URL.equals(event.sourceUrl))
            .toList();
        assertThat(matching).hasSize(1);
        assertThat(matching.getFirst().evidenceIds)
            .containsExactlyInAnyOrder(firstEvidence, secondEvidence);
    }

    @Test
    void independentLockInstancesKeepDistributedOwnershipAcrossFailureRollback(
        @Autowired DataSourceProperties dataSourceProperties,
        @Autowired PlatformTransactionManager transactionManager,
        @Autowired JdbcTemplate jdbc,
        @Autowired HikariDataSource dataSource
    ) throws Exception {
        jdbc.execute("create table if not exists fingerprint_lock_test (fingerprint varchar(64) primary key)");
        jdbc.update("delete from fingerprint_lock_test");
        String fingerprint = "a".repeat(64);
        AtomicInteger expensiveCalls = new AtomicInteger();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch allowFailure = new CountDownLatch(1);
        int originalMaximum = dataSource.getMaximumPoolSize();
        dataSource.setMaximumPoolSize(1);
        dataSource.getHikariPoolMXBean().softEvictConnections();

        try (var firstLock = new PostgresFingerprintLock(
                 dataSourceProperties, new TransactionTemplate(transactionManager));
             var secondLock = new PostgresFingerprintLock(
                 dataSourceProperties, new TransactionTemplate(transactionManager));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> firstLock.execute(
                fingerprint,
                () -> {
                    expensiveCalls.incrementAndGet();
                    firstInside.countDown();
                    await(allowFailure);
                    throw new IllegalStateException("expected processing failure");
                },
                failure -> jdbc.update(
                    "insert into fingerprint_lock_test (fingerprint) values (?)", fingerprint)));
            firstInside.await();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return secondLock.execute(fingerprint, () -> {
                    Integer claimed = jdbc.queryForObject(
                        "select count(*) from fingerprint_lock_test where fingerprint = ?",
                        Integer.class,
                        fingerprint);
                    if (claimed == null || claimed == 0) expensiveCalls.incrementAndGet();
                    return null;
                });
            });
            secondStarted.await();
            boolean secondIsWaitingOnDatabaseLock = waitForUnresolvedAdvisoryLock(dataSourceProperties);
            allowFailure.countDown();
            assertThat(secondIsWaitingOnDatabaseLock).isTrue();

            assertThatThrownBy(first::get)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("expected processing failure");
            second.get();
        } finally {
            dataSource.setMaximumPoolSize(originalMaximum);
        }

        assertThat(expensiveCalls).hasValue(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from fingerprint_lock_test", Integer.class)).isEqualTo(1);
    }

    @Test
    void boundedLockPoolLimitsConcurrentDistinctFingerprintSessions(
        @Autowired DataSourceProperties dataSourceProperties,
        @Autowired PlatformTransactionManager transactionManager,
        @Autowired HikariDataSource dataSource
    ) throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger thirdEntries = new AtomicInteger();

        int originalMaximum = dataSource.getMaximumPoolSize();
        dataSource.setMaximumPoolSize(3);
        dataSource.getHikariPoolMXBean().softEvictConnections();
        try (var lock = new PostgresFingerprintLock(
                 dataSourceProperties, new TransactionTemplate(transactionManager),
                 2, 500, 30_000);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> boundedOperation(
                lock, "1".repeat(64), entered, release));
            var second = executor.submit(() -> boundedOperation(
                lock, "2".repeat(64), entered, release));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var third = executor.submit(() -> boundedOperation(
                    lock, "3".repeat(64), thirdEntries));
                assertThatThrownBy(() -> third.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseInstanceOf(java.sql.SQLTransientConnectionException.class)
                    .hasStackTraceContaining("Connection is not available");
                assertThat(thirdEntries).hasValue(0);
            } finally {
                release.countDown();
            }
            first.get();
            second.get();
        } finally {
            release.countDown();
            dataSource.setMaximumPoolSize(originalMaximum);
        }
    }

    private static void writeAfterBarrier(
        VerifiedProposalWriter writer,
        RecruitmentExtractionProposal proposal,
        UUID evidenceId,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        try {
            ready.countDown();
            start.await();
            writer.write(proposal, List.of(evidenceId));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrency test was interrupted", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrency test was interrupted", exception);
        }
    }

    private static Void boundedOperation(
        PostgresFingerprintLock lock,
        String fingerprint,
        CountDownLatch entered,
        CountDownLatch release
    ) {
        return lock.execute(fingerprint, () -> {
            entered.countDown();
            await(release);
            return null;
        });
    }

    private static Void boundedOperation(
        PostgresFingerprintLock lock,
        String fingerprint,
        AtomicInteger entries
    ) {
        return lock.execute(fingerprint, () -> {
            entries.incrementAndGet();
            return null;
        });
    }

    private static boolean waitForUnresolvedAdvisoryLock(DataSourceProperties properties)
        throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (var connection = DriverManager.getConnection(
                 properties.determineUrl(), properties.determineUsername(), properties.determinePassword());
             var query = connection.prepareStatement("""
                 select count(*)
                 from pg_locks
                 where locktype = 'advisory' and not granted
                 """)) {
            while (System.nanoTime() < deadline) {
                try (var rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) > 0) return true;
                }
                Thread.sleep(25);
            }
            return false;
        }
    }

    private static RecruitmentExtractionProposal proposal(UUID evidenceId, String eventTitle) {
        var organizationType = new ExtractedFact<>(
            OrganizationType.PUBLIC_INSTITUTION, FactStatus.EXPLICIT, 0.99,
            List.of(evidenceId), null);
        var unknownDate = new ExtractedFact<java.time.LocalDate>(
            null, FactStatus.UNKNOWN, 0, List.of(), null);
        return new RecruitmentExtractionProposal(
            RecruitmentExtractionProposal.SCHEMA_VERSION,
            new RecruitmentExtractionProposal.SourceProposal(evidenceId, SOURCE_URL, eventTitle),
            new RecruitmentExtractionProposal.OrganizationProposal("杭州市并发测试单位", organizationType),
            new RecruitmentExtractionProposal.EventProposal(
                eventTitle, 2026, EventType.PUBLIC_INSTITUTION,
                unknownDate, unknownDate, unknownDate),
            List.of(), List.of(), 0.99, false);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.careeros.infrastructure.persistence")
    @EnableJpaRepositories(basePackages = "com.careeros.infrastructure.persistence")
    @Import({
        DefaultJobUpsertService.class,
        PostgresEventSourceLock.class
    })
    static class TestApplication {}
}
