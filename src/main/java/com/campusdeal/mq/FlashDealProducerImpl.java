package com.campusdeal.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 秒杀订单 Kafka 生产者实现
 */
@Slf4j
@Component
public class FlashDealProducerImpl implements FlashDealProducer {

    /**
     * P0-1 修复：Kafka 发送专用后台线程池。
     * kafkaTemplate.send() 会同步等待 metadata，Kafka 未运行时最长阻塞 max.block.ms。
     * 下沉到后台线程后，秒杀主流程立即返回，不受 Kafka 可用性影响。
     */
    private static final ExecutorService KAFKA_SEND_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "flash-deal-kafka-send");
        t.setDaemon(true);
        return t;
    });

    @Resource
    private KafkaTemplate<String, FlashDealOrderMessage> kafkaTemplate;

    @Value("${campusdeal.kafka.topic.flash-deal-orders}")
    private String topic;

    @Override
    public SendResult<String, FlashDealOrderMessage> send(FlashDealOrderMessage message) {
        // key = userId 保证同一用户的消息有序
        ProducerRecord<String, FlashDealOrderMessage> record =
                new ProducerRecord<>(topic, message.getUserId().toString(), message);
        record.headers().add("messageId",
                (topic + "-" + message.getOrderId()).getBytes(StandardCharsets.UTF_8));

        // P0-1 修复：完全异步、尽力而为。订单已由 FlashDealServiceImpl 同步落库，
        // Kafka 发送失败只记日志，不回滚、不阻塞秒杀主流程（避免 60s 阻塞 + 500）。
        KAFKA_SEND_EXECUTOR.submit(() -> {
            try {
                kafkaTemplate.send(record)
                        .thenAccept(result -> log.debug(
                                "Kafka send success: orderId={}, offset={}, partition={}",
                                message.getOrderId(), result.getRecordMetadata().offset(),
                                result.getRecordMetadata().partition()))
                        .exceptionally(ex -> {
                            log.warn("Kafka send failed (degraded, order already persisted): orderId={}",
                                    message.getOrderId(), ex);
                            return null;
                        });
            } catch (Exception e) {
                log.warn("Kafka send rejected (degraded): orderId={}", message.getOrderId(), e);
            }
        });
        return null;
    }
}
