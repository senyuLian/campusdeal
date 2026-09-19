package com.campusdeal.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.campusdeal.entity.Merchant;
import com.campusdeal.config.ReliabilityMetrics;
import com.campusdeal.security.SensitiveLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.annotation.Resource;
import java.security.KeyPair;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.function.Function;

import static com.campusdeal.utils.RedisConstants.*;

@Slf4j
@Component
/**
 * 缓存工具类
 * 解决缓存穿透：缓存空值
 * 解决缓存击穿：逻辑过期
 */
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private ReliabilityMetrics reliabilityMetrics;



    private static final ExecutorService CACHE_REBUILDER_EXECUTOR = Executors.newFixedThreadPool(10);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 将任意对象转化为StringJSON存入Redis,设置TTL过期
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        this.stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将任意对象转化为StringJSON存入Redis,设置逻辑过期，用于解决缓存击穿问题。
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        this.stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透解决方法：缓存空值
     * @param id
     * @param keyPrefix
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return 缓存结果
     */
    public <R, ID> R queryWithPassThrough(ID id, String keyPrefix, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String cacheKey = keyPrefix + id;
        for (int attempt = 0; attempt < 3; attempt++) {
            String rJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StringUtils.isNotBlank(rJson)) {
                try {
                    return JSONUtil.toBean(rJson, type);
                } catch (Exception malformed) {
                    // A corrupted cache entry must not become a permanent
                    // 500 or an unbounded retry loop. Treat it as a miss and
                    // let the coordinated loader repair the value.
                    metric("malformed");
                    stringRedisTemplate.delete(cacheKey);
                    rJson = null;
                }
            }
            if (rJson != null) {
                return null;
            }
            String lockKey = LOCK_CACHE_REBUILD_KEY + cacheKey;
            String token = UUID.randomUUID().toString();
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, token, 10L, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(acquired)) {
                try {
                    // Recheck after taking the lock so only the winner hits MySQL.
                    rJson = stringRedisTemplate.opsForValue().get(cacheKey);
                    if (StringUtils.isNotBlank(rJson)) {
                        return JSONUtil.toBean(rJson, type);
                    }
                    if (rJson != null) {
                        return null;
                    }
                    R r = dbFallback.apply(id);
                    if (r == null) {
                        stringRedisTemplate.opsForValue().set(cacheKey, "", time, unit);
                        return null;
                    }
                    stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(r), time, unit);
                    return r;
                } finally {
                    releaseLock(lockKey, token);
                }
            }
            metric("lock_contention");
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(15L, 45L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        // Redis itself may be unavailable.  One bounded fallback keeps this
        // helper useful during recovery without an unbounded retry loop.
        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(cacheKey, "", time, unit);
        } else {
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(r), time, unit);
        }
        return r;
    }


    /**
     * 逻辑过期解决缓存击穿问题
     * @param id
     * @param keyPrefix
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return 缓存结果
     */
    public <R, ID> R queryWithLogicalExpire(ID id, String keyPrefix, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String cacheKey = keyPrefix + id;
        String rJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (rJson != null && rJson.isBlank()) {
            // A confirmed negative sentinel is a cache hit and must not
            // repeatedly query the database during its short TTL.
            metric("negative_hit");
            return null;
        }
        if (rJson == null) {
            // Coordinate cold loads just like pass-through reads. Without a
            // distinct lock key, a popular key can fan out into one DB query
            // per caller after Redis loss.
            String lockKey = LOCK_CACHE_REBUILD_KEY + cacheKey;
            for (int attempt = 0; attempt < 3; attempt++) {
                String token = UUID.randomUUID().toString();
                if (tryLock(lockKey, token)) {
                    try {
                        rJson = stringRedisTemplate.opsForValue().get(cacheKey);
                        if (rJson != null) {
                            if (rJson.isBlank()) return null;
                            return parseLogicalValue(rJson, type);
                        }
                        R fromDb = dbFallback.apply(id);
                        metric(fromDb == null ? "miss_negative" : "miss_loaded");
                        if (fromDb != null) {
                            this.setWithLogicalExpire(cacheKey, fromDb, time, unit);
                        } else {
                            stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        }
                        return fromDb;
                    } finally {
                        releaseLock(lockKey, token);
                    }
                }
                metric("lock_contention");
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(15L, 45L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                rJson = stringRedisTemplate.opsForValue().get(cacheKey);
                if (rJson != null) {
                    return rJson.isBlank() ? null : parseLogicalValue(rJson, type);
                }
            }
            // Redis locking may be unavailable. Keep the fallback bounded;
            // callers still receive a result while the negative value is
            // cached for a short period.
            R fromDb = dbFallback.apply(id);
            metric(fromDb == null ? "miss_negative_fallback" : "miss_loaded_fallback");
            if (fromDb != null) {
                this.setWithLogicalExpire(cacheKey, fromDb, time, unit);
            } else {
                stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            }
            return fromDb;
        }
        //命中需要判断是否过期
        RedisData redisData;
        R r;
        try {
            redisData = JSONUtil.toBean(rJson, RedisData.class);
            r = parseLogicalValue(rJson, type);
        } catch (Exception malformed) {
            // Treat malformed cache state as a bounded miss; never expose a
            // deserialization stack trace or spin indefinitely.
            metric("malformed");
            R fromDb = dbFallback.apply(id);
            if (fromDb != null) this.setWithLogicalExpire(cacheKey, fromDb, time, unit);
            else stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return fromDb;
        }
        if (redisData.getExpireTime() == null || redisData.getData() == null) {
            R fromDb = dbFallback.apply(id);
            if (fromDb != null) this.setWithLogicalExpire(cacheKey, fromDb, time, unit);
            else stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return fromDb;
        }
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            metric("hit");
            return r;
        }
        //已经过期，获取锁，重新写入
        String lock = LOCK_CACHE_REBUILD_KEY + keyPrefix + id;
        String token = UUID.randomUUID().toString();
        if (tryLock(lock, token)) {
            //开启独立线程进行缓存重建
            metric("rebuild_started");
            CACHE_REBUILDER_EXECUTOR.submit(() -> {
                try {
                    //查询店铺数据
                    R r1 = dbFallback.apply(id);
                    //封装逻辑过期时间
                    if (r1 != null) {
                        this.setWithLogicalExpire(keyPrefix + id, r1, time, unit);
                    } else {
                        stringRedisTemplate.opsForValue().set(keyPrefix + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    }
                    metric("rebuild_success");
                } catch (Exception e) {
                    metric("rebuild_failure");
                    log.warn("Cache rebuild failed for {}: {}", keyPrefix + id,
                            SensitiveLogSanitizer.exceptionSummary(e));
                } finally {
                    releaseLock(lock, token);
                }
            });
        }
        return r;
    }

    private void metric(String outcome) {
        if (reliabilityMetrics != null) {
            reliabilityMetrics.increment("campusdeal.cache.query", outcome);
        }
    }

    private <R> R parseLogicalValue(String json, Class<R> type) {
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Object data = redisData.getData();
        if (data == null) return null;
        return JSONUtil.toBean(data instanceof JSONObject ? (JSONObject) data : JSONUtil.parseObj(data.toString()), type);
    }

    private boolean tryLock(String key, String token) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, token, 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void releaseLock(String key, String token) {
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, java.util.Collections.singletonList(key), token);
        } catch (Exception e) {
            log.warn("Cache lock release failed for {}: {}", key,
                    SensitiveLogSanitizer.exceptionSummary(e));
        }
    }
}
