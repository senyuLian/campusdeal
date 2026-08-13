package com.campusdeal.service;

import com.campusdeal.service.impl.IdempotentServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 幂等服务单元测试（ID-01..03）
 *
 * <p>Mock StringRedisTemplate，不依赖真实 Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
class IdempotentServiceTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks private IdempotentServiceImpl service;

    private void mockOps() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("ID-01: 首次标记返回 true")
    void shouldMarkFirstTime() {
        mockOps();
        when(valueOps.setIfAbsent("order:dedup:1001:5", "1", 1L, TimeUnit.HOURS))
                .thenReturn(true);

        assertThat(service.tryMark("order:dedup:1001:5")).isTrue();
    }

    @Test
    @DisplayName("ID-02: 重复标记返回 false")
    void shouldRejectDuplicate() {
        mockOps();
        when(valueOps.setIfAbsent("order:dedup:1001:5", "1", 1L, TimeUnit.HOURS))
                .thenReturn(false);

        assertThat(service.tryMark("order:dedup:1001:5")).isFalse();
    }

    @Test
    @DisplayName("ID-03: SETNX 携带 1 小时 TTL，过期后重新可标记")
    void shouldSetTtlForDedupKey() {
        mockOps();
        // Redis 端保证 TTL 过期后 key 消失，此时 setIfAbsent 再次返回 true
        when(valueOps.setIfAbsent("order:dedup:1001:5", "1", 1L, TimeUnit.HOURS))
                .thenReturn(true);

        assertThat(service.tryMark("order:dedup:1001:5")).isTrue();

        // 验证 TTL 参数确实以 1 小时传入（幂等标记自动过期）
        verify(valueOps).setIfAbsent(eq("order:dedup:1001:5"), eq("1"), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("ID-04: clearMark 删除去重 key")
    void shouldClearMark() {
        service.clearMark("order:dedup:1001:5");

        verify(stringRedisTemplate).delete("order:dedup:1001:5");
    }
}
