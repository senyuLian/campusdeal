package com.campusdeal.mq;

import cn.hutool.json.JSONUtil;
import com.campusdeal.entity.CouponOrder;
import com.campusdeal.mapper.CouponOrderMapper;
import com.campusdeal.service.IdempotentService;
import com.campusdeal.service.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;

/**
 * 秒杀订单 Kafka 消费者
 *
 * <p>消费流水线：Redis SETNX 幂等去重 → MySQL INSERT 落库 → Outbox 记录。
 * 消费失败不 ack，交由 Kafka 重投；同时写 Outbox PENDING 供补偿调度器重试。</p>
 */
@Slf4j
@Component
public class FlashDealConsumer {

    @Resource
    private IdempotentService idempotentService;
    @Resource
    private CouponOrderMapper couponOrderMapper;
    @Resource
    private OutboxService outboxService;

    @KafkaListener(
            topics = "${campusdeal.kafka.topic.flash-deal-orders}",
            groupId = "flash-deal-group",
            concurrency = "3"  // 3 个消费者线程，提升吞吐
    )
    public void onMessage(ConsumerRecord<String, FlashDealOrderMessage> record,
                          Acknowledgment ack) {
        FlashDealOrderMessage msg = record.value();
        String dedupKey = buildDedupKey(msg);

        // === Step 1: 幂等检查（Redis SETNX，快速去重） ===
        if (!idempotentService.tryMark(dedupKey)) {
            log.info("Duplicate message skipped: orderId={}", msg.getOrderId());
            ack.acknowledge();
            return;
        }

        String messageId = record.key() + "-" + record.offset();
        try {
            // === Step 2: 订单落库 ===
            couponOrderMapper.insert(buildOrder(msg));

            // === Step 3: Outbox 记录（PROCESSED） ===
            outboxService.record(messageId, JSONUtil.toJsonStr(msg), OutboxStatus.PROCESSED);

            ack.acknowledge();
            log.info("Order persisted: orderId={}, userId={}, dealId={}",
                    msg.getOrderId(), msg.getUserId(), msg.getDealId());

        } catch (DuplicateKeyException e) {
            // MySQL UNIQUE KEY 冲突 = 幂等兜底，视为已处理
            log.warn("Duplicate key on insert, already processed: orderId={}", msg.getOrderId());
            ack.acknowledge();

        } catch (Exception e) {
            // 重置幂等标记，供补偿调度器重试；同时写 Outbox PENDING
            idempotentService.clearMark(dedupKey);
            outboxService.record(messageId, JSONUtil.toJsonStr(msg), OutboxStatus.PENDING);
            log.error("Failed to process order: orderId={}", msg.getOrderId(), e);
            // 不调用 ack.acknowledge()，消息会被 Kafka 重新投递
        }
    }

    /**
     * 补偿重试入口：幂等保护 + 落库，供 OutboxScheduler 复用。
     * 重复插入（订单已存在）视为成功，不会向上抛异常。
     */
    public void processMessage(FlashDealOrderMessage msg) {
        String dedupKey = buildDedupKey(msg);
        if (!idempotentService.tryMark(dedupKey)) {
            log.info("Duplicate message skipped (compensation): orderId={}", msg.getOrderId());
            return;
        }
        try {
            couponOrderMapper.insert(buildOrder(msg));
        } catch (DuplicateKeyException e) {
            // MySQL UNIQUE KEY 兜底：订单已存在，补偿视为处理成功
            log.warn("Duplicate key on insert, already processed (compensation): orderId={}",
                    msg.getOrderId());
        }
    }

    private String buildDedupKey(FlashDealOrderMessage msg) {
        return String.format("order:dedup:%d:%d", msg.getUserId(), msg.getDealId());
    }

    private CouponOrder buildOrder(FlashDealOrderMessage msg) {
        CouponOrder order = new CouponOrder();
        order.setId(msg.getOrderId());
        order.setUserId(msg.getUserId());
        order.setVoucherId(msg.getDealId());
        order.setStatus(1);  // 未支付，后续支付回调再更新
        order.setCreateTime(LocalDateTime.now());
        return order;
    }
}
