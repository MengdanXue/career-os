package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 一次模型往返必须有上限。
 *
 * <p>真机上发现的问题：模型不可达时，客户端自身的重试与退避让这个只读接口 120 秒都没有返回，
 * 请求线程一直被占着。模型抖动不该把查询变成挂起。
 */
class ModelTurnDeadlineTest {
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    });

    @AfterEach void shutdown() { pool.shutdownNow(); }

    /** 卡住的往返要在截止时间内被放弃，而不是一直等下去。 */
    @Test void aHangingTurnIsAbandonedAtTheDeadline() {
        var started = new CountDownLatch(1);
        long before = System.nanoTime();

        String answer = DecisionAgentConfiguration.boundedTurn(pool, Duration.ofMillis(150), () -> {
            started.countDown();
            Thread.sleep(30_000);
            return "太晚了";
        });

        long elapsedMillis = (System.nanoTime() - before) / 1_000_000;
        assertThat(answer).isEmpty();
        assertThat(elapsedMillis).isLessThan(5_000);
    }

    /** 失败与超时对规划来说是同一件事：这一步没有计划。都返回空串，不抛出去。 */
    @Test void aFailingTurnBecomesNoPlanRatherThanAnException() {
        assertThat(DecisionAgentConfiguration.boundedTurn(pool, Duration.ofSeconds(5), () -> {
            throw new IllegalStateException("model refused");
        })).isEmpty();
        assertThat(DecisionAgentConfiguration.boundedTurn(pool, Duration.ofSeconds(5), () -> null)).isEmpty();
    }

    @Test void aPromptTurnIsReturnedUnchanged() {
        assertThat(DecisionAgentConfiguration.boundedTurn(pool, Duration.ofSeconds(5),
            () -> "TOOL search_jobs")).isEqualTo("TOOL search_jobs");
    }

    /** 上限本身要是个合理的值——被人改成几小时就等于没有上限。 */
    @Test void theConfiguredDeadlineIsBounded() {
        assertThat(DecisionAgentConfiguration.MODEL_TURN_DEADLINE)
            .isLessThanOrEqualTo(Duration.ofSeconds(60))
            .isGreaterThan(Duration.ZERO);
    }
    /**
     * 超时之后线程收不回来，所以并发必须有界。
     *
     * <p>{@code Future.cancel(true)} 只是打个中断标记；卡在 socket 读上的线程收不到。
     * 模型不可达时每个请求都留下一个这样的线程，无界线程池就是"每来一个请求多一个线程，
     * 永远不还"。有界之后超出的请求当场被拒，解析成"这一步没有计划"，而不是把进程拖垮。
     */
    @Test void turnsBeyondTheConcurrencyLimitAreRefusedInsteadOfPilingUpThreads() throws Exception {
        var bounded = DecisionAgentConfiguration.boundedModelTurnPool();
        int limit = DecisionAgentConfiguration.MAX_CONCURRENT_MODEL_TURNS;
        var occupied = new CountDownLatch(limit);
        var release = new CountDownLatch(1);
        try {
            // 先把上限占满：这些往返都不会在截止时间内返回，线程也不会被真的中断掉。
            for (int index = 0; index < limit; index++) {
                bounded.submit(() -> { occupied.countDown(); release.await(); return "占着"; });
            }
            assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

            long before = System.nanoTime();
            String answer = DecisionAgentConfiguration.boundedTurn(bounded, Duration.ofSeconds(20),
                () -> "不该跑到这里");
            long elapsedMillis = (System.nanoTime() - before) / 1_000_000;

            assertThat(answer).isEmpty();
            // 当场被拒，不是排队等满 20 秒——排队只是把等待时间藏起来。
            assertThat(elapsedMillis).isLessThan(1_000);
            assertThat(bounded.getPoolSize()).isLessThanOrEqualTo(limit);
        } finally {
            release.countDown();
            bounded.shutdownNow();
        }
    }

    /** 上限本身要是个合理的值：改成几千就等于没有上限。 */
    @Test void theConcurrencyLimitIsSmallEnoughToMatter() {
        assertThat(DecisionAgentConfiguration.MAX_CONCURRENT_MODEL_TURNS)
            .isGreaterThan(0).isLessThanOrEqualTo(64);
    }
}
