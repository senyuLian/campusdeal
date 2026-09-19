package com.campusdeal.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Health/telemetry view for the BM25 and optional vector retrieval channels. */
@Component("campusDealRetrieval")
public class RetrievalHealthIndicator implements HealthIndicator {

    @Autowired(required = false)
    private ReliabilityMetrics reliabilityMetrics;

    @Value("${campusdeal.pgvector.enabled:false}")
    private boolean vectorEnabled;

    @Override
    public Health health() {
        double degraded = metric("vector_degraded");
        double disabled = metric("vector_disabled");
        return Health.up()
                .withDetail("bm25", "AVAILABLE")
                .withDetail("vector", vectorEnabled ? (degraded > 0 ? "DEGRADED" : "ENABLED") : "DISABLED")
                .withDetail("vectorDegraded", degraded)
                .withDetail("vectorDisabled", disabled)
                .build();
    }

    private double metric(String outcome) {
        return reliabilityMetrics == null ? 0.0
                : reliabilityMetrics.count("campusdeal.rag.search", outcome);
    }
}
