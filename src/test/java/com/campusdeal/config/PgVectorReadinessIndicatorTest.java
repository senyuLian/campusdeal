package com.campusdeal.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PgVectorReadinessIndicatorTest {

    @Test
    void disabledVectorBackendIsReadyWithoutOpeningAConnection() {
        PgVectorReadinessIndicator indicator = new PgVectorReadinessIndicator();
        ReflectionTestUtils.setField(indicator, "enabled", false);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "DISABLED");
    }

    @Test
    void enabledBackendWithMissingUrlIsActionablyDown() {
        PgVectorReadinessIndicator indicator = new PgVectorReadinessIndicator();
        ReflectionTestUtils.setField(indicator, "enabled", true);
        ReflectionTestUtils.setField(indicator, "url", "");

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("state", "MISCONFIGURED");
    }
}
