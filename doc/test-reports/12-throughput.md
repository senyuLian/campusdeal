# 测试报告 12 · 异步落库（Kafka）与消费者吞吐优化

> 日期：2026-08-23 ｜ 依据：`doc/test-plan/12-throughput-benchmark.md`（TP-03 秒杀吞吐）、`doc/test-plan/06-module-consistency.md`
> 环境：JDK 17（通过 `JAVA_HOME` 配置）✅、MySQL :3306 ✅、Redis :6379（密码由环境变量注入）✅、后端 :8081 ✅、Kafka KRaft 单节点 :9092 ✅

## 1. 背景与目标

原秒杀下单在 `FlashDealServiceImpl.handleSuccess` 内**同步内联 MySQL INSERT**，落库串在秒杀主链路上，是吞吐瓶颈（异步化后消费者侧初版 ~250 TPS，远低于生产 ~974 TPS）。本次完成两件事：

1. **异步落库改造**：秒杀主流程只「受理订单」——同步确认投递 Kafka（`acks=all`，Kafka 成为订单持久化点），真正的 MySQL 落库由 `FlashDealConsumer` 异步执行（SETNX 幂等 + `uk_user_voucher` UNIQUE KEY 兜底 + Outbox 补偿）。
2. **消费者吞吐优化**：concurrency 3→6 匹配 6 分区、批量消费 + 单条多行 `INSERT IGNORE`、成功路径不再写 Outbox PROCESSED，并调优 fetch 聚合批次。

## 2. 执行范围与结果

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `FlashDealConsumerTest` | KB-01..08 批量消费 / 幂等 / 补偿 / Redis 降级 | ✅ 8/8 |
| `FlashDealProducerTest` | 生产者 send→boolean 同步确认契约 | ✅ |
| `FlashDealServiceImplTest` | 移除内联落库后的秒杀主流程 | ✅ |
| 全量单测 | 全部 `*Test.java`（JDK17） | ✅ 174/174 |
| `tools/throughput-test.js` TP-03 | 秒杀 100 并发 30s、库存 10000、新用户 token 池 | ✅ 落库增量 = 成功下单 |

## 3. 测试中发现并修复的问题

### 3.1 消费者停摆丢单（严重）

**现象**：批优化后的首轮压测（新用户池、库存 10000、100 并发）成功下单 10000，但 DB 落库只增 2839 后**永久停滞**，7161 条卡在 Kafka，后端日志：

```
ListenerExecutionFailedException: onMessage(...) threw exception
Caused by: RedisConnectionFailureException: Unable to connect to Redis
Caused by: BindException: Address already in use: getsockopt: localhost/127.0.0.1:6379
```

**根因**（两层）：
- `FlashDealConsumer.onMessage` 里 `tryMarkBatch`（Redis pipeline）**无 try/catch**，Redis 连接异常直接逃逸出监听器；失败补偿里的 `clearMark` 又二次打 Redis 抛异常 → `ListenerExecutionFailedException` 让 Kafka 容器停摆。
- 底层诱因：`RedisConfig` 固定 `shareNativeConnection=true` 只对单条命令生效，**Lua 扣减脚本与 pipeline 每次新建独占连接**；1000 TPS × 14s 产生 ~1.4 万条连接进入 TIME_WAIT，超出 Windows 临时端口范围，后续建连即 `Address already in use`。

**修复**（`FlashDealConsumer`）：
- Redis 仅作「快速去重」路径，`tryMarkBatch` 失败时**降级为 DB-only**（全部视为首次，由 `INSERT IGNORE` + UNIQUE KEY 在 DB 层去重），绝不因 Redis 抖动中断消费；
- 失败补偿动作（`clearMark` / `outbox.record`）逐一 try/catch，异常不再逃逸出监听器。

**验证**：新增 KB-07（Redis 降级）、KB-08（补偿异常不逃逸）；压测复跑「落库增量 = 10000 ✅」，0 降级 / 0 重复 / 0 失败。

### 3.2 碎片化小批次（吞吐隐患）

**现象**：修复停摆后落 10000 单竟产生 **7418** 个「Batch persisted」，平均每批仅 **1.3** 条——批量消费形同虚设，Redis pipeline / DB 往返各 7418 次，也是 Redis 连接 churn 主因之一。

**根因**：Kafka 消费者默认 `fetch.min.bytes=1`，broker「来一条就返回一条」，poll 拿不到大块数据。

