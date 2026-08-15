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
import com.campusdeal.mq.FlashDealConsumer;
import com.campusdeal.mq.FlashDealOrderMessage;
import com.campusdeal.mq.FlashDealProducer;
import com.campusdeal.mq.OutboxStatus;
import com.campusdeal.seckill.FlashDealContext;
import com.campusdeal.service.IFlashDealService;
import com.campusdeal.service.OutboxService;
import com.campusdeal.utils.RedisConstants;
import com.campusdeal.utils.RedisIdWorker;
import com.campusdeal.utils.UserHolder;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
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
    @Resource
    private FlashDealProducer flashDealProducer;
    @Resource
    private FlashDealConsumer flashDealConsumer;
    @Resource
    private OutboxService outboxService;
    @Resource
    private CouponOrderMapper couponOrderMapper;

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
            log.warn("Flash deal stock preload failed at startup: {}", e.getMessage());
        }
    }

    @Override
    public Result executeFlashDeal(Long dealId) {
        FlashDealContext ctx = FlashDealContext.builder()
                .dealId(dealId)
                .userId(UserHolder.getUser().getId())
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
        if (timeRange != null) {
            String[] parts = timeRange.split("\\|");
            if (parts.length == 2) {
                try {
                    long now = System.currentTimeMillis();
                    long begin = Long.parseLong(parts[0]);
                    long end = Long.parseLong(parts[1]);
                    if (now < begin) {
                        return Result.fail("秒杀尚未开始");
                    }
                    if (now > end) {
                        return Result.fail("秒杀已经结束");
                    }
                } catch (NumberFormatException ignore) {
                    // 时间窗格式异常时放行，由后续层兜底
                }
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
            stockCache.put(ctx.getDealId(), false);  // L1 负缓存：后续请求快速失败
            // T16：置 "0" 而非删除——库存 key 显式保持为 0，与 Lua tonumber(stock)<=0 判定一致，
            // 且并发测试/监控可直接断言 flashdeal:stock:{dealId}=0（"已扣到 0"而非"缺失"）。
            stringRedisTemplate.opsForValue().set(RedisConstants.FLASH_DEAL_STOCK_KEY + ctx.getDealId(), "0");
            return Result.fail("Out of stock");
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

        // P0-1/P0-3 修复：同步落库保证订单不丢（Kafka 不可用仍可下单成功）。
        // 幂等由 FlashDealConsumer.processMessage 的 SETNX + MySQL UNIQUE KEY 兜底。
        try {
            flashDealConsumer.processMessage(message);
        } catch (Exception e) {
            // 同步落库失败（如 DB 抖动）：写 Outbox PENDING，交由补偿调度器重试
            log.error("Sync persist order failed: orderId={}", ctx.getOrderId(), e);
            outboxService.record("sync-" + ctx.getOrderId(),
                    JSONUtil.toJsonStr(message), OutboxStatus.PENDING);
        }

        // 尽力而为发送 Kafka（失败只记日志，不回滚；见 FlashDealProducerImpl）
        flashDealProducer.send(message);

        // T16：Lua 返回剩余库存，最后一单（剩余=0）立即写 L1 负缓存，
        // 消除「库存恰好售罄后、L1 仍无 false 记录」的窗口——S2 耗尽压测从第一波即 0 Redis 命中。
        if (ctx.getRemainingStock() == 0) {
            stockCache.put(ctx.getDealId(), false);
        }

        long totalNs = System.nanoTime() - ctx.getStartNanos();
        log.info("Flash deal success: dealId={}, userId={}, orderId={}, " +
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
     * 剩余库存 = 活动库存 - 已落库订单数（秒杀已改同步落库，订单数即已售数）。
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
            Long sold = couponOrderMapper.selectCount(
                    Wrappers.<CouponOrder>lambdaQuery()
                            .eq(CouponOrder::getVoucherId, dealId)
            );
            int remaining = Math.max(deal.getStock() - sold.intValue(), 0);
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
}
