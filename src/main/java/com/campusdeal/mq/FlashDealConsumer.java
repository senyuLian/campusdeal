package com.campusdeal.mq;

import com.campusdeal.security.SensitiveLogSanitizer;
import cn.hutool.json.JSONUtil;
import com.campusdeal.entity.CouponOrder;
import com.campusdeal.mapper.CouponOrderMapper;
import com.campusdeal.mapper.FlashOrderIntentMapper;
import com.campusdeal.service.IdempotentService;
import com.campusdeal.service.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 消费失败会先记录 Outbox PENDING，再抛出异常交由 Kafka 的重试/DLT 处理器接管。</p>
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
@ConditionalOnProperty(prefix = "campusdeal.kafka", name = "enabled", havingValue = "true")
public class FlashDealConsumer {

    @Resource
    private IdempotentService idempotentService;
    @Resource
    private CouponOrderMapper couponOrderMapper;
    @Resource
    private OutboxService outboxService;
    @Autowired(required = false)
    private FlashOrderIntentMapper intentMapper;

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
            log.warn("Redis dedup unavailable, fallback to DB-only idempotency: {}",
                    SensitiveLogSanitizer.exceptionSummary(e));
            marks = new ArrayList<>(Collections.nCopies(records.size(), true));
        }

        // 本批首次处理的消息（重复消息直接跳过）
        List<CouponOrder> orders = new ArrayList<>();
        List<Integer> newIdx = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            // A malformed/short Redis pipeline response is not evidence that
            // a message was durably processed. Let the database uniqueness
            // constraint decide, then keep the batch recoverable on failure.
            boolean firstAttempt = marks != null && i < marks.size()
                    ? Boolean.TRUE.equals(marks.get(i)) : true;
            if (firstAttempt) {
                orders.add(buildOrder(records.get(i).value()));
                newIdx.add(i);
            } else {
                FlashDealOrderMessage message = records.get(i).value();
                CouponOrder existing = couponOrderMapper.selectById(message.getOrderId());
                if (existing != null) {
                    advanceIntent(message.getOrderId());
                    log.info("Duplicate message confirmed by durable order: orderId={}",
                            message.getOrderId());
                } else {
                    // Redis marker may be stale after a process crash. Clear
                    // it and put the record through the DB uniqueness path.
                    try {
                        idempotentService.clearMark(dedupKeys.get(i));
                    } catch (Exception clearError) {
                        log.warn("Failed to clear stale dedup mark: {}", clearError.getMessage());
                    }
                    orders.add(buildOrder(message));
                    newIdx.add(i);
                }
            }
        }

        if (orders.isEmpty()) {
            ack.acknowledge();
            return;
        }

        try {
            // === Step 2: 批量落库（单条多行 INSERT IGNORE，UNIQUE KEY 幂等兜底） ===
            couponOrderMapper.batchInsertIgnore(orders);
            if (intentMapper != null) {
                for (CouponOrder order : orders) {
                    // INSERT IGNORE may skip a row because another durable
                    // user/voucher order already exists. Advance only when
                    // this exact order identity is present.
                    if (couponOrderMapper.selectById(order.getId()) != null) {
                        advanceIntent(order.getId());
                    }
                }
            }
            ack.acknowledge();
            log.info("Batch persisted: {} orders", orders.size());

        } catch (Exception e) {
            // === 失败：重置幂等标记 + 写 Outbox PENDING，再抛出异常触发 Kafka 重试/DLT ===
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
            log.error("Failed to persist batch of {} orders: {}", orders.size(),
                    SensitiveLogSanitizer.exceptionSummary(e));
            // Propagate after compensation so the configured Kafka error
            // handler can perform bounded retries/DLT transfer. Merely
            // returning without ack does not rewind a consumer position.
            throw new IllegalStateException("flash order batch persistence failed", e);
        }
    }

    /**
     * 补偿重试入口：幂等保护 + 落库，供 OutboxScheduler 复用。
     * 重复插入（订单已存在）视为成功，不会向上抛异常。
     */
    public void processMessage(FlashDealOrderMessage msg) {
        String dedupKey = buildDedupKey(msg);
        boolean marked = idempotentService.tryMark(dedupKey);
        if (!marked) {
            // A Redis marker is only a fast path. Confirm the durable order
            // before treating the message as complete; stale markers are
            // cleared and allowed to retry.
            CouponOrder existing = couponOrderMapper.selectById(msg.getOrderId());
            if (existing != null) {
                advanceIntent(msg.getOrderId());
                log.info("Duplicate message confirmed by durable order: orderId={}", msg.getOrderId());
                return;
            }
            idempotentService.clearMark(dedupKey);
            marked = idempotentService.tryMark(dedupKey);
            if (!marked) {
                throw new IllegalStateException("order dedup marker is busy without a durable order");
            }
        }
        try {
            couponOrderMapper.insert(buildOrder(msg));
            advanceIntent(msg.getOrderId());
        } catch (DuplicateKeyException e) {
            // MySQL UNIQUE KEY 兜底. Only the exact order ID is terminal
            // evidence; a user/voucher uniqueness conflict for another order
            // must remain recoverable for reconciliation.
            CouponOrder existing = couponOrderMapper.selectById(msg.getOrderId());
            if (existing == null) {
                try {
                    idempotentService.clearMark(dedupKey);
                } catch (Exception clearError) {
                    log.warn("Failed to clear dedup mark after ambiguous duplicate: {}", clearError.getMessage());
                }
                throw e;
            }
            advanceIntent(msg.getOrderId());
            log.warn("Duplicate key on insert, exact order already exists: orderId={}", msg.getOrderId());
        }
        catch (RuntimeException e) {
            // A fast Redis mark must never hide a failed durable write. Clear
            // it so a later compensation attempt can retry the message.
            try {
                idempotentService.clearMark(dedupKey);
            } catch (Exception clearError) {
                log.warn("Failed to clear compensation dedup mark: {}", clearError.getMessage());
            }
            throw e;
        }
    }

    private void advanceIntent(Long orderId) {
        if (intentMapper != null) {
            intentMapper.advanceStatus(orderId, "SUCCEEDED", null);
        }
    }

    private String buildDedupKey(FlashDealOrderMessage msg) {
        return String.format("order:dedup:%d", msg.getOrderId());
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
