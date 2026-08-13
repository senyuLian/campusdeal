package com.campusdeal.mq;

import org.springframework.kafka.support.SendResult;

/**
 * 秒杀订单消息生产者
 */
public interface FlashDealProducer {

    /**
     * 发送秒杀订单消息到 Kafka（尽力而为，异步回调）。
     *
     * <p>订单已由调用方同步落库，本方法发送失败只记日志、不抛异常、不回滚，
     * 避免 Kafka 不可用时阻塞秒杀主流程（P0-1）。</p>
     *
     * @param message 订单消息
     * @return SendResult（异步发送时恒为 null，成功信息由回调打印）
     */
    SendResult<String, FlashDealOrderMessage> send(FlashDealOrderMessage message);
}
