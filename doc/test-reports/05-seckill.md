# 测试报告 05 · 模块测试：秒杀

> 日期：2026-08-15 ｜ 依据：`doc/test-plan/05-module-seckill.md`
> 环境：MySQL :3306 ✅、Redis :6379 ✅、后端 :8081 ✅

## 1. 执行范围与结果

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `SeckillLuaScriptTest` | LU-01..04（真实 seckill.lua via LuaJ） | ✅ 4/4 |
| `FlashDealServiceImplTest` | FD-01..05 三层编排 + SC-02 落库失败补偿 + **TW-01..03 新增时间窗** | ✅ 9/9 |
| `FlashDealProducerTest` | KP-01..03（Kafka 生产者新契约） | ✅ 3/3 |
| `tools/concurrency-test.js` | CC-01..04 并发不超卖（30 用户 × 10 库存） | ✅ 7/7（61ms） |
| `tools/seckill-timewindow.js`（新增） | TW-01/02/03 活动时间窗运行时 | ✅ 4/4 |

**合计：单元 16/16 + 并发 7/7 + 时间窗 4/4 通过。**

> 全量单测 **140/140** 通过（基线 128 含 3 个 broken，本次修复 T1/T2 + 新增 12 用例）。

## 2. 测试中发现并修复的问题

### 2.1 修复 1（T1 · FlashDealProducerTest 过期契约）

**现象**：`shouldSendSuccessfully` / `shouldThrowKafkaExceptionOnFailure` 失败。

**根因**：P0-1 改造后 `FlashDealProducerImpl.send()` 为**异步尽力而为**（fire-and-forget，返回 `null`、不抛异常，Kafka 发送下沉后台线程池），测试仍断言旧契约（返回 SendResult / 抛 KafkaException）。

**修复**（`FlashDealProducerTest`）：KP-01 改为断言 `send` 返回 null + 后台线程 `timeout(500)` 内触达 `KafkaTemplate.send`（校验 topic/userId key/messageId header）；KP-02 改为断言异常被吞、返回 null（降级契约）。

### 2.2 修复 2（T2 · FlashDealServiceImplTest 同步落库改造后 NPE）

**现象**：`shouldReturnOrderIdOnSuccess` 在 `handleResult` 抛 NPE。

**根因**：秒杀成功路径改为**同步落库**（`flashDealConsumer.processMessage`）+ 失败写 Outbox 补偿，测试未 Mock `FlashDealConsumer`/`OutboxService`（均为 null）；且断言仍期待 Long，而 P2-6 已改为返回**字符串** orderId（防 JS 精度丢失）。

**修复**（`FlashDealServiceImplTest`）：补 `@Mock FlashDealConsumer` / `@Mock OutboxService`；FD-03 断言改为字符串 `"20260812000001"`；新增 SC-02（`processMessage` 抛异常 → `outboxService.record("sync-{orderId}", …, PENDING)` 补偿，秒杀仍成功）。

### 2.3 修复 3（T14 · 主链路 Lua 秒杀无活动时间窗校验）

**现象**：`beginTime` 在未来的券可被秒杀；`endTime` 已过的券在布隆过滤器中残留最长 300s 仍可秒杀（布隆按 `endTime>now` 构建且 300s 才重建）。时间窗校验仅存在于旧 Redisson 路径（`CouponOrderServiceImpl`），当前主链路 `FlashDealServiceImpl.executeFlashDeal` 完全没有。

**根因**：三层过滤（布隆→Caffeine→Lua）只校验「存在性 + 库存 + 幂等」，无活动起止时间判断。

**修复**（`FlashDealServiceImpl` + `CouponServiceImpl`）：
- 新增 Redis key `flashdeal:time:{dealId}` = `{beginEpoch}|{endEpoch}`；
- `executeFlashDeal` 在布隆之后、L1 之前读该 key：`now<begin → "秒杀尚未开始"`，`now>end → "秒杀已经结束"`（单个 GET ~0.1ms，不穿透 DB）；key 缺失时跳过（兼容旧数据）；
- 写入：启动预热 `preloadAllActiveStock`（`setIfAbsent`）+ 创建 `addFlashDeal`（`set`）。

**验证**：单测 TW-01/02/03 ✅；运行时 `tools/seckill-timewindow.js`：未开始/已结束被拒、进行中成功 ✅。

## 3. 关键场景记录

| 场景 | 结果 |
|---|---|
| Lua 原子契约：库存充足=0 / 库存不足=1 / 重复=2 / 最后一件扣到 0 | ✅ |
| 布隆拒绝 → 不触 Redis/Lua；L1 无库存 → 不触 Lua | ✅ |
| 秒杀成功 → 同步落库 + 尽力而为 Kafka + 返回字符串 orderId | ✅ |
| 落库失败 → Outbox PENDING 补偿，秒杀仍成功 | ✅ |
| 并发 30 用户抢 10 库存 → 恰 10 成功 / 20 OutOfStock / 0 重复 / DB 增量=10（不超卖） | ✅ 61ms |
| 未开始/已结束拦截（单测 + 运行时） | ✅ |
| Kafka 不可用不阻塞（send 返回 null，60ms 级响应） | ✅ |

## 4. 遗留观察

| 项 | 说明 |
|---|---|
| 新建秒杀券的布隆时效性 | 新券需等 300s 定时重建才进入布隆过滤器（BF-05 设计）；创建后立即秒杀会得到 "Deal not found"，属既有取舍 |
| 时间窗 key 缺失时跳过校验 | 兼容未预热的历史数据；重启后由 `preloadAllActiveStock` 补齐 |
| deal=12 库存被压测消耗 | 测试结束已恢复 `flashdeal:stock:12=5` 并清理去重集合 |

## 5. 模块结论

**秒杀模块通过。** Lua 原子扣减、三层过滤编排、同步落库 + Outbox 补偿、并发不超卖（61ms / 30 并发）、Kafka 降级全部验证通过；修复 T1/T2 两个过期测试 + **T14 活动时间窗缺失**（未开始/已结束的券现在会被拦截）。

> 改动文件：`src/main/java/com/campusdeal/utils/RedisConstants.java`、`src/main/java/com/campusdeal/service/impl/FlashDealServiceImpl.java`、`src/main/java/com/campusdeal/service/impl/CouponServiceImpl.java`；
> 测试：`src/test/java/com/campusdeal/seckill/FlashDealServiceImplTest.java`、`src/test/java/com/campusdeal/mq/FlashDealProducerTest.java`；新增脚本 `tools/seckill-timewindow.js`。
