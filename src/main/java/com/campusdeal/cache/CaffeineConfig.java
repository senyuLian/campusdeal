package com.campusdeal.cache;

import com.campusdeal.entity.Merchant;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置（L1 缓存）
 *
 * <ul>
 *   <li>stockCache：缓存秒杀"库存是否充足"的布尔标记，TTL 1s</li>
 *   <li>merchantCache：缓存商户热点数据，TTL 30s</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties({CacheProperties.class, BloomProperties.class})
public class CaffeineConfig {

    /**
     * 秒杀库存标记缓存
     * 键：dealId；值：true=有库存, false=无库存
     */
    @Bean
    public Cache<Long, Boolean> stockCache(CacheProperties props) {
        return Caffeine.newBuilder()
                .expireAfterWrite(props.getStockTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(props.getStockMaxSize())
                .recordStats()  // 开启统计，供 Actuator 暴露命中率
                .removalListener((key, value, cause) ->
                        log.debug("Stock cache removal: dealId={}, cause={}", key, cause))
                .build();
    }

    /**
     * 商户热点数据缓存
     * 键：merchantId；值：Merchant 实体
     */
    @Bean
    public Cache<Long, Merchant> merchantCache(CacheProperties props) {
        return Caffeine.newBuilder()
                .expireAfterWrite(props.getMerchantTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(props.getMerchantMaxSize())
                .recordStats()
                .build();
    }
}
