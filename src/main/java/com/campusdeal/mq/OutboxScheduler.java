package com.campusdeal.mq;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusdeal.entity.Outbox;
import com.campusdeal.mapper.OutboxMapper;
import com.campusdeal.service.OutboxService;
import com.campusdeal.security.SensitiveLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 补偿调度器：定时扫描 PENDING 消息，重试处理
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "campusdeal.kafka", name = "enabled", havingValue = "true")
public class OutboxScheduler {

    @Resource
    private OutboxMapper outboxMapper;
    @Resource
    private OutboxService outboxService;
    @Resource
    private FlashDealConsumer flashDealConsumer;

    private static final int MAX_RETRY = 5;
    private static final int BATCH_SIZE = 100;

    @Scheduled(fixedDelay = 30_000)  // 每 30 秒
    public void compensate() {
        List<Outbox> pending;
        try {
            pending = outboxMapper.selectList(
                    Wrappers.<Outbox>lambdaQuery()
                            .eq(Outbox::getStatus, OutboxStatus.PENDING.name())
                            .lt(Outbox::getCreateTime,
                                    LocalDateTime.now().minusMinutes(1))  // 1 分钟前的消息才补偿
                            .last("LIMIT " + BATCH_SIZE)
            );
        } catch (Exception e) {
            // P0-2 防御：表缺失/DB 抖动时降级为单条 warn，避免每 30s 刷错误日志
            log.warn("Outbox compensation skipped: {}", SensitiveLogSanitizer.exceptionSummary(e));
            return;
        }

        if (pending.isEmpty()) return;

        log.info("Outbox compensation: found {} pending messages", pending.size());

        for (Outbox msg : pending) {
            try {
                FlashDealOrderMessage orderMsg =
                        JSONUtil.toBean(msg.getPayload(), FlashDealOrderMessage.class);
                // 重试处理（幂等服务 + MySQL UNIQUE KEY 保证不会重复落库）
                flashDealConsumer.processMessage(orderMsg);
                outboxService.updateStatus(msg.getId(), OutboxStatus.PROCESSED, msg.getRetryCount(), null);
            } catch (Exception e) {
                int newRetryCount = msg.getRetryCount() + 1;
                if (newRetryCount >= MAX_RETRY) {
                    outboxService.updateStatus(msg.getId(), OutboxStatus.FAILED, newRetryCount,
                            truncate(e));
                    log.error("Outbox message FAILED after {} retries: messageId={}",
                            MAX_RETRY, msg.getMessageId(), SensitiveLogSanitizer.exceptionSummary(e));
                } else {
                    outboxService.updateStatus(msg.getId(), OutboxStatus.PENDING, newRetryCount,
                            truncate(e));
                }
            }
        }
    }

    private String truncate(Exception error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) return "RETRY_FAILED";
        String redacted = SensitiveLogSanitizer.redact(message);
        return redacted.length() > 500 ? redacted.substring(0, 500) : redacted;
    }
}
