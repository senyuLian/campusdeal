# 05 · 模块测试：秒杀

> 覆盖 seckill.lua 原子脚本契约、三层过滤编排（布隆→Caffeine→Redis+Lua）、
> 同步落库 + Outbox 兜底、RedisIdWorker 全局 ID、并发不超卖、活动时间窗。

## 1. 执行链路

```
POST /coupon-order/seckill/{dealId}
  → FlashDealServiceImpl.executeFlashDeal
    → L0 BloomFilter.mightContain(dealId)        不通过 → fail"Deal not found"
    → L1 stockCache.get(dealId, loader)          false → fail"Out of stock"
    → L2 Redis 执行 seckill.lua                  0/1/2
        SUCCESS → 同步落库 + Kafka 兜底 + Outbox 补偿 → Result.ok(orderId 字符串)
        OUT_OF_STOCK → 回填 L1 false → fail"Out of stock"
        DUPLICATE_ORDER → fail"Already purchased"
```

## 2. seckill.lua 契约

| 项 | 值 |
|---|---|
| 脚本 | `src/main/resources/seckill.lua` |
| KEYS | 不使用（`Collections.emptyList()`） |
| ARGV | `ARGV[1]=dealId`，`ARGV[2]=userId` |
| 内部 key | `flashdeal:stock:{dealId}`（String 库存）、`flashdeal:order:{dealId}`（Set 已购用户） |
| 返回码 | `0`=成功 / `1`=库存不足 / `2`=重复下单 |

执行顺序（原子）：`GET stock` → 不存在或 `<=0` 返回 1 → `SISMEMBER orderKey userId` 命中返回 2 → `INCRBY stock -1` → `SADD orderKey userId` → 返回 0。

> 边界：`stringRedisTemplate.execute` 返回 null（脚本异常）→ 视为 `1`（无库存）。

## 3. Lua 用例（LU）

| ID | 用例 | Redis 状态 | 预期 |
|---|---|---|---|
| LU-01 | 库存充足首单 | stock=10，order 空 | 返回 0；stock=9；order={userId} |
| LU-02 | 库存不足 | stock=0 / key 不存在 | 返回 1 |
| LU-03 | 重复下单 | order 含 userId | 返回 2 |
| LU-04 | 最后一件 | stock=1 | 返回 0；随后再执行返回 1 |

实现方式：`seckill/SeckillLuaScriptTest` 用 **LuaJ** 解释器执行真实脚本（mock `redis.call`），
无需 Redis。如需真 Redis 断言原子性，用 Testcontainers 或脚本级压测。

## 4. 编排用例（FD / CO1）

### 4.1 三层过滤

| ID | 用例 | Mock/状态 | 预期 |
|---|---|---|---|
| FD-01 | 布隆拒绝 | `mightContain=false` | fail"Deal not found"，不查 Redis、不调 Lua |
| FD-02 | L1 无库存 | `stockCache` 命中 false | fail"Out of stock"，不调 Lua |
| FD-03 | 成功路径 | Lua 返回 0 | 同步落库（Mock Consumer）→ 返回 orderId 字符串 |
| FD-04 | 库存不足 | Lua 返回 1 | fail"Out of stock"；`stockCache` 回填 false |
| FD-05 | 重复下单 | Lua 返回 2 | fail"Already purchased" |

> ⚠️ FD-03 现测试未 Mock `FlashDealConsumer`/`OutboxService`，同步落库改造后会 NPE（T2）。
> 修复后再跑。

### 4.2 同步落库与补偿

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| SC-01 | 落库成功 | 秒杀成功 | `tb_voucher_order` 有订单；Kafka send 尽力而为 |
| SC-02 | 落库失败（DB 抖动） | 模拟 insert 异常 | 写 Outbox PENDING（`sync-{orderId}`），调度器补偿 |
| SC-03 | Kafka 不可用 | 停 Kafka 秒杀 | **不阻塞**（后台线程异步发送），响应 <100ms |
| SC-04 | 幂等兜底 | 同一 orderId 重投 | SETNX / 主键冲突，不重复插单 |

### 4.3 活动时间窗

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| TW-01 | 未开始 | beginTime 在未来 | fail（旧链路校验） |
| TW-02 | 已结束 | endTime 已过 | fail |
| TW-03 | 进行中 | 时间窗内 | 正常秒杀 |

## 5. 并发与一致性用例

| ID | 用例 | 配置 | 预期 |
|---|---|---|---|
| CC-01 | 30 用户抢 10 库存 | 30 个不同用户 | 恰 10 成功、20 fail、0 异常、无超卖 |
| CC-02 | 同用户并发 | 同一用户并发 | 只成功 1 单，其余 fail"Already purchased" |
| CC-03 | 对账 | 压测后 | DB 订单数 = 活动库存 - Redis 剩余库存 |
| CC-04 | 库存为 0 后续请求 | 库存耗尽后 | L1 命中 false，快速 fail |

> 运行：`node tools/concurrency-test.js`（脚本级 E2E，7 项断言）。

## 6. RedisIdWorker 用例

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| ID-01 | 唯一性 | 300 线程 × 100 次 `getNextId("order")` | 无重复（已有 `CampusDealApplicationIT.testRedisWorker`） |
| ID-02 | 趋势递增 | 同 keyPrefix 连续取 | 时间戳高位相同，序列递增 |
| ID-03 | 跨天序列 | 换日期 | 序列重置（`icr:{prefix}:{yyyy:MM:dd}`） |
| ID-04 | 位数 | 观察值 | 17 位左右，超 JS 精度 → **以字符串下发**（P2-6） |

## 7. 启动预热用例

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| PL-01 | 启动预热 | 重启应用 | 未过期活动按 `剩余=活动库存-已售订单数` 写 Redis（`setIfAbsent`） |
| PL-02 | Redis 重启恢复 | 清 Redis 库存后重启 | `flashdeal:stock:{dealId}` 恢复（P1-7） |
| PL-03 | 已存在库存不覆盖 | Redis 已有在途库存 | `setIfAbsent` 不覆盖 |
| PL-04 | 预热失败不阻塞 | DB/Redis 异常 | 仅 warn，应用正常启动 |

## 8. 旧链路（Redisson 锁版）说明

`CouponOrderServiceImpl.seckillVoucher`（Redisson `lock:order:{userId}` + `AopContext` 事务）
仍是已存在实现，但当前秒杀主链路已切到 Lua 版。测试口径：

- 保留回归：时间窗校验、一人一单（`count>0` fail）、`stock>0` 原子扣减。
- 不冲突：Lua 版走 `flashdeal:order:{dealId}` 幂等，旧版走 DB count + 行锁。

## 9. 验收标准

- 秒杀响应 <100ms（Kafka 不可用不阻塞）。
- 任意并发下**不超卖、不重复、不丢单**。
- Lua 原子扣减正确；布隆/Caffeine 拦截率达标。
- Redis 重启后库存自动恢复；订单对账一致。
- `FlashDealContext` 各层耗时（bloom/caffeine/lua ns）在日志可观测。
