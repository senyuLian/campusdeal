package com.campusdeal.service;

import com.campusdeal.dto.Result;
import com.campusdeal.entity.FlashDeal;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
public interface IFlashDealService extends IService<FlashDeal> {

    /**
     * 执行秒杀（三层过滤：布隆过滤器 → Caffeine L1 → Redis+Lua 原子扣减）
     *
     * @param dealId 秒杀活动 ID
     * @return Result.data = orderId（成功时）
     */
    Result executeFlashDeal(Long dealId);

    /**
     * 获取秒杀活动详情（用于预热缓存）
     *
     * @param dealId 秒杀活动 ID
     * @return FlashDeal 实体
     */
    FlashDeal getActiveDeal(Long dealId);

    /**
     * 预热秒杀库存到 Redis（管理员创建活动时调用）
     *
     * @param dealId 秒杀活动 ID
     * @param stock  库存数量
     */
    void preloadStock(Long dealId, Integer stock);
}
