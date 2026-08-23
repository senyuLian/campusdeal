package com.campusdeal.service.impl;

import com.campusdeal.service.IdempotentService;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 幂等服务实现：SETNX + TTL 原子操作，去重标记自动过期
 */
@Service
public class IdempotentServiceImpl implements IdempotentService {

    /** 去重标记 TTL：1 小时（秒杀窗口过后自动清理） */
    private static final long DEDUP_TTL_HOURS = 1L;
    private static final long DEDUP_TTL_SECONDS = DEDUP_TTL_HOURS * 3600;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean tryMark(String dedupKey) {
        // SETNX + TTL 原子操作
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", DEDUP_TTL_HOURS, TimeUnit.HOURS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public List<Boolean> tryMarkBatch(List<String> dedupKeys) {
        if (dedupKeys == null || dedupKeys.isEmpty()) {
            return new ArrayList<>();
        }
        // 一次 pipeline 发出 N 个 SET key "1" NX EX 3600，仅一次网络往返
        List<Object> results = stringRedisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    StringRedisConnection conn = (StringRedisConnection) connection;
                    for (String key : dedupKeys) {
                        conn.set(key, "1",
                                Expiration.seconds(DEDUP_TTL_SECONDS),
                                RedisStringCommands.SetOption.SET_IF_ABSENT);
                    }
                    return null;
                });

        List<Boolean> marks = new ArrayList<>(dedupKeys.size());
        for (Object r : results) {
            // SET NX 成功返回 true；键已存在返回 null/false → 视为"已处理"
            marks.add(Boolean.TRUE.equals(r));
        }
        return marks;
    }

    @Override
    public void clearMark(String dedupKey) {
        stringRedisTemplate.delete(dedupKey);
    }
}
