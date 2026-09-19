package com.campusdeal.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReliabilityMetricsTest {

    @Test
    void metricLabelsAreBoundedAndDoNotIncludeRawPayloads() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReliabilityMetrics metrics = new ReliabilityMetrics(registry);
        metrics.increment("campusdeal.cache.query", "x".repeat(200));

        var counter = registry.get("campusdeal.cache.query").counter();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getTag("outcome")).hasSize(32);
    }
}
