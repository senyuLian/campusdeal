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
import com.campusdeal.seckill.FlashDealResult;
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

        // === Layer 1: Caffeine L1 ===
        long t1 = System.nanoTime();
        Boolean hasStock = stockCache.get(dealId, id ->
                stringRedisTemplate.opsForValue()
                        .get(RedisConstants.FLASH_DEAL_STOCK_KEY + id) != null
        );
        if (Boolean.FALSE.equals(hasStock)) {
            return Result.fail("Out of stock");
        }
        ctx.setCaffeineCostNs(System.nanoTime() - t1);

        // === Layer 2: Redis + Lua ===
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
        return result != null ? result.intValue() : 1; // null = 脚本错误，视为无库存
    }

    private Result handleResult(int code, FlashDealContext ctx) {
        FlashDealResult fr = FlashDealResult.fromCode(code);
        switch (fr) {
            case SUCCESS:
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

                log.info("Flash deal success: dealId={}, userId={}, orderId={}, " +
                                "bloom={}ns, caffeine={}ns, lua={}ns",
                        ctx.getDealId(), ctx.getUserId(), ctx.getOrderId(),
                        ctx.getBloomCostNs(), ctx.getCaffeineCostNs(), ctx.getLuaCostNs());
                // P2-6 修复：雪花 ID 17 位超 JS Number 精度(2^53)，以字符串下发，
                // 避免前端 JSON.parse 把尾位舍入（如 ...116 被解析为 ...110）。
                return Result.ok(ctx.getOrderId().toString());

            case OUT_OF_STOCK:
                stockCache.put(ctx.getDealId(), false);  // 更新本地缓存
                return Result.fail("Out of stock");

            case DUPLICATE_ORDER:
                return Result.fail("Already purchased");

            default:
                return Result.fail("System error");
        }
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
        }
        log.info("Flash deal stock preloaded: {} active deals", activeDeals.size());
    }
}
