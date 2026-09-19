# CampusDeal 可靠性运行手册

## 发布前检查

1. 使用 Java 17，设置 `CAMPUSDEAL_DB_PASSWORD`、`CAMPUSDEAL_REDIS_PASSWORD`，并在隔离数据库上设置 `CAMPUSDEAL_FLYWAY_ENABLED=true`。
2. 运行 `mvn -B test`。具备 Docker 时再设置 `CAMPUSDEAL_RUN_INTEGRATION=true` 运行 `mvn -B verify`，该开关会执行 `FlywayMySqlIT`。
3. 逐项确认 `/actuator/health` 中 `campusDealIntegrations` 的可选组件状态。Kafka、Canal、PGVector、Neo4j 默认关闭。

## 灰度、回滚和禁用

- 秒杀受理灰度由 `CAMPUSDEAL_FLASH_DURABLE_ACCEPTANCE_ENABLED` 与
  `CAMPUSDEAL_FLASH_DURABLE_ACCEPTANCE_CUTOVER_ENABLED` 控制。切换前可打开
  `CAMPUSDEAL_FLASH_DURABLE_ACCEPTANCE_SHADOW_ENABLED` 观察 Redis/MySQL 判定差异；出现新链路错误时将
  cutover 设为 `false`，保留 `flash_order_intent` 作为审计记录；恢复前先完成库存和订单对账。
- Kafka 出现故障时将 `CAMPUSDEAL_KAFKA_ENABLED=false`，DB intent recovery 仍会处理 `ACCEPTED/PROCESSING` intent；修复 broker 后重新启用 Kafka，并检查 Outbox/DLT。
- Canal、PGVector、Neo4j 分别通过 `CAMPUSDEAL_CANAL_ENABLED`、`CAMPUSDEAL_PGVECTOR_ENABLED`、`CAMPUSDEAL_NEO4J_ENABLED` 关闭。关闭向量后 BM25 继续提供检索。
- 发布回滚只回滚应用版本，不回滚已执行的 Flyway 版本；使用前向修复迁移恢复数据约束。

## Outbox 和订单恢复

- 查询积压：`SELECT status, COUNT(*), MIN(create_time) FROM outbox GROUP BY status;`。
- 查询未完成受理：`SELECT status, COUNT(*), MIN(accepted_at) FROM flash_order_intent GROUP BY status;`。
- 修复消息后，将对应记录保持为 `PENDING`，由调度器重试；不要手工删除 `flash_order_intent` 或 `tb_voucher_order`。
- DLT/FAILED 消息必须保留原始 `message_id`、失败原因和审批记录。Kafka 开启时由管理员调用
  `POST /admin/flash-orders/dlt/replay`，提交 `eventId` 与原始 JSON；服务会校验管理员身份、记录
  `flash_dlt_replay_audit`，再按 order ID 重放，唯一键保证幂等。
- 旧版 `sync-*` Outbox 行迁移期间默认只读；需要隔离损坏 payload 时先设置
  `CAMPUSDEAL_FLASH_LEGACY_OUTBOX_REPAIR_ENABLED=true`，调度器会将无法解析的行标记为
  `FAILED/LEGACY_QUARANTINED`，保留原记录供审计。

## Redis 库存重建

1. 暂停受理：`CAMPUSDEAL_FLASH_DURABLE_ACCEPTANCE_ENABLED=false`。
2. 对账 `tb_seckill_voucher.stock`、`flash_order_intent`（含未完成 intent）和 `tb_voucher_order`，确认差异后再调用应用的预热流程。
3. 重新启动应用或调用受控的库存预热任务；无法解释的差异保持活动关闭并告警。

## 所有权修复和上传清理

- 先查看 `merchant_owner_quarantine`、`social_relation_quarantine`、`outbox_quarantine`，由管理员通过审计流程补齐 owner 或恢复关系。
- 上传删除只接受 `assetId`，文件路径来自 `tb_upload_asset.relative_path`；清理前再次执行根目录规范化检查，不使用旧的路径参数。

## 密钥轮换

1. 在密钥管理系统生成新值，注入 `CAMPUSDEAL_DB_PASSWORD`、`CAMPUSDEAL_REDIS_PASSWORD`、`CAMPUSDEAL_DEEPSEEK_API_KEY` 等环境变量。
2. 滚动重启实例，检查生产配置守卫和 `/actuator/health`，确认旧凭证失效后再撤销旧值。
3. 日志、Outbox payload、指标标签不得写入完整 token、验证码、手机号或 API key。
