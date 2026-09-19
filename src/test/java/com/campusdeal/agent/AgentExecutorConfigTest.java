package com.campusdeal.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutorConfigTest {

    @Test
    void executorRejectsWhenBoundedQueueIsFull() throws Exception {
        ExecutorService executor = new AgentExecutorConfig().agentExecutor(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.submit(() -> { });
            assertThatThrownBy(() -> executor.submit(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
