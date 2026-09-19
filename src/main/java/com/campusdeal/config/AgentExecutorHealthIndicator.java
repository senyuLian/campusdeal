package com.campusdeal.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Readiness contribution for the bounded Agent executor.  The probe exposes
 * queue/worker counts and bounded outcome counters without including prompts,
 * session contents, or exception text.
 */
@Component("campusDealAgentExecutor")
public class AgentExecutorHealthIndicator implements HealthIndicator {

    @Autowired(required = false)
    @Qualifier("agentExecutor")
    private ThreadPoolExecutor executor;

    @Autowired(required = false)
    private ReliabilityMetrics reliabilityMetrics;

    @Value("${campusdeal.agent.enabled:true}")
    private boolean agentEnabled = true;

    @Override
    public Health health() {
        if (!agentEnabled) {
            return Health.up().withDetail("state", "DISABLED").build();
        }
        if (executor == null) {
            return Health.up().withDetail("state", "UNAVAILABLE").build();
        }
        int queueDepth = executor.getQueue().size();
        int queueCapacity = queueDepth + executor.getQueue().remainingCapacity();
        Health.Builder builder = Health.up()
                .withDetail("state", "READY")
                .withDetail("active", executor.getActiveCount())
                .withDetail("pool", executor.getPoolSize())
                .withDetail("queueDepth", queueDepth)
                .withDetail("queueCapacity", queueCapacity)
                .withDetail("rejected", count("rejected"))
                .withDetail("timeouts", count("timeout"))
                .withDetail("cancellations", count("cancelled"))
                .withDetail("safetyRejections", count("safety_rejected"));
        if (executor.getQueue().remainingCapacity() == 0) {
            builder.down().withDetail("state", "SATURATED");
        }
        return builder.build();
    }

    private double count(String outcome) {
        return reliabilityMetrics == null ? 0.0
                : reliabilityMetrics.count("campusdeal.agent.turn", outcome);
    }
}
