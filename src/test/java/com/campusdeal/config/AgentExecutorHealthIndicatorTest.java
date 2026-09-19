package com.campusdeal.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutorHealthIndicatorTest {

    @Test
    void fullAgentQueueIsReportedAsSaturated() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        try {
            executor.submit(() -> {
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.submit(() -> { });

            AgentExecutorHealthIndicator indicator = new AgentExecutorHealthIndicator();
            ReflectionTestUtils.setField(indicator, "executor", executor);

            var health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("state", "SATURATED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void disabledAgentDoesNotReportExecutorDetails() {
        AgentExecutorHealthIndicator indicator = new AgentExecutorHealthIndicator();
        ReflectionTestUtils.setField(indicator, "agentEnabled", false);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "DISABLED");
    }
}
