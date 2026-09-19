package com.campusdeal.mq;

import com.campusdeal.security.SensitiveLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单 Kafka 生产者实现
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "campusdeal.kafka", name = "enabled", havingValue = "true")
public class FlashDealProducerImpl implements FlashDealProducer {

    /**
     * 同步确认超时（秒）。与 application.yaml 的 max.block.ms 双重约束：
     * broker 不可用时 metadata 获取最多阻塞 max.block.ms，发送后等待 ack 最多阻塞本超时。
     * 超时/失败均返回 false，交由调用方写 Outbox 补偿。
     */
    private static final int SEND_CONFIRM_TIMEOUT_SECONDS = 3;

    @Resource
    private KafkaTemplate<String, FlashDealOrderMessage> kafkaTemplate;

    @Value("${campusdeal.kafka.topic.flash-deal-orders}")
    private String topic;

    @Override
    public boolean send(FlashDealOrderMessage message) {
        // key = userId 保证同一用户的消息有序
        ProducerRecord<String, FlashDealOrderMessage> record =
                new ProducerRecord<>(topic, message.getUserId().toString(), message);
        record.headers().add("messageId",
                (topic + "-" + message.getOrderId()).getBytes(StandardCharsets.UTF_8));

        // 异步落库改造：同步等待 broker 确认（受 max.block.ms + get 超时双重约束），
        // 返回 true 表示消息已被 Kafka 接受、后续由 FlashDealConsumer 异步落库。
        // 投递失败只记日志并返回 false，由调用方写 Outbox PENDING 补偿，不丢单。
        try {
            kafkaTemplate.send(record).get(SEND_CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            log.warn("Kafka send failed, will compensate via outbox: orderId={}, error={}",
                    message.getOrderId(), SensitiveLogSanitizer.exceptionSummary(e));
            return false;
        }
    }
}
