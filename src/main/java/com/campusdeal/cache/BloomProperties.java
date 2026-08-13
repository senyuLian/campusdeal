package com.campusdeal.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * 布隆过滤器配置
 */
@Data
@ConfigurationProperties(prefix = "campusdeal.bloom")
public class BloomProperties {

    /** 预期插入元素数量，默认 10000 */
    private int expectedInsertions = 10000;

    /** 误判率，默认 0.01 (1%) */
    private double fpp = 0.01;

    /** 定时重建周期（秒），默认 300（5 分钟） */
    private int rebuildIntervalSeconds = 300;
}
