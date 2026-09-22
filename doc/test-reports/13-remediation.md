# 13 · 项目审查修复回归

日期：2026-09-19<br>
运行环境：JDK 17<br>
适用版本：修复提交 `49ab30c`

本轮覆盖授权与请求校验、上传资源边界、验证码原子消费、缓存锁与事务后失效、Canal 生命周期、可恢复秒杀订单意图、Outbox/Kafka 重试与 DLT、Agent 会话隔离与输出校验、RAG 规范化检索、社交关系幂等、敏感日志脱敏和可靠性健康探针。

## 当前可复现结果

| 命令 | 结果 | 边界 |
|---|---|---|
| `mvn -B test` | 246/246 通过 | 无外部服务单元测试 |
| `mvn -B verify` | BUILD SUCCESS | 4 个外部集成测试因未设置 `CAMPUSDEAL_RUN_INTEGRATION` 而跳过 |
| `openspec validate remediate-project-review-findings --type change --strict` | 通过 | OpenSpec 结构与规范校验 |
| `git diff --check` | 通过 | 空白与冲突标记检查 |

`mvn verify` 成功只表示默认门禁通过，不代表 MySQL、Redis、Kafka、PostgreSQL 或 PGVector 的真实集成链路已经验证。

## 外部集成门禁

在 Docker 或等价隔离依赖可用时执行：

```bash
CAMPUSDEAL_RUN_INTEGRATION=true mvn -B verify
```

需要补齐的证据包括：

- Flyway 在空库与升级数据集上的重复迁移。
- MySQL/Redis 下的授权、OTP 并发、缓存锁、事务提交后回调和社交幂等。
- Kafka 重试、DLT、进程中断、Outbox 补偿和 Redis 丢失恢复。
- PostgreSQL/PGVector 启用、维度校验、迁移和降级启动。
- Agent SSE 顺序、断开取消和跨实例确认。

## 历史性能基准

早期完整运行环境曾记录秒杀成功路径 P99 54 ms、商户缓存 P99 4 ms、峰值生产约 1374 TPS、消费者约 1200 TPS。这些数字是特定本机环境的历史快照，当前提交完成外部门禁前不将其表述为最新发布结果。

## 运行时开关

- `CAMPUSDEAL_FLASH_DURABLE_ACCEPTANCE_ENABLED=true`：启用 MySQL 订单意图受理。
- `CAMPUSDEAL_FLASH_DURABLE_ACCEPTANCE_CUTOVER_ENABLED=true`：切换到可持久化受理路径。
- `CAMPUSDEAL_FLASH_DURABLE_ACCEPTANCE_SHADOW_ENABLED=true`：只读比较旧准入与新路径。
- `CAMPUSDEAL_FLASH_LEGACY_OUTBOX_REPAIR_ENABLED=true`：扫描并隔离历史 Outbox 数据。
- `CAMPUSDEAL_KAFKA_ENABLED=true`：启用 Kafka、Outbox relay、重试与 DLT。
- `CAMPUSDEAL_CANAL_ENABLED=true`：启用 Canal 生命周期与重连。
- `CAMPUSDEAL_PGVECTOR_ENABLED=true`：启用向量检索；关闭时保留 BM25。
- `CAMPUSDEAL_RAG_REINDEX=true`：执行可恢复 canonical passage 重建。
- `CAMPUSDEAL_UPLOAD_ROOT`：设置上传根目录。

完整整改对应关系见 [项目审查整改记录](../project-review-2026-09-19.md)，未完成验证见 [OpenSpec 任务清单](../../openspec/changes/remediate-project-review-findings/tasks.md)。
