package com.campusdeal.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationGuardTest {

    @Test
    void namesMissingPropertiesWithoutEchoingValues() {
        ProductionConfigurationGuard guard = new ProductionConfigurationGuard();
        ReflectionTestUtils.setField(guard, "databasePassword", "db-secret");
        ReflectionTestUtils.setField(guard, "redisPassword", "redis-secret");
        ReflectionTestUtils.setField(guard, "agentEnabled", true);
        ReflectionTestUtils.setField(guard, "deepSeekApiKey", "");
        ReflectionTestUtils.setField(guard, "pgVectorEnabled", false);
        ReflectionTestUtils.setField(guard, "neo4jEnabled", false);

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAMPUSDEAL_DEEPSEEK_API_KEY")
                .hasMessageNotContaining("redis-secret")
                .hasMessageNotContaining("db-secret");
    }
}
