package com.campusdeal.config;

import com.campusdeal.entity.Outbox;
import com.campusdeal.cache.BloomFilterService;
import com.campusdeal.cache.BloomFilterStats;
import com.campusdeal.canal.CanalClient;
import com.campusdeal.canal.CanalStatus;
import com.campusdeal.mapper.FlashOrderIntentMapper;
import com.campusdeal.mapper.OutboxMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReliabilityHealthIndicatorTest {

    @Test
    void staleOutboxAndPendingIntentsDegradeHealth() {
        OutboxMapper outbox = mock(OutboxMapper.class);
        FlashOrderIntentMapper intents = mock(FlashOrderIntentMapper.class);
        Outbox oldest = new Outbox();
        oldest.setCreateTime(LocalDateTime.now().minusHours(1));
        when(outbox.selectCount(any())).thenReturn(101L, 2L);
        when(outbox.selectList(any())).thenReturn(List.of(oldest));
        when(intents.selectCount(any())).thenReturn(101L);

        ReliabilityHealthIndicator indicator = new ReliabilityHealthIndicator();
        ReflectionTestUtils.setField(indicator, "outboxMapper", outbox);
        ReflectionTestUtils.setField(indicator, "intentMapper", intents);
        ReflectionTestUtils.setField(indicator, "maxOutboxAgeMinutes", 15L);
        ReflectionTestUtils.setField(indicator, "maxPendingIntents", 100L);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKeys("outboxPending", "outboxFailed", "pendingIntents");
    }

    @Test
    void cacheCanalAndBloomTelemetryIsExposedWithoutSensitiveLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReliabilityMetrics metrics = new ReliabilityMetrics(registry);
        metrics.increment("campusdeal.cache.query", "hit");
        metrics.increment("campusdeal.cache.query", "lock_contention");
        BloomFilterService bloom = mock(BloomFilterService.class);
        when(bloom.getStats()).thenReturn(BloomFilterStats.builder()
                .approximateElementCount(2)
                .lastRebuildTime(LocalDateTime.now())
                .build());
        CanalClient canal = mock(CanalClient.class);
        when(canal.getStatus()).thenReturn(CanalStatus.builder()
                .running(true).reconnectCount(1).lastBatchSize(3).lastEventAt(System.currentTimeMillis()).build());

        ReliabilityHealthIndicator indicator = new ReliabilityHealthIndicator();
        ReflectionTestUtils.setField(indicator, "bloomFilterService", bloom);
        ReflectionTestUtils.setField(indicator, "canalClient", canal);
        ReflectionTestUtils.setField(indicator, "reliabilityMetrics", metrics);
        ReflectionTestUtils.setField(indicator, "maxBloomRebuildAgeMinutes", 15L);

        var health = indicator.health();

        assertThat(health.getDetails()).containsEntry("cacheHits", 1.0)
                .containsEntry("cacheLockContention", 1.0)
                .containsEntry("canalReconnects", 1L)
                .containsEntry("bloomEntries", 2L);
        assertThat(registry.getMeters()).allMatch(m -> m.getId().getTags().stream()
                .noneMatch(tag -> tag.getValue().contains("password")));
    }
}
