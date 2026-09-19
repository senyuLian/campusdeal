package com.campusdeal.mq;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusdeal.entity.Outbox;
import com.campusdeal.mapper.OutboxMapper;
import com.campusdeal.security.SensitiveLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Quarantines malformed rows written by the pre-intent outbox path. The job
 * is opt-in during migration; valid legacy rows remain pending for the normal
 * idempotent consumer and therefore are not silently discarded.
 */
@Slf4j
@Component
public class LegacyOutboxRepairScheduler {

    @Resource private OutboxMapper outboxMapper;
    @Value("${campusdeal.flashdeal.legacy-outbox-repair-enabled:false}")
    private boolean enabled = false;

    @Scheduled(fixedDelayString = "${campusdeal.flashdeal.legacy-outbox-repair-interval-ms:60000}")
    public void repair() {
        if (!enabled) return;
        List<Outbox> rows;
        try {
            rows = outboxMapper.selectList(Wrappers.<Outbox>lambdaQuery()
                    .eq(Outbox::getStatus, OutboxStatus.PENDING.name())
                    .likeRight(Outbox::getMessageId, "sync-")
                    .last("LIMIT 100"));
        } catch (Exception e) {
            log.warn("Legacy outbox repair skipped: {}", SensitiveLogSanitizer.exceptionSummary(e));
            return;
        }
        for (Outbox row : rows) {
            try {
                FlashDealOrderMessage message = JSONUtil.toBean(row.getPayload(), FlashDealOrderMessage.class);
                if (message.getOrderId() == null || message.getUserId() == null || message.getDealId() == null) {
                    throw new IllegalArgumentException("missing flash order identity");
                }
                // Keep a valid legacy event recoverable. The new consumer is
                // idempotent and will transition the row through the normal
                // Outbox scheduler.
            } catch (Exception e) {
                Outbox update = new Outbox();
                update.setId(row.getId());
                update.setStatus(OutboxStatus.FAILED.name());
                update.setErrorMsg("LEGACY_QUARANTINED: " + truncate(e));
                update.setUpdateTime(LocalDateTime.now());
                outboxMapper.updateById(update);
                log.error("Quarantined malformed legacy outbox row: id={}", row.getId());
            }
        }
    }

    private String truncate(Exception error) {
        return SensitiveLogSanitizer.exceptionSummary(error);
    }
}
