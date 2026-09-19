package com.campusdeal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusdeal.dto.Result;
import com.campusdeal.entity.CouponOrder;
import com.campusdeal.entity.FlashDeal;
import com.campusdeal.entity.FlashOrderIntent;
import com.campusdeal.exception.ValidationException;
import com.campusdeal.mapper.CouponOrderMapper;
import com.campusdeal.mapper.FlashDealMapper;
import com.campusdeal.mapper.FlashOrderIntentMapper;
import com.campusdeal.mq.FlashDealOrderMessage;
import com.campusdeal.mq.OutboxStatus;
import com.campusdeal.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Database acceptance boundary for flash orders. */
@Service
public class FlashOrderIntentService {
    @Resource private FlashDealMapper flashDealMapper;
    @Resource private FlashOrderIntentMapper intentMapper;
    @Resource private CouponOrderMapper couponOrderMapper;
    @Resource private RedisIdWorker redisIdWorker;
    @Resource private OutboxService outboxService;
    @Autowired(required = false) private StringRedisTemplate stringRedisTemplate;
    @Autowired(required = false)
    @Qualifier("flashReservationScript")
    private DefaultRedisScript<Long> reservationScript;
    @Autowired(required = false)
    @Qualifier("flashReservationReleaseScript")
    private DefaultRedisScript<Long> releaseScript;
    @Autowired(required = false)
    @Qualifier("flashReservationCommitScript")
    private DefaultRedisScript<Long> commitScript;

    private static final long RESERVATION_LEASE_SECONDS = 90L;

    @Transactional
    public Result accept(Long dealId, Long userId) {
        if (dealId == null || userId == null) {
            throw new ValidationException("活动和用户不能为空");
        }
        FlashOrderIntent existing = intentMapper.selectOne(new LambdaQueryWrapper<FlashOrderIntent>()
                .eq(FlashOrderIntent::getVoucherId, dealId)
                .eq(FlashOrderIntent::getUserId, userId)
                .last("FOR UPDATE"));
        if (existing != null) {
            return Result.ok(existing.getOrderId().toString());
        }
        FlashDeal deal = flashDealMapper.selectById(dealId);
        LocalDateTime now = LocalDateTime.now();
        if (deal == null || deal.getBeginTime() == null || deal.getEndTime() == null) {
            return Result.fail("活动信息不可用");
        }
        if (now.isBefore(deal.getBeginTime())) {
            return Result.fail("秒杀尚未开始");
        }
        if (now.isAfter(deal.getEndTime())) {
            return Result.fail("秒杀已经结束");
        }
        Long orderId = redisIdWorker.getNextId("order");
        Reservation reservation = reserve(dealId, userId, orderId);
        if (reservation.outOfStock()) {
            return Result.fail("Out of stock");
        }
        if (reservation.existingOrderId() != null) {
            return Result.ok(reservation.existingOrderId().toString());
        }
        boolean dbStockDecremented = false;
        try {
            int updated = flashDealMapper.decrementStock(dealId);
            if (updated != 1) {
                releaseReservation(dealId, userId, orderId, reservation.reserved());
                return Result.fail("Out of stock");
            }
            dbStockDecremented = true;
            FlashOrderIntent intent = new FlashOrderIntent();
            intent.setOrderId(orderId);
            intent.setUserId(userId);
            intent.setVoucherId(dealId);
            intent.setStatus("ACCEPTED");
            intent.setAcceptedAt(now);
            int inserted = intentMapper.insertIgnoreIntent(intent);
            if (inserted != 1) {
                // A concurrent request won the unique (user, deal) race. Return
                // its durable order and put back the stock unit reserved by this
                // transaction before leaving the acceptance boundary.
                dbStockDecremented = false;
                flashDealMapper.incrementStock(dealId);
                releaseReservation(dealId, userId, orderId, reservation.reserved());
                FlashOrderIntent winner = intentMapper.selectOne(new LambdaQueryWrapper<FlashOrderIntent>()
                        .eq(FlashOrderIntent::getVoucherId, dealId)
                        .eq(FlashOrderIntent::getUserId, userId));
                if (winner != null) {
                    return Result.ok(winner.getOrderId().toString());
                }
                throw new IllegalStateException("flash order intent race could not be reconciled");
            }
            FlashDealOrderMessage message = new FlashDealOrderMessage(orderId, userId, dealId,
                    System.currentTimeMillis());
            outboxService.record("intent-" + orderId,
                    cn.hutool.json.JSONUtil.toJsonStr(message), OutboxStatus.PENDING);
            registerReservationCommit(dealId, userId, orderId, reservation.reserved());
            return Result.ok(orderId.toString());
        } catch (RuntimeException e) {
            if (dbStockDecremented) {
                try {
                    flashDealMapper.incrementStock(dealId);
                } catch (RuntimeException ignored) {
                    // The surrounding transaction will roll back the decrement;
                    // keep the original failure visible to the caller.
                }
            }
            releaseReservation(dealId, userId, orderId, reservation.reserved());
            throw e;
        }
    }

