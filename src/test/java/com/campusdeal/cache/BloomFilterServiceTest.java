package com.campusdeal.cache;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.campusdeal.config.ReliabilityMetrics;
import com.campusdeal.entity.FlashDeal;
import com.campusdeal.mapper.FlashDealMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 布隆过滤器单元测试
 * <p>纯 Mock 测试，不依赖外部服务。FlashDeal 数据通过 Mock FlashDealMapper 提供。</p>
 */
@ExtendWith(MockitoExtension.class)
class BloomFilterServiceTest {

    @Mock
    private FlashDealMapper flashDealMapper;

    @InjectMocks
    private BloomFilterServiceImpl bloomFilter;

    private FlashDeal activeDeal(Long voucherId) {
        FlashDeal deal = new FlashDeal();
        deal.setVoucherId(voucherId);
        deal.setEndTime(LocalDateTime.now().plusDays(1));  // 未过期
        return deal;
    }

    @Test
    @DisplayName("BF-01: 已加载的元素应该命中")
    void shouldContainLoadedElement() {
        when(flashDealMapper.selectList(any(Wrapper.class))).thenReturn(List.of(activeDeal(1L)));

        bloomFilter.afterPropertiesSet();

        assertThat(bloomFilter.mightContain(1L)).isTrue();
    }

    @Test
    @DisplayName("BF-02: 未加载的元素一定返回 false")
    void shouldNotContainUnloadedElement() {
        when(flashDealMapper.selectList(any(Wrapper.class))).thenReturn(List.of(activeDeal(1L)));

        bloomFilter.afterPropertiesSet();

        assertThat(bloomFilter.mightContain(999L)).isFalse();
    }

    @Test
    @DisplayName("BF-03: 空表加载不抛异常，布隆为空")
    void shouldHandleEmptyDealList() {
        when(flashDealMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

        // 不应抛 IllegalArgumentException（Guava 要求 expectedInsertions > 0）
        bloomFilter.afterPropertiesSet();

        assertThat(bloomFilter.mightContain(1L)).isFalse();
        assertThat(bloomFilter.mightContain(999L)).isFalse();
        assertThat(bloomFilter.getStats().getApproximateElementCount()).isZero();
    }

    @Test
    @DisplayName("BF-04: 定时重建后能识别新增活动")
    void shouldPickUpNewDealAfterRebuild() {
        // 初始只含 dealId=1
        when(flashDealMapper.selectList(any(Wrapper.class))).thenReturn(List.of(activeDeal(1L)));
        bloomFilter.afterPropertiesSet();
        assertThat(bloomFilter.mightContain(2L)).isFalse();

        // 重建后含 dealId=1,2
        when(flashDealMapper.selectList(any(Wrapper.class))).thenReturn(List.of(activeDeal(1L), activeDeal(2L)));
        bloomFilter.rebuild();

        assertThat(bloomFilter.mightContain(2L)).isTrue();
    }

    @Test
    @DisplayName("BF-05: null 输入返回 false")
    void shouldReturnFalseForNullInput() {
        assertThat(bloomFilter.mightContain(null)).isFalse();
    }

    @Test
    @DisplayName("BF-06: 启动时数据库不可用，不抛异常，布隆为空，可定时重建恢复")
    void shouldNotFailWhenDbDownAtStartup() {
        when(flashDealMapper.selectList(any(Wrapper.class)))
                .thenThrow(new RuntimeException("db down"));

        // 不应抛出异常（安全失败：应用继续启动，等待定时重建）
        bloomFilter.afterPropertiesSet();

        assertThat(bloomFilter.mightContain(1L)).isFalse();

        // 数据库恢复后，定时重建能成功加载数据
        when(flashDealMapper.selectList(any(Wrapper.class))).thenReturn(List.of(activeDeal(1L)));
        bloomFilter.rebuild();
        assertThat(bloomFilter.mightContain(1L)).isTrue();
    }

    @Test
    @DisplayName("BF-M1: 重建失败与成功均记录可观测结果")
    void shouldRecordRebuildTelemetry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(bloomFilter, "reliabilityMetrics", new ReliabilityMetrics(registry));
        when(flashDealMapper.selectList(any(Wrapper.class)))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(List.of(activeDeal(7L)));

        bloomFilter.afterPropertiesSet();
        bloomFilter.rebuild();

        assertThat(registry.get("campusdeal.bloom")
                .tag("outcome", "rebuild_failure").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("campusdeal.bloom")
                .tag("outcome", "rebuild").counter().count()).isEqualTo(1.0);
    }
}
