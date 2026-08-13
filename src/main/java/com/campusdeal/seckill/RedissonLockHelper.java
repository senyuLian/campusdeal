package com.campusdeal.seckill;

import com.campusdeal.exception.BusinessException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson 分布式锁工具
 *
 * <p>秒杀场景中，Lua 脚本的 SISMEMBER 已经实现了一人一单的互斥；
 * 分布式锁保留用于需要跨服务互斥的场景（如优惠券核销、非秒杀下单）。</p>
 */
@Component
public class RedissonLockHelper {

    @Resource
    @Lazy
    private RedissonClient redissonClient;

    /**
     * 尝试获取锁并执行
     *
     * @param lockKey   锁的 key
     * @param waitTime  最大等待时间（秒）
     * @param leaseTime 锁持有时间（秒，-1 = Watchdog 自动续期）
     * @param action    需要互斥执行的代码块
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                 Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS)) {
                return action.get();
            }
            throw new BusinessException("Failed to acquire lock: " + lockKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Lock interrupted: " + lockKey);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
