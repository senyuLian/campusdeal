# 06 · 模块测试：异步订单与数据一致性

> 覆盖 Kafka 削峰、消费者幂等、Outbox 补偿调度、Canal binlog 缓存失效、
> 以及"同步落库 + 异步兜底"的双通道一致性策略。

## 1. 架构与数据流

```
秒杀成功
 ├─ ① 同步落库：FlashDealConsumer.processMessage(message)
 │      → IdempotentService.tryMark(SETNX) → CouponOrderMapper.insert → 成功
 │      → 失败 → OutboxService.record(PENDING)  ← 补偿兜底
 ├─ ② 异步发送：FlashDealProducer.send(message)（尽力而为，不阻塞）
 │      → Kafka topic flash-deal-orders（key=userId，header messageId）
 │      → FlashDealConsumer（@KafkaListener，manual ack）→ 幂等落库
 └─ ③ Canal 监听 binlog：tb_voucher / tb_shop / tb_seckill_voucher 变更 → 失效缓存
```

**当前主链路**：①同步落库保证不丢单；②Kafka 仅兜底/削峰；③Canal 可选（未启动则靠逻辑过期）。

## 2. Kafka 配置要点

| 配置 | 值 | 影响 |
|---|---|---|
| `producer.max.block.ms` | 3000（P0-1 修复） | Kafka 不可用最多阻塞 3s（已被异步化，实际不阻塞主流程） |
| `producer.retries` | 0 | 不重试（幂等兜底） |
| `consumer.auto-offset-reset` | earliest | 消费者启动后从头消费 |
| `consumer.enable-auto-commit` | false | 手动 ack |
| `listener.auto-startup` | **false** | 无 Kafka 环境不启动消费者；接入后改 true |
| `listener.ack-mode` | manual | 消费成功后 ack |

## 3. 生产者用例（KP）

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| KP-01 | 发送成功 | Kafka 可用 | 消息进入 topic（key=userId，header 含 messageId） |
| KP-02 | Kafka 不可用 | 停 Kafka | **不抛异常**，仅 warn 日志（异步回调） |
| KP-03 | 时序保序 | 同用户多单 | key=userId 保证分区有序 |
| KP-04 | 返回值 | 调 `send()` | 恒返回 null（异步语义） |

> ⚠️ 现有 `FlashDealProducerTest.KP-01/02` 断言旧同步语义（返回 SendResult/抛异常），
> 与异步实现脱节，**需重写**（T1）。

## 4. 消费者幂等用例（KC）

去重键：`order:dedup:{userId}:{dealId}`，`SETNX` + 1h TTL（`IdempotentService.tryMark`）。

| ID | 用例 | 消息状态 | 预期 |
|---|---|---|---|
| KC-01 | 正常消费 | 新消息 | insert 订单；Outbox PROCESSED；ack |
| KC-02 | 重复消息 | 同 user+deal 已处理 | tryMark 失败 → 直接 ack，不重复 insert |
| KC-03 | 消息缺字段 | orderId/userId 非法 | 异常 → clearMark + Outbox PENDING + 不 ack |
| KC-04 | DB 主键冲突 | 同 orderId 重投 | 吞 DuplicateKeyException，ack |
| KC-05 | DB 其他异常 | 连接失败 | clearMark + Outbox PENDING + 不 ack（Kafka 重投） |
| KC-06 | 补偿入口 | `processMessage` 直接调用 | 重复消息吞 DuplicateKey，非重复异常上抛 |
| KC-07 | TTL 过期重放 | 1h 后重投 | 可再次落库（幂等窗口=1h） |

> ⚠️ **幂等边界**：`tb_voucher_order` 无 `(user_id, voucher_id)` 唯一索引（仅主键 id 雪花 orderId）。
> 同一用户同一 deal 若生成不同 orderId（理论上 Lua 已拦截）无 DB 层兜底。需评估补唯一索引（T4）。

## 5. 幂等服务用例（ID）

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| ID-01 | 首次标记 | tryMark | true |
| ID-02 | 重复标记 | 再 tryMark | false |
| ID-03 | TTL 过期 | 等 1h / 改短 TTL | 可再次标记 |
| ID-04 | clearMark | 删除 | 之后可再次标记 |

