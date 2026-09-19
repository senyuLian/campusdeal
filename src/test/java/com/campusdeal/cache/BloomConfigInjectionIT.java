package com.campusdeal.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import jakarta.annotation.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 修复验证：BloomProperties 配置绑定生效（Spring 上下文级 IT）。
 *
 * <p>通过 {@code @TestPropertySource} 覆盖 {@code campusdeal.bloom.*}，
 * 若注入生效，则 {@code rebuild()} 后 stats 中的 fpp / 内存估算应反映新配置值；
 * 若仍是普通字段默认值（修复前），fpp=0.01、容量取活动数，断言将失败。</p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "CAMPUSDEAL_RUN_INTEGRATION", matches = "true")
@TestPropertySource(properties = {
        "campusdeal.bloom.expected-insertions=50",
        "campusdeal.bloom.fpp=0.002",
        "campusdeal.bloom.rebuild-interval-seconds=120"
})
class BloomConfigInjectionIT {

    @Resource
    private BloomFilterServiceImpl bloomFilterService;

    @Test
    @DisplayName("T5: yaml 中 fpp=0.002 / expected-insertions=50 注入生效")
    void configShouldBeInjectedIntoBloomService() {
        bloomFilterService.rebuild();

        BloomFilterStats stats = bloomFilterService.getStats();

        // 修复前未注入 → 恒为默认 0.01，断言失败；修复后 = 0.002
        assertThat(stats.getExpectedFpp()).isEqualTo(0.002);

        // expectedInsertions = max(活动数, max(50,1)) ≥ 50 → 内存估算 ≥ 50*10 = 500B
        assertThat(stats.getEstimatedMemoryBytes()).isGreaterThanOrEqualTo(50 * 10L);
    }
}
