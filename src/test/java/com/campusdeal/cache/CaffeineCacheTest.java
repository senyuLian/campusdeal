package com.campusdeal.cache;

import com.campusdeal.entity.Merchant;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Caffeine 本地缓存单元测试
 * <p>纯内存缓存，不需要 Redis / 数据库。</p>
 */
class CaffeineCacheTest {

    @Test
    @DisplayName("CF-01: 缓存命中")
    void shouldHitCache() {
        Cache<Long, Boolean> cache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.SECONDS)
                .build();
        cache.put(1L, true);

        assertThat(cache.getIfPresent(1L)).isTrue();
    }

    @Test
    @DisplayName("CF-02: TTL 过期后缓存 miss")
    void shouldExpireAfterTTL() throws InterruptedException {
        Cache<Long, Boolean> cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(200))
                .build();
        cache.put(1L, true);
        assertThat(cache.getIfPresent(1L)).isTrue();

        Thread.sleep(250);
        assertThat(cache.getIfPresent(1L)).isNull();
    }

    @Test
    @DisplayName("CF-03: 缓存 miss 时通过 loader 加载")
    void shouldLoadOnMiss() {
        Cache<Long, Boolean> cache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.SECONDS)
                .maximumSize(100)
                .build();

        // miss → 调用 loader 从"Redis"读取（此处用简单函数模拟）
        Boolean hasStock = cache.get(42L, key -> Boolean.TRUE);

        assertThat(hasStock).isTrue();
        assertThat(cache.getIfPresent(42L)).isTrue();  // 已回填
    }

    @Test
    @DisplayName("CF-04: 超过容量后触发淘汰")
    void shouldEvictBeyondCapacity() {
        Cache<Long, Boolean> cache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .maximumSize(100)
                .build();

        IntStream.range(0, 101).forEach(i -> cache.put((long) i, true));

        // Caffeine 淘汰是异步的（maintenance），cleanUp() 强制同步执行淘汰
        cache.cleanUp();

        // W-TinyLFU 淘汰：大小不会超过容量上限
        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("CF-05: stockCache Bean 工厂方法正确配置 TTL 与容量")
    void shouldBuildStockCacheFromConfig() {
        CaffeineConfig config = new CaffeineConfig();
        CacheProperties props = new CacheProperties();
        props.setStockTtlSeconds(1);
        props.setStockMaxSize(100);

        Cache<Long, Boolean> stockCache = config.stockCache(props);
        stockCache.put(5L, true);

        assertThat(stockCache.getIfPresent(5L)).isTrue();
        assertThat(stockCache.policy().expireAfterWrite()).isPresent();
    }

    @Test
    @DisplayName("CF-06: merchantCache Bean 工厂方法正确配置")
    void shouldBuildMerchantCacheFromConfig() {
        CaffeineConfig config = new CaffeineConfig();
        CacheProperties props = new CacheProperties();
        props.setMerchantTtlSeconds(30);
        props.setMerchantMaxSize(1000);

        Cache<Long, Merchant> merchantCache = config.merchantCache(props);
        Merchant merchant = new Merchant();
        merchant.setId(7L);
        merchant.setName("食堂A");
        merchantCache.put(7L, merchant);

        assertThat(merchantCache.getIfPresent(7L)).isNotNull();
        assertThat(merchantCache.getIfPresent(7L).getName()).isEqualTo("食堂A");
    }
}
