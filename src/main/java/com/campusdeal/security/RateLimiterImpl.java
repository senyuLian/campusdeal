package com.campusdeal.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Collections;

/**
 * Redis 令牌桶限流实现。
 *
 * <p>为什么不用 Guava RateLimiter？因为分布式部署下多个实例需共享计数——Guava 是 JVM 内的，
 * 实例 A 不知道实例 B 消耗了几个令牌。Redis Lua 原子脚本保证分布式一致性，单次操作 &lt; 1ms。</p>
 */
@Slf4j
@Component
public class RateLimiterImpl implements RateLimiter {

    private static final String RATE_LIMIT_KEY = "ratelimit:user:";
    private static final int DEFAULT_CAPACITY = 10;   // 每分钟 10 次
    private static final long REFILL_INTERVAL_MS = 60_000;

    /**
     * 令牌桶 Lua 脚本（原子执行）。
     *
     * <p>KEYS[1]: ratelimit:user:{userId}
     * ARGV[1]: 当前时间（毫秒）
     * ARGV[2]: 令牌桶容量
     * ARGV[3]: 令牌补充间隔（毫秒）
     *
     * <p>返回：剩余令牌数；-1 = 无令牌（被限流）。</p>
     */
    private static final String TOKEN_BUCKET_LUA = """
        local key = KEYS[1]
        local now = tonumber(ARGV[1])
        local capacity = tonumber(ARGV[2])
        local interval = tonumber(ARGV[3])

        local lastRefill = tonumber(redis.call('HGET', key, 'lastRefill') or '0')
        local tokens = tonumber(redis.call('HGET', key, 'tokens') or capacity)

        -- 计算需要补充的令牌
        local elapsed = now - lastRefill
        local refill = math.floor(elapsed / interval * capacity)

        if refill > 0 then
            tokens = math.min(capacity, tokens + refill)
            redis.call('HSET', key, 'lastRefill', now)
        end

        if tokens > 0 then
            redis.call('HSET', key, 'tokens', tokens - 1)
            redis.call('EXPIRE', key, 120)
            return tokens - 1
        else
            return -1
        end
        """;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final DefaultRedisScript<Long> tokenBucketScript;

    public RateLimiterImpl() {
        tokenBucketScript = new DefaultRedisScript<>();
        tokenBucketScript.setScriptText(TOKEN_BUCKET_LUA);
        tokenBucketScript.setResultType(Long.class);
    }

    @Override
    public boolean tryAcquire(Long userId) {
        if (userId == null) {
            return true;
        }
        String key = RATE_LIMIT_KEY + userId;
        Long result = stringRedisTemplate.execute(tokenBucketScript,
                Collections.singletonList(key),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(DEFAULT_CAPACITY),
                String.valueOf(REFILL_INTERVAL_MS));
        return result != null && result >= 0;
    }

    @Override
    public long availableTokens(Long userId) {
        String key = RATE_LIMIT_KEY + userId;
        String tokens = (String) stringRedisTemplate.opsForHash().get(key, "tokens");
        return tokens != null ? Long.parseLong(tokens) : DEFAULT_CAPACITY;
    }
}
