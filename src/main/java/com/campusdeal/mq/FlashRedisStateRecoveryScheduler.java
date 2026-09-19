package com.campusdeal.mq;

import com.campusdeal.security.SensitiveLogSanitizer;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusdeal.entity.CouponOrder;
import com.campusdeal.entity.FlashDeal;
import com.campusdeal.entity.FlashOrderIntent;
import com.campusdeal.mapper.CouponOrderMapper;
import com.campusdeal.mapper.FlashDealMapper;
import com.campusdeal.mapper.FlashOrderIntentMapper;
import com.campusdeal.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Rebuilds missing Redis admission mirrors from the durable database state. */
@Slf4j
@Component
public class FlashRedisStateRecoveryScheduler {

    @Resource private FlashDealMapper flashDealMapper;
    @Resource private CouponOrderMapper couponOrderMapper;
    @Resource private FlashOrderIntentMapper intentMapper;
    @Resource private StringRedisTemplate stringRedisTemplate;

    @Value("${campusdeal.flashdeal.recovery-enabled:true}")
    private boolean enabled = true;
    @Value("${campusdeal.flashdeal.durable-acceptance-enabled:true}")
    private boolean durableAcceptanceEnabled = true;

    @Scheduled(fixedDelayString = "${campusdeal.flashdeal.redis-recovery-interval-ms:60000}")
    public void recover() {
        if (!enabled || stringRedisTemplate == null) return;
        try {
            List<FlashDeal> deals = flashDealMapper.selectList(
                    Wrappers.<FlashDeal>lambdaQuery().gt(FlashDeal::getEndTime, LocalDateTime.now()));
            for (FlashDeal deal : deals) {
                if (deal.getVoucherId() == null || deal.getStock() == null) continue;
                rebuildStockIfMissing(deal);
                rebuildPurchasersIfMissing(deal.getVoucherId());
            }
        } catch (Exception e) {
            log.warn("Flash Redis state recovery deferred: {}", SensitiveLogSanitizer.exceptionSummary(e));
        }
    }

    private void rebuildStockIfMissing(FlashDeal deal) {
        String key = RedisConstants.FLASH_DEAL_STOCK_KEY + deal.getVoucherId();
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) return;
        int remaining = Math.max(deal.getStock(), 0);
        if (!durableAcceptanceEnabled) {
            Long completed = couponOrderMapper.selectCount(Wrappers.<CouponOrder>lambdaQuery()
                    .eq(CouponOrder::getVoucherId, deal.getVoucherId()));
            Long accepted = intentMapper.selectCount(Wrappers.<FlashOrderIntent>lambdaQuery()
                    .eq(FlashOrderIntent::getVoucherId, deal.getVoucherId())
                    .in(FlashOrderIntent::getStatus, "ACCEPTED", "PROCESSING"));
            remaining = Math.max(remaining - (completed == null ? 0 : completed.intValue())
                    - (accepted == null ? 0 : accepted.intValue()), 0);
        }
        stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(remaining));
        log.info("Rebuilt missing flash stock mirror: dealId={}, remaining={}", deal.getVoucherId(), remaining);
    }

    private void rebuildPurchasersIfMissing(Long dealId) {
        String key = RedisConstants.FLASH_DEAL_ORDER_KEY + dealId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) return;
        Set<String> users = couponOrderMapper.selectList(Wrappers.<CouponOrder>lambdaQuery()
                        .eq(CouponOrder::getVoucherId, dealId)).stream()
                .map(CouponOrder::getUserId)
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toSet());
        users.addAll(intentMapper.selectList(Wrappers.<FlashOrderIntent>lambdaQuery()
                        .eq(FlashOrderIntent::getVoucherId, dealId)
                        .in(FlashOrderIntent::getStatus, "ACCEPTED", "PROCESSING", "SUCCEEDED"))
                .stream().map(FlashOrderIntent::getUserId)
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toSet()));
        if (!users.isEmpty()) {
            stringRedisTemplate.opsForSet().add(key, users.toArray(String[]::new));
        }
    }
}
