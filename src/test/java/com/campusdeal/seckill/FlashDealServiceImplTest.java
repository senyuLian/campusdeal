package com.campusdeal.seckill;

import com.campusdeal.cache.BloomFilterService;
import com.campusdeal.dto.Result;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.mq.FlashDealConsumer;
import com.campusdeal.mq.FlashDealOrderMessage;
import com.campusdeal.mq.FlashDealProducer;
import com.campusdeal.mq.OutboxStatus;
import com.campusdeal.service.OutboxService;
import com.campusdeal.service.impl.FlashDealServiceImpl;
import com.campusdeal.utils.RedisConstants;
import com.campusdeal.utils.RedisIdWorker;
import com.campusdeal.utils.UserHolder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FlashDealServiceImpl 单元测试（FD-01..05）
 *
 * <p>全部 Mock（布隆过滤器、Redis、ID 生成器），Caffeine 使用真实内存缓存，
 * 不依赖外部服务。</p>
 */
@ExtendWith(MockitoExtension.class)
class FlashDealServiceImplTest {

    @Mock private BloomFilterService bloomFilter;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private RedisIdWorker redisIdWorker;
    @Mock private DefaultRedisScript<Long> flashDealScript;
    @Mock private FlashDealProducer flashDealProducer;
    @Mock private FlashDealConsumer flashDealConsumer;
    @Mock private OutboxService outboxService;

    @InjectMocks private FlashDealServiceImpl service;

    /** 真实 Caffeine 缓存（内存，不需要 mock） */
    private Cache<Long, Boolean> stockCache;

    @BeforeEach
    void setUp() {
        stockCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(1))
                .maximumSize(100)
                .build();
        ReflectionTestUtils.setField(service, "stockCache", stockCache);

        // lenient：FD-01 布隆拒绝时完全不触 Redis（不能因未使用的桩报错）
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        // T14 时间窗检查会额外 get(flashdeal:time:*)——无桩时返回 null（跳过时间窗），
        // 并避免与库存 get 的严格桩产生 PotentialStubbingProblem
        lenient().when(valueOps.get(anyString())).thenReturn(null);

