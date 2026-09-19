# CampusDeal 测试报告索引

测试报告按执行时间保留，便于追踪性能和行为变化。历史报告只代表当时提交和环境，当前验证口径以最新报告为准。

| 报告 | 内容 | 状态 |
|---|---|---|
| [00-baseline](00-baseline.md) | 初始构建与测试基线 | 历史快照 |
| [01-interface](01-interface.md) | HTTP 接口与鉴权契约 | 历史快照 |
| [02-integration-e2e](02-integration-e2e.md) | E2E 与 SSE 协议 | 历史快照 |
| [03-functional](03-functional.md) | 核心业务功能 | 历史快照 |
| [04-cache](04-cache.md) | 缓存与 Bloom Filter | 历史快照 |
| [05-seckill](05-seckill.md) | 秒杀链路 | 历史快照 |
| [06-consistency](06-consistency.md) | Kafka、Outbox 与一致性 | 历史快照 |
| [07-agent](07-agent.md) | Agent 会话与工具 | 历史快照 |
| [08-rag](08-rag.md) | RAG 与降级 | 历史快照 |
| [09-security](09-security.md) | 安全护栏 | 历史快照 |
| [10-performance](10-performance.md) | 性能测试 | 历史快照 |
| [11-regression-release](11-regression-release.md) | 旧版发布门禁 | 历史快照 |
| [12-throughput](12-throughput.md) | 异步订单吞吐 | 历史快照 |
| [13-remediation](13-remediation.md) | 审查修复后的当前本地回归 | 当前口径 |

当前主分支单元测试为 246/246 通过；外部集成用例需要显式设置 `CAMPUSDEAL_RUN_INTEGRATION=true` 后执行 `mvn -B verify`。
