# 测试报告 06 · 模块测试：异步订单与数据一致性

> 日期：2026-08-15 ｜ 依据：`doc/test-plan/06-module-consistency.md`
> 环境：MySQL :3306 ✅、Redis :6379 ✅、后端 :8081 ✅（无 Kafka、无 Canal，符合 IT-05 降级场景）

## 1. 执行范围与结果

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `CanalClientTest` | CN-01..06 失效规则 + **CN-R1..R3 自动重连（T7 新增）** | ✅ 9/9 |
| `FlashDealConsumerTest` | KC-01..06 消费者幂等/补偿 | ✅ 6/6 |
| `FlashDealProducerTest` | KP-01..04 异步生产者新契约 | ✅ 3/3 |
| `OutboxSchedulerTest` | OB-01..07 补偿调度 | ✅ 4/4 |
| `IdempotentServiceTest` | ID-01..04 SETNX 幂等标记 | ✅ 4/4 |
| `tools/consistency-verify.js`（新增） | **IT-01..05 + T4 DB 兜底** 运行时 | ✅ 12/12 |

**合计：单元 26/26 + 运行时 12/12 通过。**

> 全量单测 **145/145** 通过（模块 05 基线 140，本次 +5 个 Canal 用例）。

## 2. 测试中发现并修复的问题

### 2.1 修复 1（T4 · `tb_voucher_order` 无 `(user_id, voucher_id)` 唯一索引）

**现象**：实时库存在**重复订单**——`user_id=1013` 对 voucher 10 有 3 单、voucher 11 有 7 单（8-13 及 8-15 测试遗留）。Lua 去重 + `flashdeal:order:*` SET 集合是唯一幂等防线；一旦该层失效（TTL 1h 过期重放、Lua 未命中、补偿重试竞态），DB 层无兜底，会重复插单。

**修复**：
- `campusdeal.sql` DDL 增加 `UNIQUE KEY uk_user_voucher (user_id, voucher_id)`；
- 实时库：先备份重复行至 `tb_voucher_order_dup_backup_20260815`，保留每个 `(user,voucher)` 最早一单后删除其余（10 行），再 `ALTER TABLE` 建索引；`SHOW INDEX` 验证生效，剩余重复=0。

**验证**：运行时直接绕过 Lua/Redis 向 DB 插入同 `(user=1111, voucher=12)` 的新 orderId → `ERROR 1062 Duplicate entry '1111-12'` 被拦截；正常秒杀路径不受影响。

### 2.2 修复 2（T7 · Canal 客户端无自动重连）

**现象**：`CanalClientImpl.start()` 连接异常后主循环退出但 `running` 恒为 `true`，后续 `start()` 直接返回 → **缓存失效永久中断**，只能靠 30s 逻辑过期兜底且无法恢复。

**根因**：单次 try-catch 包裹 connect+consume，异常即线程结束；`if (running) return` 阻止重启。

**修复**（`CanalClientImpl`）：
- 外层 `while (running && canalThread == currentThread)` 重连循环：失败后指数退避（默认 1s 起、封顶 30s，配置项 `campusdeal.canal.reconnect-base-delay-ms`）自动重连；
- 重连成功即重置 retryCount；消费异常同样走重连而非退出；
- `stop()` 幂等：`running=false` + interrupt + `disconnect()` 让阻塞的 `getWithoutAck(1000)` 尽快返回；
- 线程令牌 `canalThread == Thread.currentThread()` 防止 stop→start 快速切换时旧线程被新 `running=true` 复活（双消费者冲突）；
- `@Value` 字段补声明默认值（与 `:localhost/:11111/:example` 一致），Mockito 场景下不再为 null。

**验证**：CN-R1 模拟「首次 connect 抛异常 → 50ms 退避 → 二次连接成功」（latch 断言恢复）；CN-R2 start 幂等不建第二线程；CN-R3 stop 后可重启、线程为新实例。

## 3. 关键场景记录

| 场景 | 结果 |
|---|---|
| 秒杀 → 同步落库闭环（订单落 `tb_voucher_order`，返回字符串 orderId） | ✅ |
| 无 Kafka/Canal 环境秒杀正常（响应 30–93ms，不阻塞） | ✅ |
| Lua 去重：同用户重复秒杀 → "Already purchased" | ✅ |
| **DB 兜底：绕过 Lua 直插重复 (user,voucher) → 唯一索引 1062 拦截** | ✅ |
| 消费者 KC-01..06：正常落库 / 重复跳过 / 失败记 PENDING 不 ack / DuplicateKey 吞掉 ack | ✅ 6/6 |
| Outbox：表结构 9 字段、当前无积压 PENDING、调度器 5 次失败转 FAILED | ✅ |
| Canal 失效规则：tb_voucher/tb_seckill_voucher/tb_shop → 对应缓存 key 删除 | ✅ 9/9 |
| Canal 自动重连：连接失败退避重连、start 幂等、stop 可重启 | ✅ CN-R1..R3 |

## 4. 遗留观察

| 项 | 说明 |
|---|---|
| Canal 未部署 | 客户端仅手动 `start()` 后生效；本环境 binlog→Redis 失效由 30s 逻辑过期兜底（IT-04 按可选跳过，失效规则由单测 CN-01..06 覆盖） |
| IT-03 故障注入 | 同步落库失败→Outbox 补偿为单测覆盖（SC-02 / KC-03 / OB 系列）；运行时不注入 DB 故障，仅验证表结构与无积压 |
| 历史重复数据 | 已备份至 `tb_voucher_order_dup_backup_20260815`（仅测试库，10 行 status=1 未支付）；生产接入前须按此迁移流程处理存量数据 |
| 唯一索引与退款 | 退款仅改 `status`，不产生新行，`(user_id, voucher_id)` 不阻止退款后复用同券，业务上退款不重购 |

## 5. 模块结论

**异步订单与一致性模块通过。** 修复 T4（DB 层幂等兜底：`uk_user_voucher` 唯一索引，实测直插重复被 1062 拦截）与 T7（Canal 自动重连：失败退避恢复、start 幂等、stop 可重启）；秒杀订单「同步落库 + Outbox 补偿 + Kafka 兜底 + DB 唯一索引」四层保障，无 Kafka/Canal 环境功能不受影响（IT-05 实测 30ms）。

> 改动文件：`src/main/java/com/campusdeal/canal/CanalClientImpl.java`、`src/main/resources/db/campusdeal.sql`；
> 测试：`src/test/java/com/campusdeal/canal/CanalClientTest.java`（CN-05/06 + CN-R1..R3）；新增脚本 `tools/consistency-verify.js`。
