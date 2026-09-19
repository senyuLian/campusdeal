package com.campusdeal.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campusdeal.cache.BloomFilterService;
import com.campusdeal.dto.Result;
import com.campusdeal.entity.CouponOrder;
import com.campusdeal.entity.FlashDeal;
import com.campusdeal.mapper.CouponOrderMapper;
import com.campusdeal.mapper.FlashDealMapper;
import com.campusdeal.mq.FlashDealOrderMessage;
import com.campusdeal.mq.FlashDealProducer;
import com.campusdeal.mq.OutboxStatus;
import com.campusdeal.seckill.FlashDealContext;
import com.campusdeal.service.IFlashDealService;
import com.campusdeal.service.OutboxService;
import com.campusdeal.utils.RedisConstants;
import com.campusdeal.utils.RedisIdWorker;
import com.campusdeal.utils.UserHolder;
import com.campusdeal.security.AuthorizationService;
import com.campusdeal.security.SensitiveLogSanitizer;
import com.campusdeal.service.FlashOrderIntentService;
import com.campusdeal.entity.FlashOrderIntent;
import com.campusdeal.mapper.FlashOrderIntentMapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

/**
 * 秒杀主流程编排：布隆过滤 → Caffeine L1 → Redis+Lua 原子扣减
 */
@Slf4j
@Service
public class FlashDealServiceImpl extends ServiceImpl<FlashDealMapper, FlashDeal>
        implements IFlashDealService, InitializingBean {

    @Resource
    private BloomFilterService bloomFilter;
    @Resource
    private Cache<Long, Boolean> stockCache;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private DefaultRedisScript<Long> flashDealScript;
    @Autowired(required = false)
    private FlashDealProducer flashDealProducer;
    @Resource
    private OutboxService outboxService;
    @Resource
    private CouponOrderMapper couponOrderMapper;
    @Resource
    private AuthorizationService authorizationService;
    @Resource
    private FlashOrderIntentService flashOrderIntentService;
    @Resource
    private FlashOrderIntentMapper flashOrderIntentMapper;

    @Value("${campusdeal.flashdeal.durable-acceptance-enabled:true}")
    private boolean durableAcceptanceEnabled = true;
    @Value("${campusdeal.flashdeal.durable-acceptance-cutover-enabled:true}")
    private boolean durableAcceptanceCutoverEnabled = true;
    @Value("${campusdeal.flashdeal.durable-acceptance-shadow-enabled:false}")
    private boolean durableAcceptanceShadowEnabled = false;

    /**
     * P1-7：启动时预热所有未过期秒杀活动的剩余库存到 Redis，
     * 修复 Redis 重启后 flashdeal:stock:* 为空导致秒杀全部 Out of stock。
     */
    @Override
    public void afterPropertiesSet() {
        try {
            preloadAllActiveStock();
        } catch (Exception e) {
            // 启动时 DB/Redis 异常不应阻塞应用：记录告警，等待运维手动预热
            log.warn("Flash deal stock preload failed at startup: {}", SensitiveLogSanitizer.exceptionSummary(e));
        }
    }

    @Override
    public Result executeFlashDeal(Long dealId) {
        var user = authorizationService == null
                ? UserHolder.getUser()
                : authorizationService.requireAuthenticated();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        // Durable acceptance is the application default. Tests and a staged
        // rollback can leave this collaborator unwired and use the legacy Lua
        // admission path below.
        if (durableAcceptanceShadowEnabled && flashOrderIntentService != null) {
            FlashOrderIntentService.ShadowComparison comparison =
                    flashOrderIntentService.compareLegacyAdmission(dealId, user.getId());
            if (comparison.discrepant()) {
                log.warn("Flash acceptance shadow discrepancy: dealId={}, userId={}, legacy={}, durable={}",
                        dealId, user.getId(), comparison.legacyDecision(), comparison.durableDecision());
            }
        }
        if (durableAcceptanceEnabled && durableAcceptanceCutoverEnabled && flashOrderIntentService != null) {
            return flashOrderIntentService.accept(dealId, user.getId());
        }
        FlashDealContext ctx = FlashDealContext.builder()
                .dealId(dealId)
                .userId(user.getId())
                .startNanos(System.nanoTime())
                .build();

        // === Layer 0: Bloom filter ===
        long t0 = System.nanoTime();
        if (!bloomFilter.mightContain(dealId)) {
            return Result.fail("Deal not found");
        }
        ctx.setBloomCostNs(System.nanoTime() - t0);

        // === Layer 1: Caffeine L1（负缓存快速失败，置于时间窗之前） ===
        long t1 = System.nanoTime();
        // T15：只缓存 false（无库存）用于快速失败；true 一律不缓存、每请求回源 Redis/Lua 复核，
        // 避免「最后一件售罄后 L1 仍缓存 true」的窗口把所有请求打进 Lua。
        // T16：L1 命中 false 时直接失败，跳过时间窗/库存的 Redis 往返（耗尽后 0 Redis 命中）。
        // 1s TTL 内「恢复库存」会被短暂拒绝——可接受的最终一致窗口，换取耗尽后快速失败。
        Boolean cachedStock = stockCache.getIfPresent(dealId);
        if (Boolean.FALSE.equals(cachedStock)) {
            ctx.setCaffeineCostNs(System.nanoTime() - t1);
            return Result.fail("Out of stock");
        }
        ctx.setCaffeineCostNs(System.nanoTime() - t1);

        // === 活动时间窗校验（TW-01/02）：preheat/创建时写入 flashdeal:time:{dealId} ===
        // 布隆过滤器只按 endTime>now 构建且 300s 才重建：未开始的券、刚结束的券在下次重建前
        // 都会误放行，必须在此用 Redis 时间窗拦截（单个 GET，~0.1ms，不穿透 DB）。
        String timeRange = stringRedisTemplate.opsForValue().get(RedisConstants.FLASH_DEAL_TIME_KEY + dealId);
        if (timeRange == null) {
            // Production beans always have a MyBatis base mapper.  Keep the
            // Redis-only legacy path usable in isolated unit tests/staged
            // rollbacks where the service is constructed without one.
            if (getBaseMapper() != null) {
                FlashDeal deal = getById(dealId);
                if (deal == null || deal.getBeginTime() == null || deal.getEndTime() == null) {
                    return Result.fail("活动信息不可用");
                }
                long now = System.currentTimeMillis();
                long begin = deal.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long end = deal.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                if (now < begin) return Result.fail("秒杀尚未开始");
                if (now > end) return Result.fail("秒杀已经结束");
                stringRedisTemplate.opsForValue().set(RedisConstants.FLASH_DEAL_TIME_KEY + dealId, begin + "|" + end);
            }
        } else {
            String[] parts = timeRange.split("\\|");
            if (parts.length != 2) {
                return Result.fail("活动信息不可用");
            }
            try {
                long now = System.currentTimeMillis();
                long begin = Long.parseLong(parts[0]);
                long end = Long.parseLong(parts[1]);
                if (begin >= end) return Result.fail("活动信息不可用");
                if (now < begin) return Result.fail("秒杀尚未开始");
                if (now > end) return Result.fail("秒杀已经结束");
            } catch (NumberFormatException malformed) {
                return Result.fail("活动信息不可用");
            }
        }

        String stockKey = RedisConstants.FLASH_DEAL_STOCK_KEY + dealId;
        Boolean stockPresent = stringRedisTemplate.hasKey(stockKey);
        if (Boolean.FALSE.equals(stockPresent) && getBaseMapper() != null) {
            FlashDeal deal = getById(dealId);
            if (deal == null || deal.getStock() == null) return Result.fail("活动信息不可用");
            int remaining = remainingStockFromDb(dealId, deal);
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(remaining));
            if (remaining <= 0) {
                stockCache.put(dealId, false);
                return Result.fail("Out of stock");
            }
        }

        // === Layer 2: Redis + Lua（原子扣减；库存校验与去重都在脚本内完成） ===
        // T16：去掉 Java 侧预读库存 GET——Lua 脚本内部已做库存校验并返回剩余值，
        // 成功路径减少 1 次 Redis 往返（30 并发尾延迟约 -12ms）。
        long t2 = System.nanoTime();
        int result = executeLuaScript(ctx);
        ctx.setLuaCostNs(System.nanoTime() - t2);

        return handleResult(result, ctx);
    }

    private int executeLuaScript(FlashDealContext ctx) {
        Long result = stringRedisTemplate.execute(
                flashDealScript,
                Collections.emptyList(),   // KEYS: 不使用
                ctx.getDealId().toString(),
                ctx.getUserId().toString()
        );
        return result != null ? result.intValue() : -1; // null = 脚本错误，视为无库存
    }

    private Result handleResult(int code, FlashDealContext ctx) {
        // seckill.lua 新契约（T16）：>=0 成功（值 = 剩余库存），-1 无库存，-2 重复下单
        if (code >= 0) {
            ctx.setRemainingStock(code);
            return handleSuccess(ctx);
        }
        if (code == -1) {
            String stockKey = RedisConstants.FLASH_DEAL_STOCK_KEY + ctx.getDealId();
            String stockValue = null;
            try {
                stockValue = stringRedisTemplate.opsForValue().get(stockKey);
            } catch (Exception ignored) {
                // A Redis outage is an unavailable admission decision, not
                // evidence that the configured deal is sold out.
            }
            if (stockValue == null || stockValue.isBlank()) {
                return Result.fail("库存状态暂不可用");
            }
            try {
                if (Long.parseLong(stockValue) <= 0) {
                    stockCache.put(ctx.getDealId(), false);  // bounded negative cache
                    // Keep an explicit zero only for a confirmed stock key;
                    // a missing key must never become a permanent sold-out marker.
                    stringRedisTemplate.opsForValue().set(stockKey, "0");
                    return Result.fail("Out of stock");
                }
            } catch (NumberFormatException ignored) {
                return Result.fail("库存状态暂不可用");
            }
            return Result.fail("库存状态暂不可用");
        }
        if (code == -2) {
            return Result.fail("Already purchased");
        }
        return Result.fail("System error");
    }

    private Result handleSuccess(FlashDealContext ctx) {
        ctx.setOrderId(redisIdWorker.getNextId("order"));
        FlashDealOrderMessage message = new FlashDealOrderMessage(
                ctx.getOrderId(), ctx.getUserId(), ctx.getDealId(),
                System.currentTimeMillis());

        // 异步落库改造：秒杀主流程只「受理订单」——同步确认投递 Kafka，
        // 真正的 MySQL 落库由 FlashDealConsumer 异步执行（SETNX 幂等 + UNIQUE KEY 兜底）。
        // 投递失败则写 Outbox PENDING，交由 OutboxScheduler 最终落库，保证订单不丢。
        if (flashDealProducer == null || !flashDealProducer.send(message)) {
            outboxService.record("sync-" + ctx.getOrderId(),
                    JSONUtil.toJsonStr(message), OutboxStatus.PENDING);
        }

        // T16：Lua 返回剩余库存，最后一单（剩余=0）立即写 L1 负缓存，
        // 消除「库存恰好售罄后、L1 仍无 false 记录」的窗口——S2 耗尽压测从第一波即 0 Redis 命中。
        if (ctx.getRemainingStock() == 0) {
            stockCache.put(ctx.getDealId(), false);
        }

        long totalNs = System.nanoTime() - ctx.getStartNanos();
        log.info("Flash deal accepted: dealId={}, userId={}, orderId={}, " +
                        "bloom={}ns, caffeine={}ns, lua={}ns, total={}ns",
                ctx.getDealId(), ctx.getUserId(), ctx.getOrderId(),
                ctx.getBloomCostNs(), ctx.getCaffeineCostNs(), ctx.getLuaCostNs(), totalNs);
        // P2-6 修复：雪花 ID 17 位超 JS Number 精度(2^53)，以字符串下发，
        // 避免前端 JSON.parse 把尾位舍入（如 ...116 被解析为 ...110）。
        return Result.ok(ctx.getOrderId().toString());
    }

    @Override
    public FlashDeal getActiveDeal(Long dealId) {
        return getById(dealId);
    }

    @Override
    public void preloadStock(Long dealId, Integer stock) {
        stringRedisTemplate.opsForValue()
                .set(RedisConstants.FLASH_DEAL_STOCK_KEY + dealId, stock.toString());
    }

    /**
     * P1-7：预热所有未过期秒杀活动的「剩余库存」到 Redis。
     * 剩余库存 = 活动库存 - 已落库订单数（秒杀已改 Kafka 异步落库，订单数即已售数）。
     * 使用 setIfAbsent，避免 Redis 未重启时覆盖在途库存。
     */
    public void preloadAllActiveStock() {
        List<FlashDeal> activeDeals = list(
                Wrappers.<FlashDeal>lambdaQuery()
                        .gt(FlashDeal::getEndTime, LocalDateTime.now())
        );
        for (FlashDeal deal : activeDeals) {
            Long dealId = deal.getVoucherId();
            if (dealId == null || deal.getStock() == null) {
                continue;
            }
            int remaining = remainingStockFromDb(dealId, deal);
            stringRedisTemplate.opsForValue().setIfAbsent(
                    RedisConstants.FLASH_DEAL_STOCK_KEY + dealId,
                    String.valueOf(remaining)
            );
            // T14：预写活动时间窗（begin|end epoch 毫秒），供 executeFlashDeal 做未开始/已结束拦截
            if (deal.getBeginTime() != null && deal.getEndTime() != null) {
                stringRedisTemplate.opsForValue().setIfAbsent(
                        RedisConstants.FLASH_DEAL_TIME_KEY + dealId,
                        deal.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                + "|" + deal.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                );
            }
        }
        log.info("Flash deal stock preloaded: {} active deals", activeDeals.size());
    }

    private Long countInFlightIntents(Long dealId) {
        if (flashOrderIntentMapper == null) return 0L;
        return flashOrderIntentMapper.selectCount(Wrappers.<FlashOrderIntent>lambdaQuery()
                .eq(FlashOrderIntent::getVoucherId, dealId)
                .in(FlashOrderIntent::getStatus, "ACCEPTED", "PROCESSING"));
    }

    /**
     * In durable mode tb_seckill_voucher.stock is decremented inside the
     * acceptance transaction and is already the remaining authoritative stock.
     * The legacy path keeps the original stock value, so only that path needs
     * to subtract durable orders/intents during Redis reconstruction.
     */
    private int remainingStockFromDb(Long dealId, FlashDeal deal) {
        if (durableAcceptanceEnabled && flashOrderIntentMapper != null) {
            return Math.max(deal.getStock() == null ? 0 : deal.getStock(), 0);
        }
        Long sold = couponOrderMapper.selectCount(Wrappers.<CouponOrder>lambdaQuery()
                .eq(CouponOrder::getVoucherId, dealId));
        Long accepted = countInFlightIntents(dealId);
        return Math.max((deal.getStock() == null ? 0 : deal.getStock())
                - (sold == null ? 0 : sold.intValue())
                - (accepted == null ? 0 : accepted.intValue()), 0);
    }
}
