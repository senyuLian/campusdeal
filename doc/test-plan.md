# CampusDeal 全量测试方案

> 适用范围：CampusDeal 校园生活服务平台（后端 Spring Boot 3.1.10 + Redis + Kafka + Canal + DeepSeek Agent / 前端 Vue3 CDN 无构建 SPA）
> 目标读者：开发 / 测试 / 运维
> **本文档为总索引**：完整用例已拆分到 `doc/test-plan/` 下的 11 个模块文档，按需跳转。

---

## 1. 文档地图

| 文档 | 内容 | 何时使用 |
|---|---|---|
| [README](test-plan/README.md) | 策略金字塔、环境表、测试数据约定、回归脚本索引、发布门禁 | 开始任何测试前 |
| [01-interface-test.md](test-plan/01-interface-test.md) | 36+ 接口请求/响应契约、鉴权矩阵、五维用例、curl 示例、契约测试 | 接口联调 / 契约回归 |
| [02-integration-e2e.md](test-plan/02-integration-e2e.md) | nginx 部署、前端 JS 结构、页面×接口矩阵、SSE 事件流协议、E2E S1–S5、CDP 回归 | 前后端联调 / E2E |
| [03-functional-test.md](test-plan/03-functional-test.md) | 六大业务线功能用例 + 跨业务串联场景 | 功能验收 |
| [04-module-cache.md](test-plan/04-module-cache.md) | 布隆过滤器、Caffeine L1、CacheClient 空值/逻辑过期、缓存一致性 | 缓存模块测试 |
| [05-module-seckill.md](test-plan/05-module-seckill.md) | seckill.lua 契约、三层过滤编排、同步落库+Outbox、并发不超卖、预热 | 秒杀模块测试 |
| [06-module-consistency.md](test-plan/06-module-consistency.md) | Kafka 削峰、消费者幂等、幂等服务、Outbox 补偿、Canal 失效 | 异步/一致性测试 |
| [07-module-agent.md](test-plan/07-module-agent.md) | 状态图、SSE 协议、6 工具契约、编排、敏感确认、会话压缩、LLM、注册 | Agent 模块测试 |
| [08-module-rag.md](test-plan/08-module-rag.md) | BM25、PGVector、RRF 融合、知识图谱、索引构建、降级验收 | RAG 模块测试 |
| [09-module-security.md](test-plan/09-module-security.md) | 输入清洗(PII/注入)、输出幻觉、敏感确认、限流、压缩 | 安全护栏测试 |
| [10-performance-test.md](test-plan/10-performance-test.md) | 压测目标/环境/用例、秒杀吞吐、Agent TTFT、RAG、资源压力 | 性能测试 |
| [11-regression-release.md](test-plan/11-regression-release.md) | 分层回归、P0/P1/P2 + T1–T10 回归矩阵、发布门禁、回滚预案 | 发布前回归 |

---

## 2. 项目与测试目标

CampusDeal 校园 O2O 平台核心业务线：

| 模块 | 技术要点 |
|---|---|
| 用户 | 手机验证码登录 / 签到 / 个人信息（Redis Hash + BitMap） |
| 商户 | 逻辑过期缓存（CacheClient）、Redis GEO 附近 |
| 优惠券/秒杀 | 布隆过滤器 + Caffeine L1 + Redis+Lua 原子扣减（三层过滤） |
| 帖子社交 | 点赞 ZSet、关注 Feed ZSet + 滚动分页 |
| 智能助手 | LangGraph4j ReAct 状态图、SSE 流式、RAG（BM25+PGVector+Neo4j）、安全护栏 |
| 一致性 | 同步落库 + Kafka 削峰 + Outbox 补偿 + Canal binlog 失效 |

测试目标：功能正确性、安全（401/越权/PII/注入）、一致性（不超卖不重复、最终一致）、性能、可回归。

---

## 3. 基础契约（各文档共用）

### 3.1 统一响应 `Result`

```json
{ "success": true, "errorMsg": null, "data": {}, "total": null }
```

Jackson `non_null` 序列化，null 字段不出现。`orderId` 以**字符串**下发（防 JS 精度，P2-6）。

