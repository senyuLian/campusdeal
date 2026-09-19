package com.campusdeal.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusdeal.entity.CouponOrder;
import com.campusdeal.entity.FlashOrderIntent;
import com.campusdeal.mapper.CouponOrderMapper;
import com.campusdeal.mapper.FlashOrderIntentMapper;
import com.campusdeal.security.SensitiveLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DB-only recovery for accepted intents. It keeps durable acceptance
 * recoverable while Kafka is disabled or temporarily unavailable, and is
 * idempotent with the Kafka consumer through the order unique keys.
 */
@Slf4j
@Component
public class FlashOrderIntentRecoveryScheduler {

    @Resource
    private FlashOrderIntentMapper intentMapper;
    @Resource
    private CouponOrderMapper orderMapper;

    @Value("${campusdeal.flashdeal.recovery-enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${campusdeal.flashdeal.recovery-interval-ms:30000}")
    @Transactional
    public void recover() {
        if (!enabled) return;
        List<FlashOrderIntent> intents = intentMapper.selectList(new LambdaQueryWrapper<FlashOrderIntent>()
                .in(FlashOrderIntent::getStatus, "ACCEPTED", "PROCESSING")
                .le(FlashOrderIntent::getAcceptedAt, LocalDateTime.now())
                .orderByAsc(FlashOrderIntent::getAcceptedAt)
                .last("LIMIT 100"));
        for (FlashOrderIntent intent : intents) {
            try {
                intentMapper.advanceStatus(intent.getOrderId(), "PROCESSING", null);
                CouponOrder order = new CouponOrder();
                order.setId(intent.getOrderId());
                order.setUserId(intent.getUserId());
                order.setVoucherId(intent.getVoucherId());
                order.setStatus(1);
                order.setCreateTime(LocalDateTime.now());
                try {
                    orderMapper.insert(order);
                } catch (DuplicateKeyException duplicate) {
                    // Existing order is the idempotent terminal evidence.
                }
                intentMapper.advanceStatus(intent.getOrderId(), "SUCCEEDED", null);
            } catch (Exception e) {
                intentMapper.advanceStatus(intent.getOrderId(), "FAILED", truncate(e));
                log.warn("Flash intent recovery failed: orderId={}", intent.getOrderId());
            }
        }
    }

    private String truncate(Exception error) {
        return SensitiveLogSanitizer.exceptionSummary(error);
    }
}
