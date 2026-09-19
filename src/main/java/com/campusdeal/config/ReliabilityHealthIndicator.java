package com.campusdeal.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusdeal.cache.BloomFilterService;
import com.campusdeal.cache.BloomFilterStats;
import com.campusdeal.canal.CanalClient;
import com.campusdeal.entity.FlashDeal;
import com.campusdeal.entity.FlashOrderIntent;
import com.campusdeal.entity.Outbox;
import com.campusdeal.mapper.FlashDealMapper;
import com.campusdeal.mapper.FlashOrderIntentMapper;
import com.campusdeal.mapper.OutboxMapper;
import com.campusdeal.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** Bounded, non-sensitive health details for the recoverable pipelines. */
@Component("campusDealReliability")
public class ReliabilityHealthIndicator implements HealthIndicator {

    @Autowired(required = false) private OutboxMapper outboxMapper;
    @Autowired(required = false) private FlashOrderIntentMapper intentMapper;
    @Autowired(required = false) private FlashDealMapper flashDealMapper;
    @Autowired(required = false) private StringRedisTemplate stringRedisTemplate;
    @Autowired(required = false) private BloomFilterService bloomFilterService;
    @Autowired(required = false) private CanalClient canalClient;
    @Autowired(required = false) private ReliabilityMetrics reliabilityMetrics;
    @Value("${campusdeal.flashdeal.durable-acceptance-enabled:true}")
    private boolean durableAcceptanceEnabled = true;
    @Value("${campusdeal.canal.enabled:false}")
    private boolean canalEnabled;
    @Value("${campusdeal.health.max-bloom-rebuild-age-minutes:15}")
    private long maxBloomRebuildAgeMinutes = 15;

    @Value("${campusdeal.health.max-outbox-age-minutes:15}")
    private long maxOutboxAgeMinutes = 15;
    @Value("${campusdeal.health.max-pending-intents:100}")
    private long maxPendingIntents = 100;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        boolean degraded = false;
        try {
            if (outboxMapper != null) {
                long pending = outboxMapper.selectCount(Wrappers.<Outbox>lambdaQuery()
                        .eq(Outbox::getStatus, "PENDING"));
                long failed = outboxMapper.selectCount(Wrappers.<Outbox>lambdaQuery()
                        .eq(Outbox::getStatus, "FAILED"));
                List<Outbox> oldest = outboxMapper.selectList(Wrappers.<Outbox>lambdaQuery()
                        .eq(Outbox::getStatus, "PENDING")
                        .orderByAsc(Outbox::getCreateTime).last("LIMIT 1"));
                long ageMinutes = oldest.isEmpty() || oldest.get(0).getCreateTime() == null ? 0
                        : Math.max(0, Duration.between(oldest.get(0).getCreateTime(), LocalDateTime.now()).toMinutes());
                builder.withDetail("outboxPending", pending)
                        .withDetail("outboxFailed", failed)
                        .withDetail("oldestOutboxAgeMinutes", ageMinutes);
                degraded |= ageMinutes > maxOutboxAgeMinutes;
            }
            if (intentMapper != null) {
                long pendingIntents = intentMapper.selectCount(Wrappers.<FlashOrderIntent>lambdaQuery()
                        .in(FlashOrderIntent::getStatus, "ACCEPTED", "PROCESSING"));
                builder.withDetail("pendingIntents", pendingIntents);
                degraded |= pendingIntents > maxPendingIntents;
            }
            if (durableAcceptanceEnabled && flashDealMapper != null && stringRedisTemplate != null) {
                int mismatches = 0;
                List<FlashDeal> active = flashDealMapper.selectList(Wrappers.<FlashDeal>lambdaQuery()
                        .gt(FlashDeal::getEndTime, LocalDateTime.now()).last("LIMIT 100"));
                for (FlashDeal deal : active) {
                    String value = stringRedisTemplate.opsForValue().get(
                            RedisConstants.FLASH_DEAL_STOCK_KEY + deal.getVoucherId());
                    if (value != null && deal.getStock() != null && !value.equals(String.valueOf(deal.getStock()))) {
                        mismatches++;
                    }
                }
                builder.withDetail("stockMismatches", mismatches);
                degraded |= mismatches > 0;
            }
        } catch (Exception e) {
            builder.withDetail("reliabilityData", "UNAVAILABLE");
            degraded = true;
        }
        if (bloomFilterService != null) {
            BloomFilterStats stats = bloomFilterService.getStats();
            builder.withDetail("bloomEntries", stats == null ? 0 : stats.getApproximateElementCount());
            long bloomAgeMinutes = stats == null || stats.getLastRebuildTime() == null
                    ? Long.MAX_VALUE
                    : Math.max(0, Duration.between(stats.getLastRebuildTime(), LocalDateTime.now()).toMinutes());
            builder.withDetail("bloomRebuildAgeMinutes", bloomAgeMinutes)
                    .withDetail("bloomRepairCount", metric("campusdeal.bloom", "rebuild"));
            degraded |= bloomAgeMinutes > maxBloomRebuildAgeMinutes;
        }
        if (canalClient != null) {
            var canal = canalClient.getStatus();
            long canalLagSeconds = canal.getLastEventAt() <= 0 ? 0
                    : Math.max(0, (System.currentTimeMillis() - canal.getLastEventAt()) / 1000);
            builder.withDetail("canal", canal.isRunning() ? "RUNNING" : "STOPPED")
                    .withDetail("canalReconnects", canal.getReconnectCount())
                    .withDetail("canalLastBatchSize", canal.getLastBatchSize())
                    .withDetail("canalLastEventAt", canal.getLastEventAt())
                    .withDetail("canalLagSeconds", canalLagSeconds);
            degraded |= canalEnabled && !canal.isRunning();
        }
        builder.withDetail("cacheHits", metric("campusdeal.cache.query", "hit"))
                .withDetail("cacheMisses", metric("campusdeal.cache.query", "miss_loaded"))
                .withDetail("cacheRebuildFailures", metric("campusdeal.cache.query", "rebuild_failure"))
                .withDetail("cacheLockContention", metric("campusdeal.cache.query", "lock_contention"));
        return degraded ? builder.down().build() : builder.build();
    }

    private double metric(String name, String outcome) {
        return reliabilityMetrics == null ? 0.0 : reliabilityMetrics.count(name, outcome);
    }
}