### 3.2 鉴权

- `RefreshTokenInterceptor`(order=0)：读 `authorization` → Redis `login:token:{token}` → `UserHolder`，滑动续期；开头强制 `removeUser()`（P0-5）。
- `LoginInterceptor`(order=2)：无用户 → HTTP 401。
- 公开路径：`/user/code`、`/user/login`、`/post/**`、`/user/public/*`、`/merchant/**`、`/merchant-type/**`、`/upload/**`、`/coupon/**`、静态资源。
- **例外**：`/merchant` 的 POST/PUT 写接口在公开路径中，Controller 内自校验登录（P0-4 回归重点）。
- **新接口默认受保护**，加入 MvcConfig excludes 或带 token。

### 3.3 环境

| 组件 | 地址/版本 | 说明 |
|---|---|---|
| JDK | **17**（系统默认 25 破坏 Lombok） | `JAVA_HOME="D:/program/develop/jdks/jdk17"` |
| MySQL / Redis | localhost:3306/campusdeal / localhost:6379(123456) | 必需 |
| 后端 / 前端 | :8081 / nginx:8080 反代 `/api` | 必需 |
| Kafka / Canal | :9092 / :11111（**默认未启动**） | 可选，降级可用 |
| DeepSeek / PGVector / Neo4j | 无 Key / 未部署可降级 | 可选 |

> 依赖可用性口径：Kafka / Canal / PGVector / Neo4j 未部署时应用须正常启动、主链路不受影响、能力降级可用（详见 06/08/09 验收）。

### 3.4 测试数据约定

- 测试手机号 `13800001111` / `13686869696`（用户1）；并发用 30+ 个 `13688668900` 段手机号。
- 闪购券 id=10/11/12（`11` 专用秒杀回归）。
- 秒杀回归前清理幂等键：Redis `flashdeal:order:{dealId}` + `order:dedup:{userId}:{dealId}` + `flashdeal:stock:{dealId}`，MySQL `tb_voucher_order` 对应单。

### 3.5 回归脚本（详见 README / 11）

| 脚本 | 覆盖 |
|---|---|
| `node tools/api-smoke.js` | 36 接口 × 鉴权基线 |
| `node tools/regression-phase-d.js` | 接口矩阵 + S1/S2/S3/S5 |
| `node tools/concurrency-test.js` | 秒杀并发不超卖（7 断言） |
| `node tools/agent-sse-test.js` | SSE 事件流（8 断言） |
| `node tools/auth-leak-test.js` | P0-5 ThreadLocal 泄漏 |
| `node tools/cdp-pages.js` / `cdp-verify-doc09.js` | 前端页面 CDP 断言 |
| `mvn test` / `mvn verify`（JDK17） | 单测 / 集成测试 |

---

## 4. 已知问题与风险速览

完整矩阵见 [11-regression-release.md](test-plan/11-regression-release.md)；历史 P0/P1/P2 修复见 01/02 各文档。

**本轮评审发现 T1–T10（发布前需闭环）：**

| # | 问题 | 严重度 |
|---|---|---|
| T3 | `confirm-tools:[applyRefund]` 与工具名 `apply_refund` 不匹配 → **退款二次确认被绕过** | 安全 |
| T1/T2 | 两个单测与现实现脱节（异步生产者 / 缺 Mock） | CI 红 |
| T4 | `tb_voucher_order` 无唯一索引，幂等无 DB 兜底 | 一致性 |
| T5–T9 | 配置未接线（Bloom/RateLimit/injection-sensitivity）、Canal 无重连、确认上下文内存态、PiiMode 部分未实现 | 配置/加固 |
| T10 | 4 个核心工具零单测 | 盲区 |

---

## 5. 快速开始

```bash
export JAVA_HOME="D:/program/develop/jdks/jdk17"
mvn compile
mvn spring-boot:run
# 另开终端：回归脚本，见 README §回归脚本索引
```

---

*本文档基于当前代码库（`main`，2026-08）与 doc/design 01–09、阶段报告 A–E 编写；代码变更后需同步更新对应模块文档与接口矩阵。*
