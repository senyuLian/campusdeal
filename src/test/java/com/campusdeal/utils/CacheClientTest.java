package com.campusdeal.utils;

import cn.hutool.json.JSONUtil;
import com.campusdeal.config.ReliabilityMetrics;
import com.campusdeal.entity.Merchant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.campusdeal.utils.RedisConstants.CACHE_MERCHANT_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CacheClient 单元测试：穿透（空值缓存 PT）、击穿（逻辑过期 LE）。
 * <p>纯 Mock Redis，验证缓存读写与降级路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class CacheClientTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private Merchant merchant(Long id, String name) {
        return new Merchant().setId(id).setName(name);
    }

    /** 构造逻辑过期包装 JSON：data + 指定 expireTime */
    private String logicalExpireJson(Object data, LocalDateTime expire) {
        RedisData rd = new RedisData();
        rd.setData(data);
        rd.setExpireTime(expire);
        return JSONUtil.toJsonStr(rd);
    }

    private String key(Long id) {
        return CACHE_MERCHANT_KEY + id;
    }

    // ==================== 穿透防御：空值缓存 ====================

    @Test
    @DisplayName("PT-01: 命中缓存直接返回，不穿透 DB")
    void pt01_cacheHit() {
        when(valueOps.get(key(1L))).thenReturn(JSONUtil.toJsonStr(merchant(1L, "食堂")));
        Function<Long, Merchant> db = mock(Function.class);

        Merchant r = cacheClient.queryWithPassThrough(1L, CACHE_MERCHANT_KEY, Merchant.class, db, 30L, TimeUnit.MINUTES);

        assertThat(r.getName()).isEqualTo("食堂");
        verify(db, never()).apply(any());
    }

    @Test
    @DisplayName("PT-02: 缓存未命中且 DB 有 → 写缓存并返回")
    void pt02_missDbHit() {
        when(valueOps.get(key(1L))).thenReturn(null);
        Function<Long, Merchant> db = mock(Function.class);
        when(db.apply(1L)).thenReturn(merchant(1L, "食堂"));

        Merchant r = cacheClient.queryWithPassThrough(1L, CACHE_MERCHANT_KEY, Merchant.class, db, 30L, TimeUnit.MINUTES);

        assertThat(r.getName()).isEqualTo("食堂");
        verify(valueOps).set(eq(key(1L)), org.mockito.ArgumentMatchers.contains("食堂"), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("PT-03: 缓存未命中且 DB 无 → 写空值 TTL 并返回 null")
    void pt03_missDbMiss() {
        when(valueOps.get(key(1L))).thenReturn(null);
        Function<Long, Merchant> db = mock(Function.class);
        when(db.apply(1L)).thenReturn(null);

        Merchant r = cacheClient.queryWithPassThrough(1L, CACHE_MERCHANT_KEY, Merchant.class, db, 30L, TimeUnit.MINUTES);

        assertThat(r).isNull();
        verify(valueOps).set(eq(key(1L)), eq(""), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("PT-04: 空值缓存命中 → 快速返回 null，不穿透 DB")
    void pt04_nullValueHit() {
        when(valueOps.get(key(1L))).thenReturn("");
        Function<Long, Merchant> db = mock(Function.class);

        Merchant r = cacheClient.queryWithPassThrough(1L, CACHE_MERCHANT_KEY, Merchant.class, db, 30L, TimeUnit.MINUTES);

        assertThat(r).isNull();
        verify(db, never()).apply(any());
    }

    @Test
    @DisplayName("PT-05: 竞争锁时只进行有界重试，最终回源一次")
    void pt05_lockContentionIsBounded() {
        when(valueOps.get(key(2L))).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);
        Function<Long, Merchant> db = mock(Function.class);
        when(db.apply(2L)).thenReturn(merchant(2L, "食堂"));

        Merchant result = cacheClient.queryWithPassThrough(2L, CACHE_MERCHANT_KEY, Merchant.class,
                db, 30L, TimeUnit.MINUTES);

        assertThat(result.getName()).isEqualTo("食堂");
        verify(db).apply(2L);
    }

    // ==================== 击穿防御：逻辑过期 ====================

    @Test
    @DisplayName("LE-01: 未过期命中 → 返回缓存，不触发重建")
    void le01_notExpiredHit() {
        when(valueOps.get(key(1L))).thenReturn(
                logicalExpireJson(merchant(1L, "食堂"), LocalDateTime.now().plusMinutes(30)));
        Function<Long, Merchant> db = mock(Function.class);

        Merchant r = cacheClient.queryWithLogicalExpire(1L, CACHE_MERCHANT_KEY, Merchant.class, db, 30L, TimeUnit.MINUTES);

        assertThat(r.getName()).isEqualTo("食堂");
        verify(db, never()).apply(any());
    }

    @Test
    @DisplayName("LE-02: 未预热回源 → DB 重建逻辑过期缓存并返回")
    void le02_missRebuildFromDb() {
        when(valueOps.get(key(1L))).thenReturn(null);
        Function<Long, Merchant> db = mock(Function.class);
        when(db.apply(1L)).thenReturn(merchant(1L, "食堂"));

        Merchant r = cacheClient.queryWithLogicalExpire(1L, CACHE_MERCHANT_KEY, Merchant.class, db, 30L, TimeUnit.MINUTES);

        assertThat(r.getName()).isEqualTo("食堂");
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        // setWithLogicalExpire 使用 2 参 set(key, value) 重载
        verify(valueOps).set(eq(key(1L)), cap.capture());
        // 写入的是逻辑过期包装（含 expireTime），非裸 JSON
        assertThat(cap.getValue()).contains("expireTime");
    }

    @Test
    @DisplayName("LE-03: 已过期 + 获锁 → 返回旧值，后台线程重建并释放锁")
    void le03_expiredRebuildAsync() throws InterruptedException {
        when(valueOps.get(key(1L))).thenReturn(
                logicalExpireJson(merchant(1L, "旧食堂"), LocalDateTime.now().minusSeconds(1)));
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        Function<Long, Merchant> db = mock(Function.class);
        when(db.apply(1L)).thenReturn(merchant(1L, "新食堂"));

        Merchant r = cacheClient.queryWithLogicalExpire(1L, CACHE_MERCHANT_KEY, Merchant.class, db, 30L, TimeUnit.MINUTES);

        // 读线程拿到旧值，不等待重建
        assertThat(r.getName()).isEqualTo("旧食堂");

        // 等待后台重建线程完成，断言发生了重建与解锁
        Thread.sleep(500);
        verify(db).apply(1L);
        // Lock release uses the compare-and-delete Lua script on a dedicated
        // rebuild lock key; deleting the cached data key would be unsafe when
        // a lease has expired and another worker owns the successor lock.
        verify(stringRedisTemplate).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(java.util.Collections.singletonList("lock:cache:rebuild:" + key(1L))), any());
    }

    @Test
    @DisplayName("LE-04: 重建失败（DB 异常）→ 返回旧值，调用方不感知异常")
    void le04_rebuildFailureDegrades() throws InterruptedException {
        when(valueOps.get(key(1L))).thenReturn(
                logicalExpireJson(merchant(1L, "旧食堂"), LocalDateTime.now().minusSeconds(1)));
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        Function<Long, Merchant> db = mock(Function.class);
        when(db.apply(1L)).thenThrow(new RuntimeException("db down"));

        Merchant r = cacheClient.queryWithLogicalExpire(1L, CACHE_MERCHANT_KEY, Merchant.class, db, 30L, TimeUnit.MINUTES);

        assertThat(r.getName()).isEqualTo("旧食堂");

        // 等待后台线程真正触达失败点（否则 Mockito 严格桩检测可能早于异步执行判定“未使用”）
        Thread.sleep(500);
        verify(db).apply(1L);
    }

    @Test
    @DisplayName("LE-M1: 逻辑过期重建失败记录固定标签且不包含缓存内容")
    void le05_rebuildFailureRecordsTelemetry() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(cacheClient, "reliabilityMetrics", new ReliabilityMetrics(registry));
        when(valueOps.get(key(1L))).thenReturn(
                logicalExpireJson(merchant(1L, "旧食堂"), LocalDateTime.now().minusSeconds(1)));
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        Function<Long, Merchant> db = mock(Function.class);
        when(db.apply(1L)).thenThrow(new RuntimeException("sensitive prompt body"));

        cacheClient.queryWithLogicalExpire(1L, CACHE_MERCHANT_KEY, Merchant.class, db, 30L, TimeUnit.MINUTES);
        Thread.sleep(500);

        assertThat(registry.get("campusdeal.cache.query")
                .tag("outcome", "rebuild_started").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("campusdeal.cache.query")
                .tag("outcome", "rebuild_failure").counter().count()).isEqualTo(1.0);
    }
}
