package com.campusdeal.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RL-01..06：Redis 令牌桶限流测试（Mock StringRedisTemplate）。
 *
 * <p>Lua 脚本被 mock，因此"令牌恢复/超限"通过 execute 的返回值序列来模拟。</p>
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private SecurityProperties securityProperties;

    @InjectMocks
    private RateLimiterImpl rateLimiter;

    @Test
    @DisplayName("RL-01 正常请求：有令牌时放行")
    void rl01_acquireAllowed() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(5L);

        assertTrue(rateLimiter.tryAcquire(1001L));
    }

    @Test
    @DisplayName("RL-02 连续 11 次：第 11 次被限流")
    void rl02_exhaustedAfterEleven() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, -1L);

        boolean last = true;
        for (int i = 0; i < 11; i++) {
            last = rateLimiter.tryAcquire(1001L);
        }
        assertFalse(last);
    }

    @Test
    @DisplayName("RL-03 令牌恢复：被限流后间隔一段时间又可获取")
    void rl03_tokenRefill() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(-1L)
                .thenReturn(5L);

        assertFalse(rateLimiter.tryAcquire(1001L));
        assertTrue(rateLimiter.tryAcquire(1001L));
    }

    @Test
    @DisplayName("RL-04 不同用户独立计数")
    void rl04_independentPerUser() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(5L);

        assertTrue(rateLimiter.tryAcquire(1001L));
        assertTrue(rateLimiter.tryAcquire(1002L));

        // 两个请求使用的 Redis key 不同（ratelimit:user:1001 / :1002）
        ArgumentCaptor<List<String>> keyCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate, times(2))
                .execute(any(RedisScript.class), keyCaptor.capture(), any(), any(), any());
        List<List<String>> allKeys = keyCaptor.getAllValues();
        assertTrue(allKeys.get(0).get(0).endsWith(":1001"));
        assertTrue(allKeys.get(1).get(0).endsWith(":1002"));
    }

    @Test
    @DisplayName("RL-05 匿名 userId=null：直接放行，不触碰 Redis")
    void rl05_anonymousBypass() {
        assertTrue(rateLimiter.tryAcquire(null));
        verify(stringRedisTemplate, never())
                .execute(any(RedisScript.class), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("RL-06 T6：rate-limit-per-minute 配置接线，容量取配置值而非硬编码 10")
    void rl06_configurableCapacity() {
        when(securityProperties.getRateLimitPerMinute()).thenReturn(3);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(2L);

        rateLimiter.tryAcquire(1001L);

        ArgumentCaptor<String> argCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).execute(
                any(RedisScript.class), anyList(), argCaptor.capture(), argCaptor.capture(), argCaptor.capture());
        // 三个 varargs：时间戳 / capacity / 补充间隔；第 2 个应是配置的 3
        assertEquals("3", argCaptor.getAllValues().get(1));
    }
}
