package com.campusdeal.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisIdWorker 单元测试（ID-01..02）
 *
 * <p>Mock StringRedisTemplate.opsForValue().increment()，纯单元测试。</p>
 */
@ExtendWith(MockitoExtension.class)
class RedisIdWorkerTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks private RedisIdWorker redisIdWorker;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("ID-01: 连续生成 ID 单调递增")
    void shouldGenerateMonotonicIncreasingIds() {
        when(valueOps.increment(anyString())).thenReturn(0L, 1L, 2L);

        long id1 = redisIdWorker.getNextId("order");
        long id2 = redisIdWorker.getNextId("order");
        long id3 = redisIdWorker.getNextId("order");

        // 时间戳(32bit) << 32 | 序列号(32bit)，序列号递增保证 ID 单调递增
        assertThat(id1).isLessThan(id2);
        assertThat(id2).isLessThan(id3);
    }

    @Test
    @DisplayName("ID-02: 不同业务前缀的序列独立")
    void shouldKeepPrefixesIndependent() {
        // 按 Redis key 独立计数，模拟真实 Redis INCR 行为
        Map<String, Long> counters = new HashMap<>();
        when(valueOps.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long next = counters.getOrDefault(key, -1L) + 1;
            counters.put(key, next);
            return next;
        });

        long o1 = redisIdWorker.getNextId("order");
        long o2 = redisIdWorker.getNextId("order");
        long r1 = redisIdWorker.getNextId("refund");
        long r2 = redisIdWorker.getNextId("refund");

        assertThat(o1).isLessThan(o2);
        assertThat(r1).isLessThan(r2);
        // 两个业务前缀使用不同的 Redis 递增 key，且各自独立计数
        verify(valueOps, times(2)).increment(contains("icr:order:"));
        verify(valueOps, times(2)).increment(contains("icr:refund:"));
    }
}
