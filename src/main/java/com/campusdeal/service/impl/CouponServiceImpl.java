package com.campusdeal.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campusdeal.dto.Result;
import com.campusdeal.entity.Coupon;
import com.campusdeal.entity.CouponOrder;
import com.campusdeal.mapper.CouponMapper;
import com.campusdeal.entity.FlashDeal;
import com.campusdeal.service.IFlashDealService;
import com.campusdeal.service.ICouponOrderService;
import com.campusdeal.service.ICouponService;
import com.campusdeal.security.AuthorizationService;
import com.campusdeal.cache.BloomFilterService;
import com.campusdeal.utils.RedisIdWorker;
import com.campusdeal.utils.SimpleRedisLock;
import com.campusdeal.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static com.campusdeal.utils.RedisConstants.FLASH_DEAL_STOCK_KEY;
import static com.campusdeal.utils.RedisConstants.FLASH_DEAL_TIME_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {

    @Resource
    private IFlashDealService seckillVoucherService;
    @Resource
    private  StringRedisTemplate stringRedisTemplate;

    @Resource
    private AuthorizationService authorizationService;

    @Resource
    private BloomFilterService bloomFilterService;

    @Override
    @Transactional
    public boolean save(Coupon coupon) {
        if (coupon == null) {
            return false;
        }
        authorizationService.requireMerchantOwner(coupon.getShopId());
        validateCoupon(coupon);
        return super.save(coupon);
    }



    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */

    @Override
    public Result queryCouponOfMerchant(Long shopId) {
        // 查询优惠券信息
        List<Coupon> coupons = getBaseMapper().queryCouponOfMerchant(shopId);
        // 返回结果
        return Result.ok(coupons);
    }

    @Override
    public Result queryFlashDealList() {
        // 全站进行中的闪购券（status=1、库存>0、当前时间在生效期内）
        List<Coupon> coupons = getBaseMapper().queryFlashDealList();
        return Result.ok(coupons);
    }

    @Override
    public Result queryAllCoupons() {
        // 全站上架优惠券（普通券 + 闪购券）
        List<Coupon> coupons = getBaseMapper().queryAllCoupons();
        return Result.ok(coupons);
    }

    /**
     * 添加秒杀券
     * @param coupon 优惠券信息
     */
    @Override
    @Transactional
    public void addFlashDeal(Coupon coupon) {
        if (coupon == null) {
            throw new com.campusdeal.exception.ValidationException("优惠券不能为空");
        }
        authorizationService.requireMerchantOwner(coupon.getShopId());
        validateCoupon(coupon);
        // 保存优惠券
        save(coupon);
        // 保存秒杀信息
        FlashDeal seckillVoucher = new FlashDeal();
        seckillVoucher.setVoucherId(coupon.getId());
        seckillVoucher.setStock(coupon.getStock());
        seckillVoucher.setBeginTime(coupon.getBeginTime());
        seckillVoucher.setEndTime(coupon.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        Runnable publishAdmission = () -> {
            stringRedisTemplate.opsForValue().set(FLASH_DEAL_STOCK_KEY + coupon.getId(), coupon.getStock().toString());
            if (coupon.getBeginTime() != null && coupon.getEndTime() != null) {
                stringRedisTemplate.opsForValue().set(FLASH_DEAL_TIME_KEY + coupon.getId(),
                        coupon.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                + "|" + coupon.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            }
            bloomFilterService.registerCommitted(coupon.getId());
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishAdmission.run();
                }
            });
        } else {
            publishAdmission.run();
        }
    }

    private void validateCoupon(Coupon coupon) {
        if (coupon.getShopId() == null || coupon.getTitle() == null || coupon.getTitle().isBlank()) {
            throw new com.campusdeal.exception.ValidationException("店铺和标题不能为空");
        }
        if (coupon.getPayValue() == null || coupon.getPayValue() < 0
                || coupon.getActualValue() == null || coupon.getActualValue() < 0) {
            throw new com.campusdeal.exception.ValidationException("优惠券金额必须为非负数");
        }
        if (coupon.getType() != null && coupon.getType() == 1) {
            if (coupon.getStock() == null || coupon.getStock() <= 0
                    || coupon.getBeginTime() == null || coupon.getEndTime() == null
                    || !coupon.getEndTime().isAfter(coupon.getBeginTime())) {
                throw new com.campusdeal.exception.ValidationException("秒杀库存和有效期不合法");
            }
        }
    }



}
