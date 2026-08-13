package com.campusdeal.service.impl;

import com.campusdeal.service.IdempotentService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.util.concurrent.TimeUnit;

/**
 * 幂等服务实现：SETNX + TTL 原子操作，去重标记自动过期
 */
@Service
public class IdempotentServiceImpl implements IdempotentService {

    /** 去重标记 TTL：1 小时（秒杀窗口过后自动清理） */
    private static final long DEDUP_TTL_HOURS = 1L;

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
    public void clearMark(String dedupKey) {
        stringRedisTemplate.delete(dedupKey);
    }
}
