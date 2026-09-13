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
}
