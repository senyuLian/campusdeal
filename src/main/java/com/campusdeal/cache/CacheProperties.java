package com.campusdeal.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Caffeine 缓存配置
 */
@Data
@ConfigurationProperties(prefix = "campusdeal.cache")
public class CacheProperties {

    /** stock 缓存 TTL（秒），默认 1 */
    private int stockTtlSeconds = 1;

    /** stock 缓存最大条目数，默认 10000 */
    private int stockMaxSize = 10000;

    /** merchant 缓存 TTL（秒），默认 30 */
    private int merchantTtlSeconds = 30;

    /** merchant 缓存最大条目数，默认 1000 */
    private int merchantMaxSize = 1000;
}
