package com.campusdeal.cache;

/**
 * 布隆过滤器服务
 *
 * 作用：秒杀请求入口的第一道防线，快速拒绝非法 dealId（不存在的秒杀活动 ID），
 * 拦截 80%+ 的无效请求，保护下游 Redis / MySQL。
 */
public interface BloomFilterService {

    /**
     * 检查某个秒杀活动 ID 是否可能存在
     *
     * @param dealId 秒杀活动 ID
     * @return false = 一定不存在（可直接拒绝），true = 可能存在（继续后续校验）
     */
    boolean mightContain(Long dealId);

    /** Register a newly committed deal without waiting for the periodic rebuild. */
    default void registerCommitted(Long dealId) {
        // Implementations that maintain an in-memory admission index override this.
    }

    /**
     * 获取当前布隆过滤器的统计信息（用于监控和面试展示）
     */
    BloomFilterStats getStats();
}