    private Reservation reserve(Long dealId, Long userId, Long orderId) {
        if (stringRedisTemplate == null || reservationScript == null) {
            // MySQL conditional decrement remains a safe availability fallback
            // when Redis is intentionally disabled or temporarily unavailable.
            return Reservation.unavailable();
        }
        try {
            Long result = stringRedisTemplate.execute(reservationScript,
                    java.util.List.of(stockKey(dealId), reservationKey(orderId), reservedUsersKey(dealId)),
                    orderId.toString(), userId.toString(), Long.toString(RESERVATION_LEASE_SECONDS));
            if (Long.valueOf(-1L).equals(result)) return Reservation.outOfStockResult();
            if (Long.valueOf(-3L).equals(result)) return Reservation.unavailable();
            if (Long.valueOf(-2L).equals(result)) {
                Object existing = stringRedisTemplate.opsForHash().get(reservedUsersKey(dealId), userId.toString());
                try {
                    return existing == null ? Reservation.unavailable()
                            : Reservation.existing(Long.valueOf(existing.toString()));
                } catch (NumberFormatException ignored) {
                    return Reservation.unavailable();
                }
            }
            return Long.valueOf(1L).equals(result) ? Reservation.created() : Reservation.unavailable();
        } catch (Exception e) {
            // Availability of Redis must not turn a durable MySQL acceptance
            // into a false success or an unbounded retry loop.
            return Reservation.unavailable();
        }
    }

    private void releaseReservation(Long dealId, Long userId, Long orderId, boolean reserved) {
        if (!reserved || stringRedisTemplate == null || releaseScript == null) return;
        try {
            stringRedisTemplate.execute(releaseScript,
                    java.util.List.of(reservationKey(orderId), reservedUsersKey(dealId), stockKey(dealId)),
                    userId.toString(), orderId.toString());
        } catch (Exception ignored) {
            // Recovery/rebuild will reconcile the Redis mirror from MySQL.
        }
    }

    private void registerReservationCommit(Long dealId, Long userId, Long orderId, boolean reserved) {
        if (!reserved || stringRedisTemplate == null || commitScript == null) return;
        Runnable commit = () -> {
            try {
                stringRedisTemplate.execute(commitScript,
                        java.util.List.of(reservationKey(orderId), reservedUsersKey(dealId),
                                com.campusdeal.utils.RedisConstants.FLASH_DEAL_ORDER_KEY + dealId),
                        userId.toString());
            } catch (Exception ignored) {
                // The DB intent is authoritative; the next reconciliation can
                // rebuild the purchaser mirror after a Redis outage.
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { commit.run(); }
            });
        } else {
            commit.run();
        }
    }

    private String stockKey(Long dealId) {
        return com.campusdeal.utils.RedisConstants.FLASH_DEAL_STOCK_KEY + dealId;
    }

    private String reservationKey(Long orderId) {
        return "flashdeal:reservation:" + orderId;
    }

    private String reservedUsersKey(Long dealId) {
        return "flashdeal:reserved:" + dealId;
    }

    private record Reservation(boolean reserved, boolean outOfStock, Long existingOrderId) {
        static Reservation created() { return new Reservation(true, false, null); }
        static Reservation unavailable() { return new Reservation(false, false, null); }
        static Reservation outOfStockResult() { return new Reservation(false, true, null); }
        static Reservation existing(Long orderId) { return new Reservation(false, false, orderId); }
    }

    @Transactional(readOnly = true)
    public Result status(Long orderId, Long userId) {
        if (orderId == null || userId == null) {
            throw new ValidationException("订单和用户不能为空");
        }
        FlashOrderIntent intent = intentMapper.selectById(orderId);
        if (intent != null) {
            if (!userId.equals(intent.getUserId())) {
                throw new com.campusdeal.exception.ForbiddenException();
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("orderId", intent.getOrderId().toString());
            data.put("status", intent.getStatus());
            data.put("reason", intent.getFailureReason());
            return Result.ok(data);
        }
        CouponOrder order = couponOrderMapper.selectById(orderId);
        if (order == null) {
            throw new ValidationException("订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            throw new com.campusdeal.exception.ForbiddenException();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.getId().toString());
        data.put("status", "SUCCEEDED");
        data.put("reason", null);
        return Result.ok(data);
    }

    /**
     * Read-only comparison used during a staged cutover. It deliberately does
     * not reserve stock or create a durable intent, so enabling shadow mode
     * cannot change admission semantics. The durable side is based on the
     * conditional MySQL stock value; the legacy side is the Redis mirror and
     * purchaser set that the Lua admission path would inspect.
     */
    public ShadowComparison compareLegacyAdmission(Long dealId, Long userId) {
        FlashDeal deal = flashDealMapper.selectById(dealId);
        if (deal == null || deal.getStock() == null) {
            return new ShadowComparison("UNAVAILABLE", "UNAVAILABLE", false);
        }
        String stock = null;
        boolean duplicate = false;
        if (stringRedisTemplate != null) {
            try {
                stock = stringRedisTemplate.opsForValue().get(stockKey(dealId));
                Boolean member = stringRedisTemplate.opsForSet()
                        .isMember(com.campusdeal.utils.RedisConstants.FLASH_DEAL_ORDER_KEY + dealId,
                                String.valueOf(userId));
                duplicate = Boolean.TRUE.equals(member);
            } catch (Exception ignored) {
                return new ShadowComparison("UNAVAILABLE", deal.getStock() > 0 ? "AVAILABLE" : "SOLD_OUT", true);
            }
        }
        String legacy = duplicate ? "DUPLICATE" : parseStockDecision(stock);
        String durable = deal.getStock() > 0 ? "AVAILABLE" : "SOLD_OUT";
        return new ShadowComparison(legacy, durable, !legacy.equals(durable));
    }

    private String parseStockDecision(String stock) {
        if (stock == null || stock.isBlank()) return "UNAVAILABLE";
        try {
            return Long.parseLong(stock) > 0 ? "AVAILABLE" : "SOLD_OUT";
        } catch (NumberFormatException ignored) {
            return "UNAVAILABLE";
        }
    }

    public record ShadowComparison(String legacyDecision, String durableDecision, boolean discrepant) { }
}
