package com.campusdeal.controller;


import com.campusdeal.dto.Result;
import com.campusdeal.entity.Coupon;
import com.campusdeal.service.ICouponService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;


/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/coupon")
public class CouponController {

    @Resource
    private ICouponService couponService;

    /**
     * 新增普通券
     * @param coupon 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Coupon coupon) {
        couponService.save(coupon);
        return Result.ok(coupon.getId());
    }

    /**
     * 新增秒杀券
     * @param coupon 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addFlashDeal(@RequestBody Coupon coupon) {
        couponService.addFlashDeal(coupon);
        return Result.ok(coupon.getId());
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryCouponOfMerchant(@PathVariable("shopId") Long shopId) {
       return couponService.queryCouponOfMerchant(shopId);
    }

    /**
     * 全站进行中的闪购券列表（金刚区「抢购」入口）
     * @return 闪购券列表（含商户名/图片）
     */
    @GetMapping("/flash/list")
    public Result queryFlashDealList() {
        return couponService.queryFlashDealList();
    }

    /**
     * 全站上架优惠券列表（金刚区「卡券」入口）
     * @return 优惠券列表（普通券 + 闪购券，含商户名/图片）
     */
    @GetMapping("/list/all")
    public Result queryAllCoupons() {
        return couponService.queryAllCoupons();
    }
}