**修复**（`application.yaml`）：

```yaml
consumer:
  max-poll-records: 500
  fetch-min-size: 51200      # 至少累计 50KB 才返回
  fetch-max-wait: 1000ms     # 最多等 1s，避免低流量延迟过高
```

**验证**：批次数 **7418 → 57（130×↓）**，平均每批 **1.3 → 175 条（135×↑）**。

## 4. 关键指标（阶段对比）

| 阶段 | 落库核对 | 消费者批次数 | 平均/批 | 说明 |
|---|---|---|---|---|
| 修复前（停摆 bug） | ❌ 增量 2839，7161 卡死 | — | — | 端口耗尽 → 监听器异常逃逸 → 容器停摆 |
| 修复后（未调 fetch） | ✅ 10000 | 7418 | 1.3 | 批量消费被 `fetch.min.bytes=1` 碎片化 |
| 修复后（fetch 调优） | ✅ 10000 | **57** | **175** | 消费者 ~1200 TPS，与生产实时同步 |

> 最终态（fetch 调优后）：生产 21:00:47 起、21:00:54 结束，峰值 TPS **1374**、t=8 库存耗尽；消费者 21:00:47.129 → 21:00:55.509 排空 10000 单（~8.4s），**消费者不再是瓶颈**。
> 附带收益：fetch 调优后生产 TPS 由 333 升至峰值 1374——消费者 Redis 连接 churn 下降（批次数 7418→57）减少了对秒杀主链路 Redis 连接的争用（相关性观察，未做对照实验）。

## 5. 关键场景记录

| 场景 | 结果 |
|---|---|
| 秒杀 → 受理下单（返回字符串 orderId）→ Kafka → 异步落库 | ✅ |
| 落库核对：`tb_voucher_order` 增量 = 成功下单数 | ✅ 10000 = 10000（两次） |
| 幂等：同 (user, voucher) 重复投递由 SETNX + UNIQUE KEY 双重兜底 | ✅ |
| Redis 去重不可用 → 降级 DB-only（INSERT IGNORE）不中断消费 | ✅ KB-07 |
| 落库失败 → 不 ack + 写 Outbox PENDING + 补偿重试 | ✅ KB-04/05/06 |
| 6 消费者线程匹配 6 分区（0..5 各一） | ✅ |

## 6. 遗留观察

| 项 | 说明 |
|---|---|
| 首轮压测 7161 条订单丢失 | 停摆 bug 的直接后果（未落库、未写 Outbox、offset 前移）；仅压测数据，生产不受影响，本次修复已杜绝该类丢失 |
| 生产侧 Lua 连接 churn | 秒杀 Lua 脚本每请求仍建一条独占连接（千级 TPS 时是端口耗尽根源）；本次用「消费者降级 + 批次聚合」从消费侧化解，未动生产侧。持续数分钟 2000+ TPS 时生产侧可能撞端口墙，届时需 Lua 走连接池或改用 Redis 原生限流 |
| 落库核对轮询窗口 | `throughput-test.js` 落库核对等待由 40s 放宽至 90s（异步消费者是已知慢路径） |
| 同机压测 | 压测脚本与后端同机，绝对 TPS 偏保守；结论以「消费者与生产实时同步、增量一致」为准 |

## 7. 结论

**异步落库（Kafka）+ 消费者吞吐优化通过。** 秒杀主链路解耦 MySQL INSERT，落库由 Kafka 消费者异步完成；修复消费者停摆丢单（Redis 降级 + 补偿防御）与碎片化小批次（fetch 聚合），最终「落库增量 = 成功下单数」两次压测均一致，消费者 ~1200 TPS 与生产实时同步、不再是瓶颈。全量单测 174/174 通过。

> 改动文件：`src/main/java/com/campusdeal/mapper/CouponOrderMapper.java`（batchInsertIgnore）、`mq/FlashDealConsumer.java`、`mq/FlashDealProducer.java`、`mq/FlashDealProducerImpl.java`、`service/IdempotentService.java`、`service/impl/FlashDealServiceImpl.java`、`service/impl/IdempotentServiceImpl.java`、`resources/application.yaml`；
> 测试：`src/test/java/com/campusdeal/mq/FlashDealConsumerTest.java`（KB-01..08）、`FlashDealProducerTest.java`、`seckill/FlashDealServiceImplTest.java`；新增脚本 `tools/throughput-test.js`、`tools/gen-token-pool.js`。