## 6. Outbox 补偿用例（OB）

### 6.1 表结构（`db/outbox.sql`）

`id`(PK), `message_id`(varchar), `topic`, `payload`(JSON TEXT), `status`(PENDING/PROCESSED/FAILED),
`retry_count`, `error_msg`, `create_time`, `update_time`；索引 `(status, create_time)`。

### 6.2 调度器行为

- `@Scheduled(fixedDelay=30_000)`：每 30s 扫 `PENDING AND create_time < now-1min`，LIMIT 100。
- 成功 → PROCESSED；失败 → retry_count+1，≥5 → FAILED，否则仍 PENDING。
- 表缺失/DB 抖动 → 仅 warn 不刷错。

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| OB-01 | 补偿成功 | 插 PENDING → 跑调度 | 变 PROCESSED |
| OB-02 | 延迟扫描 | PENDING 但 <1min | 不处理 |
| OB-03 | 超重试 | 失败 5 次 | 变 FAILED |
| OB-04 | 幂等冲突 | 补偿时订单已存在 | 吞冲突，变 PROCESSED |
| OB-05 | 无 PENDING | 空表 | 直接返回 |
| OB-06 | 表缺失 | 删 outbox 表 | 不崩，warn 日志 |
| OB-07 | 批处理上限 | >100 条 PENDING | 每轮处理 ≤100 |

## 7. Canal 缓存失效用例（CN）

### 7.1 订阅与失效规则

- 订阅：`campusdeal\.tb_voucher,campusdeal\.tb_shop,campusdeal\.tb_seckill_voucher`
- 仅处理 `EntryType.ROWDATA`；取 `afterColumnsList.id`。
- 失效：`tb_voucher`/`tb_seckill_voucher` → 删 `cache:merchant:{id}` + `flashdeal:stock:{id}`；
  `tb_shop` → 删 `cache:merchant:{id}`；其他表忽略。

| ID | 用例 | binlog 表 | 预期 |
|---|---|---|---|
| CN-01 | tb_voucher 变更 | update/insert/delete | 两类 key 均失效 |
| CN-02 | tb_shop 变更 | 任意 DML | `cache:merchant:{id}` 失效 |
| CN-03 | 非关注表 | tb_user | 忽略 |
| CN-04 | 非 ROWDATA | entry 类型非行数据 | 跳过 |
| CN-05 | id 列为空 | after 无 id | 跳过 |
| CN-06 | batchId=-1 | 心跳 | continue 不处理 |

### 7.2 已知问题

- **无自动重连**：连接异常后主循环退出、`running` 仍 true，后续 `start()` 直接返回（T7）。
  降级 = 缓存失效中断，靠 30s 逻辑过期兜底。测试须验证"降级可用"且文档化恢复方式。

### 7.3 现有测试

`canal/CanalClientTest`（CN-01..04，Mock connector + protobuf 构造 Entry）。
补：CN-05/06、start/stop/异常路径。

## 8. 双通道一致性验证（端到端）

| ID | 场景 | 步骤 | 预期 |
|---|---|---|---|
| IT-01 | 秒杀→落库闭环 | 秒杀后查 DB | 订单存在（同步落库路径） |
| IT-02 | Kafka 兜底 | 同步成功但 Kafka 正常 | 消费者不重复插单（幂等） |
| IT-03 | 同步失败→补偿 | Mock insert 抛错 | Outbox PENDING → 调度器补偿成功 |
| IT-04 | Canal 实时失效（可选） | 直改 DB | 缓存被立即失效 |
| IT-05 | 无 Kafka/Canal 环境 | 正常跑秒杀 | 功能不受影响，仅日志降级 |

## 9. 验收标准

- 秒杀订单不丢、不重、不超卖（同步 + Outbox 双保险）。
- Kafka 不可用不影响秒杀主流程；消费者启动后幂等消费。
- Outbox 调度器正确补偿，超限 FAILED，表缺失不崩。
- Canal 未部署时缓存一致性由逻辑过期兜底（≤30s）。
- 所有 MQ/Canal 异常路径均有日志（warn/error）可观测。
