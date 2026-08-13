package com.campusdeal.service;

import com.campusdeal.dto.Result;
import com.campusdeal.entity.Coupon;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ICouponService extends IService<Coupon> {

    Result queryCouponOfMerchant(Long shopId);

    /** 全站进行中的闪购券列表（含商户名/图片） */
    Result queryFlashDealList();

    /** 全站上架优惠券列表（普通券 + 闪购券，含商户名/图片） */
    Result queryAllCoupons();

    void addFlashDeal(Coupon coupon);

}
