package com.campusdeal.cache;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusdeal.entity.FlashDeal;
import com.campusdeal.config.ReliabilityMetrics;
import com.campusdeal.security.SensitiveLogSanitizer;
import com.campusdeal.mapper.FlashDealMapper;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 布隆过滤器服务实现
 *
 * <p>数据来源：数据库中所有未过期的秒杀活动 ID。
 * 应用启动时全量构建，之后每 {@code campusdeal.bloom.rebuild-interval-seconds} 秒定时重建，
 * 保证新增的秒杀活动能尽快被识别。</p>
 *
 * <p>重建只读取未过期的 deal ID，因此直接依赖 {@code FlashDealMapper} 而非
 * {@code IFlashDealService}——否则会与 {@code FlashDealServiceImpl}
 * （其秒杀流程依赖布隆过滤器）形成循环依赖。</p>
 *
 * <p>为什么用布隆过滤器而不是 HashSet：Guava BloomFilter 每个元素约占用 10 bytes，
 * 10000 个 deal 仅约 100KB；HashSet 存 10000 个 Long 对象需 240KB+。</p>
 */
@Slf4j
@Component
public class BloomFilterServiceImpl implements BloomFilterService, InitializingBean {

    @Resource
    private FlashDealMapper flashDealMapper;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;
    @Autowired(required = false)
    private ReliabilityMetrics reliabilityMetrics;

    private static final String COMMITTED_DEALS_KEY = "bloom:committed:deals";

    /**
     * 布隆过滤器配置（@ConfigurationProperties 绑定；无 Spring 环境下使用默认值，便于单元测试）
     *
     * <p>修复 T5：原先为普通字段初始化，Spring 不会注入 {@code @ConfigurationProperties} 绑定的
     * 配置 Bean，yaml 中的 {@code fpp / expected-insertions} 恒为默认值。加 {@code @Resource}
     * 注入后，配置经 CaffeineConfig 的 {@code @EnableConfigurationProperties} 生效。</p>
     */
    @Resource
    private BloomProperties properties = new BloomProperties();

    /**
     * 当前生效的布隆过滤器（volatile：定时重建时对读线程立即可见）
     */
    private volatile BloomFilter<Long> bloomFilter =
            BloomFilter.create(Funnels.longFunnel(), 1, 0.01);

    private volatile BloomFilterStats stats =
            BloomFilterStats.builder()
                    .expectedFpp(properties.getFpp())
                    .approximateElementCount(0)
                    .estimatedMemoryBytes(0)
                    .lastRebuildTime(LocalDateTime.now())
                    .uptimeHours(0)
                    .build();

    // === 初始化：Spring 容器启动后立即构建 ===
    @Override
    public void afterPropertiesSet() {
        try {
            rebuild();
        } catch (Exception e) {
            // 启动时数据库不可用不应阻塞整个应用：记录告警，等待定时重建恢复。
            // 期间布隆过滤器为空，秒杀请求会被拒在入口（安全失败）。
            log.warn("Bloom filter initial rebuild failed (will retry on schedule): {}",
                    SensitiveLogSanitizer.exceptionSummary(e));
        }
    }

    // === 定时重建：周期性刷新（@EnableScheduling 已开启） ===
    @Scheduled(fixedRateString = "${campusdeal.bloom.rebuild-interval-seconds:300}000")
    public void rebuild() {
        List<FlashDeal> activeDeals;
        try {
            activeDeals = flashDealMapper.selectList(
                    Wrappers.<FlashDeal>lambdaQuery()
                            .gt(FlashDeal::getEndTime, LocalDateTime.now())
            );
        } catch (RuntimeException e) {
            metric("rebuild_failure");
            throw e;
        }

        // 取配置预期值与实际活动数的较大者：配置值决定容量基线（T5 修复后生效），
        // 活动数超过配置值时不至于因容量不足而误判率飙升；Guava 要求 > 0
        int expectedInsertions = Math.max(activeDeals.size(), Math.max(properties.getExpectedInsertions(), 1));
        BloomFilter<Long> newFilter =
                BloomFilter.create(Funnels.longFunnel(), expectedInsertions, properties.getFpp());
        activeDeals.forEach(d -> newFilter.put(d.getVoucherId()));

        this.bloomFilter = newFilter;
        if (stringRedisTemplate != null) {
            stringRedisTemplate.delete(COMMITTED_DEALS_KEY);
            activeDeals.stream().map(FlashDeal::getVoucherId)
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .forEach(id -> stringRedisTemplate.opsForSet().add(COMMITTED_DEALS_KEY, id));
        }
        metric("rebuild");

        LocalDateTime now = LocalDateTime.now();
        this.stats = BloomFilterStats.builder()
                .approximateElementCount(newFilter.approximateElementCount())
                .expectedFpp(properties.getFpp())
                .estimatedMemoryBytes(expectedInsertions * 10L)  // 近似：每元素 ~10 bytes
                .lastRebuildTime(now)
                .uptimeHours(ChronoUnit.HOURS.between(now, LocalDateTime.now()) + 1)
                .build();

        log.info("Bloom filter rebuilt: size={}, expectedFpp={}, activeDeals={}",
                activeDeals.size(), properties.getFpp(), activeDeals.size());
    }

    // === 核心方法：O(1) 时间复杂度 ===
    @Override
    public boolean mightContain(Long dealId) {
        if (dealId == null) {
            return false;
        }
        if (bloomFilter.mightContain(dealId)) return true;
        // A small shared exact set closes the multi-instance admission gap
        // between a committed write and the next periodic Bloom rebuild.
        if (stringRedisTemplate != null
                && Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(COMMITTED_DEALS_KEY, dealId.toString()))) {
            bloomFilter.put(dealId);
            metric("shared_hit");
            return true;
        }
        return false;
    }

    @Override
    public synchronized void registerCommitted(Long dealId) {
        if (dealId != null) {
            bloomFilter.put(dealId);
            if (stringRedisTemplate != null) {
                stringRedisTemplate.opsForSet().add(COMMITTED_DEALS_KEY, dealId.toString());
            }
            metric("register");
        }
    }

    @Override
    public BloomFilterStats getStats() {
        return stats;
    }

    private void metric(String outcome) {
        if (reliabilityMetrics != null) {
            reliabilityMetrics.increment("campusdeal.bloom", outcome);
        }
    }
}
