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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 秒杀订单 Kafka 消费者（批量消费）
 *
 * <p>消费流水线：Redis pipeline 批量 SETNX 幂等去重 → 单条多行 INSERT IGNORE 批量落库。
 * 消费失败不 ack，交由 Kafka 重投；同时写 Outbox PENDING 供补偿调度器重试。</p>
 *
 * <p>容错：Redis 仅是快速去重路径，MySQL UNIQUE KEY 才是最终幂等兜底。Redis 不可用时
 * 自动降级为 DB-only（INSERT IGNORE 去重），任何 Redis 抖动都不会中断 Kafka 消费。</p>
 *
 * <p>吞吐优化（相对逐条消费）：
 * <ul>
 *   <li>concurrency 3→6：消费者线程匹配 6 分区；</li>
 *   <li>批量消费：一次 poll 拉取多条，N 个 SETNX 合并为 1 次 pipeline 往返；</li>
 *   <li>批量落库：N 条 INSERT 合并为 1 条多行 INSERT IGNORE；</li>
 *   <li>成功路径不再写 Outbox PROCESSED（订单行即为成功记录，补偿调度器只读 PENDING）。</li>
 * </ul></p>
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
            concurrency = "6",   // 6 个消费者线程，匹配 6 分区
            batch = "true"       // 批量消费：一次 poll 拉取多条，批量落库
    )
    public void onMessage(List<ConsumerRecord<String, FlashDealOrderMessage>> records,
                          Acknowledgment ack) {
        if (records == null || records.isEmpty()) {
            ack.acknowledge();
            return;
        }

        // === Step 1: 批量幂等检查（一次 Redis pipeline 完成 N 个 SETNX） ===
        // Redis 仅是「快速去重」路径，MySQL UNIQUE KEY 才是最终幂等兜底。
        // Redis 暂不可用（连接耗尽/抖动）时降级为「全部视为首次」，由 batchInsertIgnore
        // 的 INSERT IGNORE 在 DB 层去重——绝不因 Redis 抖动抛异常中断 Kafka 消费。
        List<String> dedupKeys = new ArrayList<>(records.size());
        for (ConsumerRecord<String, FlashDealOrderMessage> record : records) {
            dedupKeys.add(buildDedupKey(record.value()));
        }
        List<Boolean> marks;
        try {
            marks = idempotentService.tryMarkBatch(dedupKeys);
        } catch (Exception e) {
            log.warn("Redis dedup unavailable, fallback to DB-only idempotency: {}", e.getMessage());
            marks = new ArrayList<>(Collections.nCopies(records.size(), true));
        }

        // 本批首次处理的消息（重复消息直接跳过）
        List<CouponOrder> orders = new ArrayList<>();
        List<Integer> newIdx = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            if (Boolean.TRUE.equals(marks.get(i))) {
                orders.add(buildOrder(records.get(i).value()));
                newIdx.add(i);
            } else {
                log.info("Duplicate message skipped: orderId={}",
                        records.get(i).value().getOrderId());
            }
        }

        if (orders.isEmpty()) {
            ack.acknowledge();
            return;
        }

        try {
            // === Step 2: 批量落库（单条多行 INSERT IGNORE，UNIQUE KEY 幂等兜底） ===
            couponOrderMapper.batchInsertIgnore(orders);
            ack.acknowledge();
            log.info("Batch persisted: {} orders", orders.size());

        } catch (Exception e) {
            // === 失败：重置幂等标记 + 写 Outbox PENDING，不 ack（Kafka 整批重投） ===
            // 补偿动作自身也可能失败（Redis/DB 同时不可用），逐一 try/catch，
            // 避免补偿异常再次逃逸出监听器、把 Kafka 容器打停。
            for (int idx : newIdx) {
                FlashDealOrderMessage msg = records.get(idx).value();
                try {
                    idempotentService.clearMark(dedupKeys.get(idx));
                } catch (Exception ce) {
                    log.warn("Failed to clear dedup mark (redis down?): orderId={}", msg.getOrderId());
                }
                ConsumerRecord<String, FlashDealOrderMessage> record = records.get(idx);
                String messageId = record.key() + "-" + record.offset();
                try {
                    outboxService.record(messageId, JSONUtil.toJsonStr(msg), OutboxStatus.PENDING);
                } catch (Exception oe) {
                    // DB 亦不可用时 Outbox 写失败，忽略（Kafka 重投 + 补偿调度器兜底）
                    log.warn("Outbox PENDING write failed (db down?): orderId={}", msg.getOrderId());
                }
            }
            log.error("Failed to persist batch of {} orders", orders.size(), e);
            // 不调用 ack.acknowledge()，整批消息会被 Kafka 重新投递
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
