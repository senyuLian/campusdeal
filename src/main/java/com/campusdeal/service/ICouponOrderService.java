package com.campusdeal.service;

import com.campusdeal.dto.Result;
import com.campusdeal.entity.CouponOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ICouponOrderService extends IService<CouponOrder> {

    Result createCouponOrder(Long dealId);

    Result seckillVoucher(Long dealId);
}
