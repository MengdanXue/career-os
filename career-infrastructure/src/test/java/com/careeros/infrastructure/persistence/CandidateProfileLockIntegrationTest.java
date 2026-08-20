package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.EducationLevel.MASTER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = CandidateProfileLockIntegrationTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class CandidateProfileLockIntegrationTest {
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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    void candidateWriteLockSerializesConcurrentProfileCommands(
        @Autowired CandidateProfileJpaRepository candidates,
        @Autowired PlatformTransactionManager transactionManager,
        @Autowired JdbcTemplate jdbc
    ) throws Exception {
        UUID candidateId = UUID.randomUUID();
        candidates.saveAndFlush(candidate(candidateId));
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttemptedLock = new CountDownLatch(1);
        var transactions = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                assertThat(candidates.findByIdForUpdate(candidateId)).isPresent();
                firstHasLock.countDown();
                await(releaseFirst);
            }));
            assertThat(firstHasLock.await(10, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                secondAttemptedLock.countDown();
                assertThat(candidates.findByIdForUpdate(candidateId)).isPresent();
            }));
            try {
                assertThat(secondAttemptedLock.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(waitForBlockedDatabaseLock(jdbc)).isTrue();
            } finally {
                releaseFirst.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            candidates.deleteById(candidateId);
        }
    }

    private static boolean waitForBlockedDatabaseLock(JdbcTemplate jdbc) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                "select count(*) from pg_locks where not granted", Integer.class);
            if (waiting != null && waiting > 0) return true;
            Thread.onSpinWait();
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("candidate lock test interrupted", exception);
        }
    }

    private static JpaModels.CandidateProfileEntity candidate(UUID id) {
        var entity = new JpaModels.CandidateProfileEntity();
        entity.id = id;
        entity.displayName = "并发测试候选人";
        entity.birthYear = 1992;
        entity.birthMonth = 12;
        entity.highestEducation = MASTER;
        entity.profileVersion = "profile-before-lock";
        return entity;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.careeros.infrastructure.persistence")
    @EnableJpaRepositories(basePackages = "com.careeros.infrastructure.persistence")
    static class TestApplication {}
}
