package com.campusdeal.cache;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 布隆过滤器统计信息（用于监控和面试展示）
 */
@Data
@Builder
public class BloomFilterStats {

    /** 当前存储的元素数量（近似值） */
    private long approximateElementCount;

    /** 预期误判率 */
    private double expectedFpp;

    /** 内存占用（字节，近似值） */
    private long estimatedMemoryBytes;

    /** 最近一次重建时间 */
    private LocalDateTime lastRebuildTime;

    /** 已运行小时数 */
    private long uptimeHours;
}
