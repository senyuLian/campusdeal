# CampusDeal 测试方案 · 文档索引

> 按模块拆分的详细测试文档集。入口文档为 [`../test-plan.md`](../test-plan.md)（总览 + 快速回归），
> 本目录为各模块的深度用例与验收标准。

## 文档地图

| 文档 | 主题 | 关键内容 |
|---|---|---|
| [01-interface-test.md](./01-interface-test.md) | 接口测试 | 全部 36+ 接口的请求/响应契约、鉴权矩阵、五维用例、curl 示例 |
| [02-integration-e2e.md](./02-integration-e2e.md) | 前后端联调 + E2E | nginx 部署、页面×接口矩阵、SSE 协议、S1–S5 场景 |
| [03-functional-test.md](./03-functional-test.md) | 功能测试 | 六大业务线场景用例（用户/商户/券秒杀/帖子/上传/Agent） |
| [04-module-cache.md](./04-module-cache.md) | 模块：缓存 | 布隆过滤器、Caffeine L1、逻辑过期/空值缓存、穿透/击穿/雪崩 |
| [05-module-seckill.md](./05-module-seckill.md) | 模块：秒杀 | seckill.lua 契约、三层过滤编排、并发与幂等、状态流转 |
| [06-module-consistency.md](./06-module-consistency.md) | 模块：异步一致性 | Kafka 削峰、Outbox 补偿、消费者幂等、Canal binlog 失效 |
| [07-module-agent.md](./07-module-agent.md) | 模块：Agent | LangGraph4j 状态图、SSE 事件协议、6 个工具、会话与压缩 |
| [08-module-rag.md](./08-module-rag.md) | 模块：RAG 检索 | BM25、PGVector 向量、RRF 融合、Neo4j 知识图谱、索引构建 |
| [09-module-security.md](./09-module-security.md) | 模块：安全护栏 | 输入清洗/PII、Prompt Injection、敏感操作确认、限流、输出幻觉检测 |
| [10-performance-test.md](./10-performance-test.md) | 性能测试 | 压测目标、秒杀/热点/Feed/Agent 压测方案、并发无超卖验证 |
| [11-regression-release.md](./11-regression-release.md) | 回归与发布 | 一键回归脚本、执行顺序、发布门禁、已知问题 T1–T10 |

## 测试策略总览

```
        ▲  E2E / 联调（tools/*.js + 浏览器 CDP + 人工冒烟）
        │  S1 登录签到 / S2 秒杀落库 / S3 缓存一致性 / S4 Agent SSE / S5 鉴权越权
    ────┼─────────────────────────────────────────────────────────
        │  集成测试（*IT.java，依赖 MySQL/Redis/Kafka/Canal）
        │  跨组件：Lua 脚本 + 幂等 + Outbox 补偿 + Canal 失效 + RAG 检索
    ────┼─────────────────────────────────────────────────────────
        │  单元测试（*Test.java，Mock 外部依赖，mvn test 即跑）
        │  秒杀编排 / 缓存 / 安全 / Agent 状态图 / 工具注册
        ▼
```

## 测试环境（所有文档通用）

| 组件 | 地址/版本 | 必需 |
|---|---|---|
| JDK | **17**（`D:/program/develop/jdks/jdk17`）⚠️ JDK25 破坏 Lombok | 全部 |
| MySQL | localhost:3306/campusdeal（root/123456） | 全部 |
| Redis | localhost:6379（password: 123456） | 全部 |
| 后端 | http://localhost:8081 | 全部 |
| nginx | http://localhost:8080，`/api` 反代 → 8081 | 联调/前端 |
| Kafka / Canal / PGVector / Neo4j | 默认未启动，未部署须降级可用 | 可选 |

## 测试数据约定（所有文档通用）

| 数据 | 约定 |
|---|---|
| 测试手机号 | `13800001111`（已有用户） |
| 商户 | id=1..14，type_id 1（美食）/2（KTV） |
| 普通券 | id=1..9；闪购券 id=10/11/12（`11` 专用秒杀回归） |
| 校区坐标 | 校本部 `(120.149993, 30.334229)` |
| 并发用户集 | 30+ 已注册手机号（`13688668900` 段） |

> **秒杀回归前置**：清幂等键（P1-8）——Redis `flashdeal:order:{dealId}`、
> `order:dedup:{userId}:{dealId}`、`flashdeal:stock:{dealId}` + MySQL `tb_voucher_order` 该券订单。

## 回归脚本索引

| 脚本 | 命令 | 覆盖 |
|---|---|---|
| 接口冒烟+鉴权 | `node tools/api-smoke.js` | 36 接口 × 公开/401/200 |
| 全量回归 | `node tools/regression-phase-d.js` | 接口矩阵 + S1/S2/S3/S5 |
| 秒杀并发 | `node tools/concurrency-test.js` | 并发无超卖 |
| Agent SSE | `node tools/agent-sse-test.js` | S4 事件流 |
| 越权回归 | `node tools/auth-leak-test.js` | P0-5 ThreadLocal 泄漏 |
| 前端页面 | `node tools/cdp-pages.js`、`node tools/cdp-verify-doc09.js` | 页面 CDP + JS_ERRORS=0 |
| P0 验证 | `node tools/verify-p0.js` | P0 修复复验 |
| 单测 | `mvn test`（JDK17） | 全部 `*Test.java` |
| 集成 | `mvn verify`（JDK17） | 全部 `*IT.java` |

## 发布门禁（Release Gate）

- [ ] `mvn test` / `mvn verify` 全绿（JDK17）
- [ ] `api-smoke` 36 接口全绿
- [ ] `regression-phase-d` 53/53
- [ ] `concurrency-test` 无超卖
- [ ] `agent-sse-test` 事件流正确
- [ ] `auth-leak-test` 越权回归通过
- [ ] 前端 CDP 页面全绿、`JS_ERRORS=0`
- [ ] 日志无 P0 ERROR
- [ ] MySQL 订单与 Redis 库存对账一致
- [ ] 已知缺陷 T1–T10 有处理结论（见 [11-regression-release.md](./11-regression-release.md#已知缺陷清单))
