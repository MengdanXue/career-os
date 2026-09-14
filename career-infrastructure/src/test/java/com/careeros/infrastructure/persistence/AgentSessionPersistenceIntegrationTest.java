package com.careeros.infrastructure.persistence;

import static com.careeros.domain.CandidateFacts.CandidateFactKey.*;
import static com.careeros.domain.DomainEnums.EducationLevel.MASTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.AgentSession;
import com.careeros.application.AgentSession.PendingConfirmation;
import com.careeros.application.AgentSession.SessionFilters;
import com.careeros.application.AgentSessionPorts.Sessions;
import com.careeros.application.AgentSessionService.SessionNotFoundException;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Uses only a new disposable database; repository mocks cannot expose first-level-cache races. */
@SpringBootTest(classes = AgentSessionPersistenceIntegrationTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class AgentSessionPersistenceIntegrationTest {
    private static final Instant AT = Instant.parse("2026-09-14T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("stage1_agent_session_cas")
        .withUsername("session_test")
        .withPassword("session_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "set lock_timeout = '5s'");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> false);
    }

    @Autowired Sessions sessions;
    @Autowired AgentSessionJpaRepository repository;
    @Autowired CandidateProfileJpaRepository candidates;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    private UUID owner;
    private UUID otherOwner;

    @BeforeEach void createOnlyThisTestsCandidates() {
        owner = UUID.randomUUID();
        otherOwner = UUID.randomUUID();
        candidates.saveAndFlush(candidate(owner));
        candidates.saveAndFlush(candidate(otherOwner));
    }

    @Test void exactCompareAndSetPersistsVersionAndPendingChangesWithoutChangingTheVisibleOrder() {
        var original = initial(UUID.randomUUID(), owner);
        sessions.save(original);
        var seen = sessions.find(original.sessionId()).orElseThrow();
        var replacement = seen.withPendingAndVersion(
            List.of(seen.pendingConfirmations().getLast()), "profile-v2", AT.plusSeconds(1));

        assertThat(sessions.compareAndSet(seen, replacement)).isTrue();

        var stored = sessions.find(original.sessionId()).orElseThrow();
        assertThat(stored).isEqualTo(replacement);
        assertThat(stored.lastJobIdsInOrder()).containsExactlyElementsOf(original.lastJobIdsInOrder());
        assertThat(stored.pendingConfirmations()).singleElement()
            .satisfies(item -> assertThat(item.factKey()).isEqualTo(SOCIAL_INSURANCE_AT_APPLICATION));
    }

    @Test void candidateOwnershipCannotBeReplacedBySaveOrByForgedCasObservations() {
        var original = initial(UUID.randomUUID(), owner);
        sessions.save(original);
        var forged = new AgentSession(original.sessionId(), otherOwner, original.filters(),
            original.lastJobIdsInOrder(), original.pendingConfirmations(), original.profileVersion(), original.updatedAt());

        assertThatThrownBy(() -> sessions.save(forged)).isInstanceOf(SessionNotFoundException.class);
        assertThat(sessions.compareAndSet(forged, forged.withProfileVersion("forged-v2", AT.plusSeconds(1))))
            .isFalse();
        assertThatThrownBy(() -> sessions.compareAndSet(original, forged))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");

        assertThat(sessions.find(original.sessionId())).contains(original);
    }

    @Test void pendingOnlyChangesInvalidateCasEvenWhenVersionAndTimestampAreUnchanged() {
        var original = initial(UUID.randomUUID(), owner);
        sessions.save(original);
        var changedPending = original.withPendingAndVersion(
            List.of(original.pendingConfirmations().getLast()), original.profileVersion(), original.updatedAt());
        assertThat(sessions.compareAndSet(original, changedPending)).isTrue();

        assertThat(sessions.compareAndSet(original,
            original.withProfileVersion("stale-v2", AT.plusSeconds(1)))).isFalse();

        assertThat(sessions.find(original.sessionId())).contains(changedPending);
    }

    @Test void aLockedReadMustRefreshAnAlreadyManagedSessionBeforeComparingIt() {
        var original = initial(UUID.randomUUID(), owner);
        sessions.save(original);
        var winner = original.withPendingAndVersion(
            List.of(original.pendingConfirmations().getLast()), "profile-v2", AT.plusSeconds(1));
        var transactions = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Boolean staleWrite = transactions.execute(ignored -> {
                // This read puts the old entity in the outer transaction's first-level cache,
                // just as a query reads ownership before a slower assessment/description.
                var observedBeforeAssessment = sessions.find(original.sessionId()).orElseThrow();
                assertThat(result(executor.submit(() -> sessions.compareAndSet(original, winner)))).isTrue();
                return sessions.compareAndSet(observedBeforeAssessment,
                    observedBeforeAssessment.withPendingAndVersion(List.of(), "stale-v3", AT.plusSeconds(2)));
            });
            assertThat(staleWrite).isFalse();
        }

        assertThat(sessions.find(original.sessionId())).contains(winner);
    }

    @Test void twoTransactionsWithTheSameObservationCannotBothAdvanceTheSession() throws Exception {
        var original = initial(UUID.randomUUID(), owner);
        sessions.save(original);
        var firstReplacement = original.withPendingAndVersion(
            List.of(original.pendingConfirmations().getLast()), "first-v2", AT.plusSeconds(1));
        var secondReplacement = original.withPendingAndVersion(
            List.of(original.pendingConfirmations().getFirst()), "second-v2", AT.plusSeconds(2));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> casAfterBothTransactionsRead(original, firstReplacement, ready, start));
            var second = executor.submit(() -> casAfterBothTransactionsRead(original, secondReplacement, ready, start));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            boolean firstWon = result(first);
            boolean secondWon = result(second);
            assertThat(firstWon).isNotEqualTo(secondWon);
            assertThat(sessions.find(original.sessionId())).contains(firstWon ? firstReplacement : secondReplacement);
        } finally {
            start.countDown();
        }
    }

    @Test void aConcurrentFirstInsertCannotMergeOverTheWinningOwner() {
        UUID sessionId = UUID.randomUUID();
        var first = initial(sessionId, owner);
        var second = initial(sessionId, otherOwner);
        var secondObservedMissing = new CountDownLatch(1);
        var firstCommitted = new CountDownLatch(1);
        // Both actual database SELECTs must observe a missing row. Delay the second return
        // until the first insert committed: merge would now overwrite it, persist must fail.
        var firstSessions = adapterAfterMissingRead(ignored -> await(secondObservedMissing));
        var secondSessions = adapterAfterMissingRead(ignored -> {
            secondObservedMissing.countDown();
            await(firstCommitted);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var secondWrite = executor.submit(() -> secondSessions.save(second));
            try {
                assertThat(firstSessions.save(first)).isEqualTo(first);
            } finally {
                firstCommitted.countDown();
            }
            assertThatThrownBy(() -> result(secondWrite))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .hasStackTraceContaining("agent_session_pkey");
        } finally {
            firstCommitted.countDown();
        }

        assertThat(sessions.find(sessionId)).contains(first);
    }

    private boolean casAfterBothTransactionsRead(AgentSession original, AgentSession replacement,
                                                 CountDownLatch ready, CountDownLatch start) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(ignored -> {
            var observed = sessions.find(original.sessionId()).orElseThrow();
            ready.countDown();
            await(start);
            return sessions.compareAndSet(observed, replacement);
        }));
    }

    private Sessions adapterAfterMissingRead(Consumer<UUID> afterMissingRead) {
        var timedRepository = (AgentSessionJpaRepository) Proxy.newProxyInstance(
            AgentSessionJpaRepository.class.getClassLoader(), new Class<?>[] { AgentSessionJpaRepository.class },
            (proxy, method, arguments) -> {
                Object actual;
                try {
                    actual = method.invoke(repository, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
                if (method.getName().equals("findByIdForUpdate") && ((Optional<?>) actual).isEmpty()) {
                    afterMissingRead.accept((UUID) arguments[0]);
                }
                return actual;
            });
        return new PersistenceAdaptersConfiguration().agentSessions(timedRepository, transactionManager, entityManager);
    }

    private static AgentSession initial(UUID sessionId, UUID candidateId) {
        UUID firstJob = UUID.randomUUID();
        UUID secondJob = UUID.randomUUID();
        return new AgentSession(sessionId, candidateId, new SessionFilters(null, "杭州", null, 5),
            List.of(secondJob, firstJob), List.of(
                new PendingConfirmation(EMPLOYER_SETTLEMENT_AT_APPLICATION, "报名时是否落实工作单位？", firstJob),
                new PendingConfirmation(SOCIAL_INSURANCE_AT_APPLICATION, "报名时社保状态？", secondJob)),
            "profile-v1", AT);
    }

    private static JpaModels.CandidateProfileEntity candidate(UUID id) {
        var entity = new JpaModels.CandidateProfileEntity();
        entity.id = id;
        entity.displayName = "会话并发测试候选人";
        entity.birthYear = 1992;
        entity.birthMonth = 12;
        entity.highestEducation = MASTER;
        entity.profileVersion = "profile-v1";
        return entity;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("session test barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("session test interrupted", interrupted);
        }
    }

    private static <T> T result(Future<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("session test interrupted", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("session test task failed", failure);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.careeros.infrastructure.persistence")
    @EnableJpaRepositories(basePackages = "com.careeros.infrastructure.persistence")
    static class TestApplication {
        @Bean Sessions sessions(AgentSessionJpaRepository repository, PlatformTransactionManager manager,
                                EntityManager entityManager) {
            return new PersistenceAdaptersConfiguration().agentSessions(repository, manager, entityManager);
        }
    }
}
