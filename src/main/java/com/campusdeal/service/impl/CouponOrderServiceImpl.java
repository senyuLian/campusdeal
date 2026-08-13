package com.campusdeal.service.impl;

import com.campusdeal.dto.Result;
import com.campusdeal.entity.FlashDeal;
import com.campusdeal.entity.CouponOrder;
import com.campusdeal.mapper.CouponOrderMapper;
import com.campusdeal.service.IFlashDealService;
import com.campusdeal.service.ICouponOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campusdeal.utils.RedisIdWorker;
import com.campusdeal.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;

@Service
public class CouponOrderServiceImpl extends ServiceImpl<CouponOrderMapper, CouponOrder> implements ICouponOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFlashDealService flashDealService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    @Lazy
    private RedissonClient redissonClient;

    /**
     * Flash deal coupon purchase
     * @param dealId flash deal ID
     * @return order ID
     */
    @Override
    public Result seckillVoucher(Long dealId) {
        FlashDeal deal = flashDealService.getById(dealId);
        if (deal.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("Flash deal has not started");
        }
        if (deal.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("Flash deal has ended");
        }
        if (deal.getStock() < 1) {
            return Result.fail("Out of stock");
        }

        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            return Result.fail("Duplicate order not allowed");
        }
        try {
            ICouponOrderService proxy = (ICouponOrderService) AopContext.currentProxy();
            return proxy.createCouponOrder(dealId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createCouponOrder(Long dealId) {
        Long userId = UserHolder.getUser().getId();

        Long count = query().eq("user_id", userId).count();
        if (count > 0) {
            return Result.fail("User has already purchased once");
        }

        boolean success = flashDealService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", dealId)
                .gt("stock", 0)
                .update();

        if (!success) {
            return Result.fail("Out of stock");
        }

        CouponOrder order = new CouponOrder();
        order.setId(redisIdWorker.getNextId("order"));
        order.setUserId(userId);
        order.setVoucherId(dealId);
        save(order);

        return Result.ok(order.getId());
    }
}
