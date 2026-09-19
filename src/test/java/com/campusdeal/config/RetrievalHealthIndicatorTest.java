package com.campusdeal.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalHealthIndicatorTest {

    @Test
    void disabledVectorStillReportsBm25Available() {
        RetrievalHealthIndicator indicator = new RetrievalHealthIndicator();
        ReflectionTestUtils.setField(indicator, "vectorEnabled", false);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("bm25", "AVAILABLE")
                .containsEntry("vector", "DISABLED");
    }

    @Test
    void vectorFailureIsVisibleWithoutRawQueryLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReliabilityMetrics metrics = new ReliabilityMetrics(registry);
        metrics.increment("campusdeal.rag.search", "vector_degraded");
        RetrievalHealthIndicator indicator = new RetrievalHealthIndicator();
        ReflectionTestUtils.setField(indicator, "vectorEnabled", true);
        ReflectionTestUtils.setField(indicator, "reliabilityMetrics", metrics);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("vector", "DEGRADED")
                .containsEntry("vectorDegraded", 1.0);
        assertThat(registry.getMeters()).allMatch(m -> m.getId().getTags().stream()
                .noneMatch(tag -> tag.getValue().contains("prompt")));
    }
}
