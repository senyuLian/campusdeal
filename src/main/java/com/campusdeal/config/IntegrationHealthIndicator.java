package com.campusdeal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Exposes feature state without making health probes leak credentials or URLs. */
@Component("campusDealIntegrations")
public class IntegrationHealthIndicator implements HealthIndicator {

    @Value("${campusdeal.kafka.enabled:false}")
    private boolean kafkaEnabled;
    @Value("${campusdeal.canal.enabled:false}")
    private boolean canalEnabled;
    @Value("${campusdeal.pgvector.enabled:false}")
    private boolean vectorEnabled;
    @Value("${campusdeal.pgvector.url:}")
    private String vectorUrl;
    @Value("${campusdeal.neo4j.enabled:false}")
    private boolean neo4jEnabled;
    @Value("${campusdeal.agent.enabled:true}")
    private boolean agentEnabled;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        builder.withDetail("kafka", kafkaEnabled ? "ENABLED" : "DISABLED");
        builder.withDetail("canal", canalEnabled ? "ENABLED" : "DISABLED");
        builder.withDetail("agent", agentEnabled ? "ENABLED" : "DISABLED");
        if (!vectorEnabled) {
            builder.withDetail("vector", "DISABLED");
        } else if (vectorUrl == null || vectorUrl.isBlank()) {
            builder.down().withDetail("vector", "MISCONFIGURED");
        } else {
            builder.withDetail("vector", "ENABLED");
        }
        builder.withDetail("neo4j", neo4jEnabled ? "ENABLED" : "DISABLED");
        return builder.build();
    }
}
