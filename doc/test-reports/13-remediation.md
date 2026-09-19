# 13 · 项目审查修复回归

日期：2026-09-19

本轮修复覆盖授权与请求校验、上传资源边界、验证码原子消费、缓存锁与事务后失效、Canal 生命周期、可恢复秒杀订单意图、Outbox/Kafka 重试与 DLT、Agent 会话隔离与输出校验、RAG 规范化检索、社交关系幂等，以及敏感日志/响应脱敏和可靠性指标健康探针。

## 已执行门禁

```bash
mvn -B test
mvn -B verify
openspec validate remediate-project-review-findings --type change --strict
git diff --check
```

`mvn test` 是默认的无外部服务单元套件；`mvn verify` 在未设置 `CAMPUSDEAL_RUN_INTEGRATION=true` 时会跳过需要 Docker/真实 MySQL、Redis、Kafka 或 PostgreSQL 的集成测试。发布前应在具备这些依赖的环境中显式运行集成门禁，确认迁移、事务提交后行为、消息重试/DLT 和 Redis 丢失恢复。

本次本地回归结果：`mvn -B test` 共 246 个测试，0 failures、0 errors、0 skipped；`mvn -B verify` 构建成功，4 个外部依赖集成测试因未设置 `CAMPUSDEAL_RUN_INTEGRATION` 全部跳过。

## 运行时开关

- `CAMPUSDEAL_FLASH_DURABLE_ACCEPTANCE_ENABLED=true`：默认启用 MySQL 订单意图受理。
- `CAMPUSDEAL_FLASH_DURABLE_ACCEPTANCE_CUTOVER_ENABLED=true`：将流量切换到可持久化受理路径；切换前可用 shadow 开关对比结果。
- `CAMPUSDEAL_FLASH_DURABLE_ACCEPTANCE_SHADOW_ENABLED=true`：只读比较旧 Redis admission 与 MySQL/intent 状态，不改变受理结果。
- `CAMPUSDEAL_FLASH_LEGACY_OUTBOX_REPAIR_ENABLED=true`：扫描并隔离历史 `sync-*` Outbox 数据，修复前先审计异常 payload。
- `CAMPUSDEAL_KAFKA_ENABLED=true`：启用 Kafka 生产者、消费者、Outbox relay 和有界重试/DLT；关闭时由意图恢复调度器继续收敛订单。
- `CAMPUSDEAL_CANAL_ENABLED=true`：启用 Canal 托管生命周期与重连。
- `CAMPUSDEAL_PGVECTOR_ENABLED=true`：启用向量检索；关闭时仅构建 BM25，不创建向量连接。
- `CAMPUSDEAL_RAG_REINDEX=true`：启动时执行可恢复 canonical passage 重建；可通过 `CAMPUSDEAL_RAG_REINDEX_CHECKPOINT` 指定 checkpoint 文件。
- `CAMPUSDEAL_UPLOAD_ROOT`：上传根目录，服务端会执行规范化路径和内容签名校验。
