package com.campusdeal.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded executor for Agent turns so model stalls cannot grow the common pool. */
@Configuration
public class AgentExecutorConfig {

    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor agentExecutor(
            @Value("${campusdeal.agent.executor-threads:8}") int threads,
            @Value("${campusdeal.agent.executor-queue-capacity:32}") int queueCapacity) {
        int safeThreads = Math.max(1, Math.min(64, threads));
        int safeQueue = Math.max(1, Math.min(10_000, queueCapacity));
        return new ThreadPoolExecutor(
                safeThreads, safeThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(safeQueue),
                runnable -> {
                    Thread thread = new Thread(runnable, "agent-turn");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}
