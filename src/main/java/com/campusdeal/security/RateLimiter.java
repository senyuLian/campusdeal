package com.campusdeal.security;

/**
 * 基于 Redis 令牌桶的限流（防 API 滥用，分布式共享计数）。
 */
public interface RateLimiter {

    /**
     * 尝试获取一个令牌。
     *
     * @param userId 用户 ID
     * @return true = 允许请求，false = 超出限流
     */
    boolean tryAcquire(Long userId);

    /**
     * 获取当前用户剩余的令牌数。
     */
    long availableTokens(Long userId);
}
