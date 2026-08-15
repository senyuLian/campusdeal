package com.campusdeal.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.campusdeal.entity.Merchant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.security.KeyPair;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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



    private static final ExecutorService CACHE_REBUILDER_EXECUTOR = Executors.newFixedThreadPool(10);

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
        String rJson = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        if (StringUtils.isNotBlank(rJson)) {
            // 修复：BeanUtil.toBean(String,Class) 不会把 JSON 字符串解析成对象（字段全为 null），
            // 缓存命中会返回空实体。改用 JSONUtil.toBean 正确反序列化。
            R r = JSONUtil.toBean(rJson, type);
            return r;
        }
        if (rJson != null) {
            return null;
        }
        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(keyPrefix + id, "", time, unit);
            return null;
        }
        stringRedisTemplate.opsForValue().set(keyPrefix + id, JSONUtil.toJsonStr(r), time, unit);
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
        String rJson = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        if (StringUtils.isBlank(rJson)) {
            // 缓存未命中（未预热/已清空）：回源数据库并重建逻辑过期缓存，
            // 避免真实存在的记录因缓存缺失被误判为“不存在”
            R fromDb = dbFallback.apply(id);
            if (fromDb != null) {
                this.setWithLogicalExpire(keyPrefix + id, fromDb, time, unit);
            }
            return fromDb;
        }
        //命中需要判断是否过期
        RedisData redisData = JSONUtil.toBean(rJson, RedisData.class);
        JSONObject jsonShop = (JSONObject)redisData.getData();
        R r = JSONUtil.toBean(jsonShop, type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }
        //已经过期，获取锁，重新写入
        String lock = keyPrefix + id;
        if (tryLock(lock)) {
            //开启独立线程进行缓存重建
            CACHE_REBUILDER_EXECUTOR.submit(() -> {
                try {
                    //查询店铺数据
                    R r1 = dbFallback.apply(id);
                    //封装逻辑过期时间
                    this.setWithLogicalExpire(keyPrefix + id, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lock);
                }
            });
        }
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
