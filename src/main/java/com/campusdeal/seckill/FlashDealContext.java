package com.campusdeal.seckill;

import lombok.Builder;
import lombok.Data;

/**
 * 秒杀执行上下文：贯穿一次 executeFlashDeal 调用的数据与各层耗时
 * <p>各层耗时字段用于压测分析，可暴露到监控面板。</p>
 */
@Data
@Builder
public class FlashDealContext {
    /** 秒杀活动 ID */
    private Long dealId;
    /** 用户 ID */
    private Long userId;
    /** 生成的订单 ID（Lua 成功后才生成） */
    private Long orderId;
    /** 执行开始时间（用于延迟监控） */
    private long startNanos;
    /** 各层耗时（用于压测分析） */
    private long bloomCostNs;
    private long caffeineCostNs;
    private long luaCostNs;
}
