package com.campusdeal.mq;

import com.campusdeal.security.SensitiveLogSanitizer;
import com.campusdeal.entity.FlashOrderIntent;
import com.campusdeal.mapper.FlashOrderIntentMapper;
import com.campusdeal.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * Repairs Redis provisional reservations whose lease expired before a durable
 * intent was committed. The database intent is authoritative: accepted or
 * processing intents are never released by this job.
 */
@Slf4j
@Component
public class FlashReservationRecoveryScheduler {

    private static final String RESERVED_USERS_PREFIX = "flashdeal:reserved:";
    private static final String RESERVATION_PREFIX = "flashdeal:reservation:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private FlashOrderIntentMapper intentMapper;
    @Value("${campusdeal.flashdeal.recovery-enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${campusdeal.flashdeal.reservation-recovery-interval-ms:30000}")
    public void recover() {
        if (!enabled || stringRedisTemplate == null) return;
        try (Cursor<String> keys = stringRedisTemplate.scan(ScanOptions.scanOptions()
                .match(RESERVED_USERS_PREFIX + "*").count(100).build())) {
            while (keys.hasNext()) {
                String usersKey = keys.next();
                String dealText = usersKey.substring(RESERVED_USERS_PREFIX.length());
                Long dealId;
                try {
                    dealId = Long.valueOf(dealText);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                Map<Object, Object> reservations = stringRedisTemplate.opsForHash().entries(usersKey);
                for (Map.Entry<Object, Object> entry : reservations.entrySet()) {
                    Long orderId;
                    try {
                        orderId = Long.valueOf(String.valueOf(entry.getValue()));
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                    // A live lease is still owned by an in-flight request.
                    if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RESERVATION_PREFIX + orderId))) {
                        continue;
                    }
                    FlashOrderIntent intent = intentMapper.selectById(orderId);
                    if (intent != null) {
                        // Commit callback may have been lost. Keep the stock
                        // reservation and remove only the stale mirror member.
                        stringRedisTemplate.opsForHash().delete(usersKey, entry.getKey());
                        continue;
                    }
                    // No lease and no durable intent: return exactly one unit
                    // to the Redis admission key, then remove the mirror.
                    stringRedisTemplate.opsForValue().increment(
                            RedisConstants.FLASH_DEAL_STOCK_KEY + dealId);
                    stringRedisTemplate.opsForHash().delete(usersKey, entry.getKey());
                    log.warn("Released orphan flash reservation: dealId={}, orderId={}", dealId, orderId);
                }
            }
        } catch (Exception e) {
            log.warn("Flash reservation recovery deferred: {}", SensitiveLogSanitizer.exceptionSummary(e));
        }
    }
}
