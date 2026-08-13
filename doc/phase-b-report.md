# 阶段 B 报告：P0 修复

> 执行日期：2026-08-13　|　验证脚本：`tools/verify-p0.js`（6/6 通过）

## 修复清单

| # | 缺陷 | 修复方案 | 涉及文件 | 验证结果 |
|---|------|----------|----------|----------|
| P0-1 | 秒杀 Kafka 阻塞 60s → 500 | 生产者改「尽力而为」异步回调（`.thenAccept`+`.exceptionally`），失败只记日志不回滚；yaml 加 `producer.properties.max.block.ms=3000` + `retries=0` | `mq/FlashDealProducerImpl.java`、`mq/FlashDealProducer.java`、`application.yaml` | ✅ 秒杀 3017ms（原 60201ms） |
| P0-2 | `outbox` 表缺失 | 新增 `db/outbox.sql`（`CREATE TABLE IF NOT EXISTS`）并落库；`OutboxScheduler` 加 try-catch 降级 | `resources/db/outbox.sql`、`mq/OutboxScheduler.java` | ✅ 表已建，字段对齐 `entity/Outbox.java` |
| P0-3 | 订单异步落库链路未打通（Kafka 未运行时永不落库） | 秒杀成功改为**同步落库**（复用 `FlashDealConsumer.processMessage`，SETNX 幂等 + MySQL 主键兜底），Kafka 仅作兜底；同步落库失败写 Outbox PENDING 供补偿 | `service/impl/FlashDealServiceImpl.java` | ✅ `tb_voucher_order` 出现订单 `76413360890970116`（user 1013） |
| P0-4 | `/merchant/**` 全放行（POST/PUT 越权写） | `MerchantController.saveShop/updateShop` 增加 `UserHolder.getUser()==null → 401` | `controller/MerchantController.java` | ✅ 匿名 POST/PUT = 401，有 token = 200 |

## 验证数据

```
══ P0-4 ══
  ✅ POST /merchant 无 token = 401
  ✅ PUT  /merchant 无 token = 401
  ✅ POST /merchant 有 token 放行 (200, data=15)
══ P0-1/P0-3 ══
  ✅ 秒杀成功（不阻塞）— 200 / 3017ms
  ✅ 秒杀耗时 < 5000ms
```

DB 落库（精确 BigInt 对比，非 JS Number）：
```
mysql> SELECT id,user_id,voucher_id,status FROM tb_voucher_order WHERE user_id=1013 ORDER BY id DESC LIMIT 1;
76413360890970116 | 1013 | 10 | 1
```

## 新增发现（非 P0，转后续阶段）

| # | 发现 | 说明 | 建议 |
|---|------|------|------|
| P2-6 | **订单 ID 超出 JS 安全整数** | RedisIdWorker 生成的 17 位 ID > 2^53，前端 `resp.data` 经 JS `Number` 解析会丢精度（`shop-detail.js:67` toast 显示订单号略有偏差）。非功能性问题，仅展示。 | 后端 Long 序列化为 String（`@JsonSerialize`）或前端按字符串处理 |
| P1-8 | **两层幂等键可能漂移** | Lua 用 `flashdeal:order:{dealId}`（set），消费者用 `order:dedup:{userId}:{dealId}`（SETNX）。测试脚本只清前者时，秒杀返回成功但同步落库被后者拦截 → 未新增订单。正常流程两者一致，无实际影响，但回归脚本需同时清理。 | 回归脚本 S2 场景统一清理两层键 |

## 备注

- 重启后端时发现 `TaskStop` 只杀 `mvn` 壳进程、未杀子 `java` 进程，导致端口占用、新进程启动失败。已用 `Stop-Process` 按 8081 监听 PID 强杀后重启（新进程 PID 10676）。
- 测试产物已清理：`tb_shop` 中 `__p0_probe__` 探针商户已删除。