        UserDTO mockUser = new UserDTO();
        mockUser.setId(1001L);
        UserHolder.saveUser(mockUser);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    @DisplayName("FD-01: 布隆过滤拒绝，不查缓存也不调 Lua")
    void shouldRejectOnBloomMiss() {
        when(bloomFilter.mightContain(999L)).thenReturn(false);

        Result result = service.executeFlashDeal(999L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("not found");
        verifyNoInteractions(stringRedisTemplate);  // 不调 Redis
    }

    @Test
    @DisplayName("FD-02: L1 缓存命中无库存，不调 Lua")
    void shouldRejectWhenCaffeineIndicatesNoStock() {
        when(bloomFilter.mightContain(101L)).thenReturn(true);
        stockCache.put(101L, false);  // 无库存

        Result result = service.executeFlashDeal(101L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("Out of stock");
        // 时间窗检查会读 Redis，但绝不执行 Lua 脚本（L1 拦截在 Lua 之前）
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    @DisplayName("FD-03: Lua 返回成功，同步落库 + 返回 orderId 字符串（P2-6）")
    void shouldReturnOrderIdOnSuccess() {
        when(bloomFilter.mightContain(101L)).thenReturn(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(5L);  // T16: >=0 = 成功（值=剩余库存）
        when(redisIdWorker.getNextId("order")).thenReturn(20260812000001L);

        Result result = service.executeFlashDeal(101L);

        assertThat(result.getSuccess()).isTrue();
        // P2-6：雪花 ID 超 JS 精度 → 以字符串下发
        assertThat(result.getData()).isEqualTo("20260812000001");
        verify(redisIdWorker).getNextId("order");
        // 秒杀成功 → 同步落库（Mock Consumer 直接吞掉） + 尽力而为发 Kafka
        verify(flashDealConsumer).processMessage(any(FlashDealOrderMessage.class));
        verify(flashDealProducer).send(any(FlashDealOrderMessage.class));
    }

    @Test
    @DisplayName("T16: Lua 返回剩余库存=0 → 预置 L1 负缓存（耗尽后首波即 0 Redis 命中）")
    void shouldCacheL1FalseWhenRemainingStockZero() {
        when(bloomFilter.mightContain(101L)).thenReturn(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(0L);  // 剩余库存 = 0（最后一单）
        when(redisIdWorker.getNextId("order")).thenReturn(20260812000004L);

        Result result = service.executeFlashDeal(101L);

        assertThat(result.getSuccess()).isTrue();
        // 最后一单售罄 → L1 立即写 false，S2 耗尽压测从第一波即快速失败
        assertThat(stockCache.getIfPresent(101L)).isFalse();
        // 后续请求走 L1 直接拒绝，不执行 Lua（Lua 仅在第一次成功时执行 1 次）
        Result second = service.executeFlashDeal(101L);
        assertThat(second.getSuccess()).isFalse();
        assertThat(second.getErrorMsg()).contains("Out of stock");
        verify(stringRedisTemplate, times(1)).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    @DisplayName("SC-02: 同步落库失败 → 写 Outbox PENDING，秒杀仍成功")
    void shouldRecordOutboxWhenSyncPersistFails() {
        when(bloomFilter.mightContain(101L)).thenReturn(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(5L);  // T16: >=0 = 成功（值=剩余库存）
        when(redisIdWorker.getNextId("order")).thenReturn(20260812000002L);
        doThrow(new RuntimeException("db down"))
                .when(flashDealConsumer).processMessage(any(FlashDealOrderMessage.class));

        Result result = service.executeFlashDeal(101L);

        assertThat(result.getSuccess()).isTrue();
        // 补偿：写 Outbox PENDING（sync-{orderId}），由调度器重试
        verify(outboxService).record(eq("sync-20260812000002"), anyString(), eq(OutboxStatus.PENDING));
    }

    @Test
    @DisplayName("FD-04: Lua 返回库存不足，更新 Caffeine 缓存")
    void shouldRejectWhenLuaOutOfStock() {
        when(bloomFilter.mightContain(101L)).thenReturn(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(-1L);  // T16: -1 = 库存不足

        Result result = service.executeFlashDeal(101L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("Out of stock");
        // 无库存结果回填本地缓存，后续请求走 L1 直接拒绝
        assertThat(stockCache.getIfPresent(101L)).isFalse();
    }

    @Test
    @DisplayName("FD-05: Lua 返回重复下单")
    void shouldRejectDuplicateOrder() {
        when(bloomFilter.mightContain(101L)).thenReturn(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(-2L);  // T16: -2 = 重复下单

        Result result = service.executeFlashDeal(101L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("Already purchased");
    }

    // ==================== T14：活动时间窗 ====================

    private String timeWindow(long beginMillis, long endMillis) {
        return beginMillis + "|" + endMillis;
    }

    @Test
    @DisplayName("TW-01: 未开始（beginTime 在未来）→ 拒绝，不执行 Lua")
    void shouldRejectWhenDealNotStarted() {
        when(bloomFilter.mightContain(101L)).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        long future = System.currentTimeMillis() + 3600_000;
        when(valueOps.get(RedisConstants.FLASH_DEAL_TIME_KEY + "101"))
                .thenReturn(timeWindow(future, future + 3600_000));

        Result result = service.executeFlashDeal(101L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("尚未开始");
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    @DisplayName("TW-02: 已结束（endTime 已过）→ 拒绝，不执行 Lua")
    void shouldRejectWhenDealEnded() {
        when(bloomFilter.mightContain(101L)).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        long past = System.currentTimeMillis() - 3600_000;
        when(valueOps.get(RedisConstants.FLASH_DEAL_TIME_KEY + "101"))
                .thenReturn(timeWindow(past - 3600_000, past));

        Result result = service.executeFlashDeal(101L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("已经结束");
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    @DisplayName("TW-03: 进行中（时间窗内）→ 正常秒杀")
    void shouldAllowWhenDealInWindow() {
        when(bloomFilter.mightContain(101L)).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        long now = System.currentTimeMillis();
        when(valueOps.get(RedisConstants.FLASH_DEAL_TIME_KEY + "101"))
                .thenReturn(timeWindow(now - 1000, now + 3600_000));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(5L);  // T16: >=0 = 成功
        when(redisIdWorker.getNextId("order")).thenReturn(20260812000003L);

        Result result = service.executeFlashDeal(101L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("20260812000003");
    }
}
