package com.campusdeal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusdeal.entity.Coupon;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface CouponMapper extends BaseMapper<Coupon> {

    List<Coupon> queryCouponOfMerchant(@Param("shopId") Long shopId);

    /** 全站进行中的闪购券（status=1、type=1、库存>0、当前时间在生效期内），关联商户名/图片 */
    List<Coupon> queryFlashDealList();

    /** 全站上架优惠券（普通券 + 闪购券），关联商户名/图片 */
    List<Coupon> queryAllCoupons();
}
