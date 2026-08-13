package com.campusdeal.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀订单 Kafka 消息体
 * <p>由 FlashDealServiceImpl（秒杀成功）发送，FlashDealConsumer 消费落库。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlashDealOrderMessage {

    /** 订单 ID（RedisIdWorker 生成） */
    private Long orderId;
    /** 用户 ID */
    private Long userId;
    /** 秒杀活动 ID */
    private Long dealId;
    /** 生成时间戳（毫秒） */
    private Long timestamp;
}
