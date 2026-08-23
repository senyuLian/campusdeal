package com.campusdeal.mq;

/**
 * 秒杀订单消息生产者
 */
public interface FlashDealProducer {

    /**
     * 发送秒杀订单消息到 Kafka（同步确认、有界超时）。
     *
     * <p>异步落库改造后，Kafka 成为订单的持久化点：调用方（秒杀主流程）只负责
     * 「受理订单」并可靠投递，真正的 MySQL 落库由 {@link FlashDealConsumer} 异步执行。</p>
     *
     * @param message 订单消息
     * @return true = 已被 broker 接受（后续由 Consumer 落库）；
     *         false = 投递失败（调用方应写 Outbox PENDING 补偿，避免丢单）
     */
    boolean send(FlashDealOrderMessage message);
}
