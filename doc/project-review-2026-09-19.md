# CampusDeal 安全与可靠性审查整改记录

> 本文是历史审查的公开整改记录。问题描述针对修复前基线 `cf2fe25`，不代表当前 `main` 的行为。主要修复落在提交 `c42b1d8`，当前状态和未完成验证以文末说明及 OpenSpec 清单为准。

## 审查范围

审查覆盖 HTTP 权限边界、上传资源、验证码、Redis 缓存、Canal、秒杀订单、Kafka/Outbox、Agent 会话与 SSE、RAG、社交一致性、配置安全和可观测性。

本轮目标不是隐藏历史问题，而是把每个问题转化为可检查的实现、自动化测试或明确保留的环境验证项。

## 整改结果

| 等级 | 历史问题 | 当前处理 | 主要证据 |
|---|---|---|---|
| P0 | 匿名文件删除与路径穿越 | 删除 GET 入口；改为登录用户按资源 ID 删除，并校验归属、规范路径和真实文件边界 | [UploadController](../src/main/java/com/campusdeal/controller/UploadController.java)、[UploadControllerTest](../src/test/java/com/campusdeal/controller/UploadControllerTest.java) |
| P1 | 优惠券、商户等写接口授权不足 | 集中授权服务；控制器与事务服务层双重检查；写请求改用校验 DTO | [AuthorizationService](../src/main/java/com/campusdeal/security/AuthorizationService.java)、[AuthorizationServiceTest](../src/test/java/com/campusdeal/security/AuthorizationServiceTest.java) |
| P1 | Agent 会话可跨用户读取或覆盖 | 会话持久化 owner/version；读取、保存、删除、继续和确认均校验归属；并发更新使用版本/锁 | [SessionManager](../src/main/java/com/campusdeal/agent/SessionManager.java)、[SessionManagerTest](../src/test/java/com/campusdeal/agent/SessionManagerTest.java) |
| P1 | Outbox 时间和状态可能导致消息不可补偿 | 迁移补齐生命周期字段；创建、领取、退避、失败和确认状态明确化 | [OutboxScheduler](../src/main/java/com/campusdeal/mq/OutboxScheduler.java)、[OutboxSchedulerTest](../src/test/java/com/campusdeal/mq/OutboxSchedulerTest.java) |
| P1 | Redis 去重可能掩盖数据库失败 | 数据库唯一约束作为最终幂等依据；失败向监听容器传播；Redis 仅用于加速 | [FlashDealConsumer](../src/main/java/com/campusdeal/mq/FlashDealConsumer.java)、[FlashDealConsumerTest](../src/test/java/com/campusdeal/mq/FlashDealConsumerTest.java) |
| P1 | Kafka 失败语义和 offset 推进不明确 | 有界重试、DLT、失败抛出和数据库提交后确认；提供授权重放与审计 | [KafkaConfig](../src/main/java/com/campusdeal/mq/KafkaConfig.java)、[FlashDltReplayService](../src/main/java/com/campusdeal/service/FlashDltReplayService.java) |
| P1 | 秒杀扣库存后可能没有可靠订单记录 | 以 MySQL 条件扣减、订单意图和 Outbox 组成受理事务；Redis 改为临时预占并支持释放、恢复和对账 | [FlashOrderIntentService](../src/main/java/com/campusdeal/service/FlashOrderIntentService.java)、[FlashOrderIntentServiceTest](../src/test/java/com/campusdeal/service/FlashOrderIntentServiceTest.java) |
| P1 | 逻辑过期缓存锁与数据 key 混用 | 独立锁 key、随机 owner、Lua 比较删除；增加空值缓存、有限重试和失败指标 | [CacheClient](../src/main/java/com/campusdeal/utils/CacheClient.java)、[CacheClientTest](../src/test/java/com/campusdeal/utils/CacheClientTest.java) |
| P1 | SSE 原文先发送、后执行安全校验 | 完整模型轮次先缓冲、校验和脱敏，再发送安全 chunk；移除重复最终生成 | [AgentOrchestratorImpl](../src/main/java/com/campusdeal/agent/AgentOrchestratorImpl.java)、[AgentOrchestratorTest](../src/test/java/com/campusdeal/agent/AgentOrchestratorTest.java) |
| P1 | Canal 未托管生命周期且事件解析不准确 | 显式开关、生命周期、重连退避和关闭；按表/事件处理 before/after image，失败批次不确认 | [CanalClientImpl](../src/main/java/com/campusdeal/canal/CanalClientImpl.java)、[CanalClientTest](../src/test/java/com/campusdeal/canal/CanalClientTest.java) |
| P2 | 新活动进入 Bloom Filter 延迟 | 事务提交后发布准入更新，多实例共享集合并用周期重建修复 | [BloomFilterServiceImpl](../src/main/java/com/campusdeal/cache/BloomFilterServiceImpl.java)、[BloomFilterServiceTest](../src/test/java/com/campusdeal/cache/BloomFilterServiceTest.java) |
| P2 | RAG 滑动窗口可能重复尾部块 | 使用可终止、连续编号的滑动窗口并覆盖空、短、边界和长文本 | [ClasspathDocumentLoader](../src/main/java/com/campusdeal/rag/ClasspathDocumentLoader.java)、[ClasspathDocumentLoaderTest](../src/test/java/com/campusdeal/rag/ClasspathDocumentLoaderTest.java) |
| P2 | BM25 与向量结果无法按同一内容融合 | 统一 `<document-id>#<chunk-index>` passage ID；保留通道分数并限制单文档占位 | [HybridRetrieverImpl](../src/main/java/com/campusdeal/rag/HybridRetrieverImpl.java)、[HybridRetrieverTest](../src/test/java/com/campusdeal/rag/HybridRetrieverTest.java) |
| P2 | PGVector 缺少驱动、迁移和启用边界 | 补充 PostgreSQL 驱动、schema、维度校验、就绪探针和显式开关 | [PgVectorStoreImpl](../src/main/java/com/campusdeal/rag/PgVectorStoreImpl.java)、[PgVectorReadinessIndicatorTest](../src/test/java/com/campusdeal/config/PgVectorReadinessIndicatorTest.java) |
| P2 | 验证码可重复使用且缺少限流 | Lua 原子比较删除；按手机号/来源限制发送和验证次数；错误映射为 429 | [UserServiceImpl](../src/main/java/com/campusdeal/service/impl/UserServiceImpl.java)、[UserServiceSecurityTest](../src/test/java/com/campusdeal/service/UserServiceSecurityTest.java) |

## 当前验证口径

2026-09-19 在 JDK 17 下执行：

| 检查 | 结果 |
|---|---|
| `mvn -B test` | 246 个测试通过，0 failures、0 errors、0 skipped |
| `mvn -B verify` | BUILD SUCCESS；4 个外部依赖集成测试因未启用而跳过 |
| `openspec validate remediate-project-review-findings --type change --strict` | 通过 |
| `git diff --check` | 通过 |
| 当前配置敏感信息扫描 | 未发现提交的凭据或机器路径 |

## 尚待外部环境验证

当前 OpenSpec 状态为 51/64。未勾选项主要要求 Docker 或真实 MySQL、Redis、Kafka、PostgreSQL、PGVector，以及本地 staging 运行证据；“未勾选”表示完整验收证据尚未取得，不等同于对应代码完全缺失。

发布前需执行：

```bash
CAMPUSDEAL_RUN_INTEGRATION=true mvn -B verify
```

具体剩余项见 [OpenSpec 任务清单](../openspec/changes/remediate-project-review-findings/tasks.md)，运行与恢复步骤见 [可靠性运行手册](runbooks/reliability.md)。
