# CampusDeal · 校园生活服务平台

校园 O2O 平台：**商户发现 / 优惠券闪购 / 社交内容 / AI 智能客服**。面向高校场景，围绕「附近商户 + 限时秒杀 + 种草社区 + 智能助手」四大业务，覆盖从高并发秒杀到 LLM Agent 的完整技术栈。

> 项目由 Spring Boot 3.1.10 + Redis + DeepSeek 驱动，含 Phase 2 多级缓存秒杀与 Phase 3 AI Agent / RAG。

---

## 核心特性

| 模块 | 能力 |
|---|---|
| 商户 | 分类浏览、关键字搜索、GEO 附近商户（Redis GEO）、逻辑过期缓存防击穿 |
| 优惠券 | 普通券 / 闪购券，优惠券列表与领取 |
| 秒杀 | Bloom → Caffeine L1 → Redis Lua 三层快速失败，Kafka 异步落库（消费者 SETNX 幂等 + UNIQUE KEY 兜底 + Outbox 补偿） |
| 社交 | 帖子发布、点赞（Redis ZSet）、关注、共同关注、滚动分页 Feed |
| AI 客服 | LangGraph4j ReAct 智能体、6 个函数调用工具（查单/退款/商户/优惠券/FAQ/知识图谱）、SSE 流式输出 |
| RAG | BM25（内存）+ 向量（PGVector 可选）RRF 混合检索；知识图谱（Neo4j 可选）作为独立检索工具 |
| 安全 | 输入清洗（Prompt 注入 / SQL 注入）、PII 脱敏三模式、敏感操作二次确认、限流 |

---

## 技术栈

- **Java 17 · Spring Boot 3.1.10**（jakarta namespace）
- **MyBatis-Plus 3.5.5**（spring-boot3-starter）· **MySQL 8.0.33**
- **Redis**：Spring Data Redis（Lettuce 共享连接）+ **Redisson 3.23.5**（分布式锁）
- **Hutool 5.7.17** · Lombok · commons-pool2 · Actuator
- **LLM**：DeepSeek API（LangChain4j）
- **Agent**：LangGraph4j（ReAct 状态图）
- **消息**：Kafka（秒杀订单异步落库；broker 不可用时降级 Outbox 补偿重试）

---

## 项目结构

```
src/main/java/com/campusdeal
├── CampusDealApplication.java   # 入口：@MapperScan + @EnableAspectJAutoProxy(exposeProxy=true)
├── controller/   PostController, MerchantController, CouponController, UserController ...
├── dto/          Result, LoginFormDTO, UserDTO, ScrollResult
├── entity/       Post, Merchant, Coupon, FlashDeal, CouponOrder, User ...
├── mapper/       MyBatis-Plus BaseMapper
├── service/      IService 接口 + impl（ServiceImpl<Mapper, Entity>）
├── config/       MvcConfig, MybatisConfig, RedissonConfig, RedisConfig, WebExceptionAdvice
├── agent/        AI 客服：LangGraph4j 状态图、6 工具、SSE、会话压缩、安全护栏
├── rag/          RAG：BM25 索引、向量检索、知识图谱、RRF 混合检索
├── cache/        布隆过滤器、Caffeine L1 配置、缓存属性
├── canal/        Canal 监听 Binlog → 缓存失效
├── mq/           FlashDealProducer / FlashDealConsumer（批量消费）/ OutboxScheduler 补偿
├── seckill/      FlashDealContext / Result、LuaScriptConfig、RedissonLockHelper
├── security/     输入清洗、PII 脱敏、敏感门禁、限流
└── utils/        CacheClient, RedisIdWorker, UserHolder, RedisConstants ...
```

---

## 快速开始

### 环境要求

| 依赖 | 版本/位置 |
|---|---|
| JDK | **Java 17**（注意：系统默认 JDK 25 会破坏 Lombok，必须切 JDK 17） |
| MySQL | `localhost:3306/campusdeal`（root / 123456） |
| Redis | `localhost:6379`（密码 `123456`） |
| Kafka | `localhost:9092`（可选：秒杀异步落库；未启动时降级 Outbox 补偿） |
| DeepSeek | API Key（环境变量 `CAMPUSDEAL_DEEPSEEK_API_KEY` 或 `application-local.yaml`） |

> Canal / PGVector / Neo4j 均为可选组件：未部署时应用正常启动，对应能力（缓存失效 / 向量检索 / 知识图谱）自动降级。

### 构建与运行

```bash
export JAVA_HOME="D:/program/develop/jdks/jdk17"
mvn compile
mvn spring-boot:run
```

默认端口 **8081**。本地开发配置（含 DeepSeek Key 等敏感项）放 `application-local.yaml`，已 gitignore，不会提交。

### 数据库

`src/main/resources/db/campusdeal.sql` 为基础数据脚本；启动时 `FlashDealServiceImpl` 自动预热未过期秒杀活动的剩余库存到 Redis。

### 前端静态资源

图片上传目录：`SystemConstants.IMAGE_UPLOAD_DIR`（默认 nginx 下 `html/campusdeal/imgs`）。

---

## 主要接口

| 模块 | 路径 | 说明 |
|---|---|---|
| 用户 | `/user/code` `/user/login` `/user/me` `/user/logout` `/user/public/{id}` | 手机号验证码登录，Redis Token；公开主页 |
| 商户 | `/merchant/**` `/merchant-type/**` `/merchant/list/by-type` | 详情/列表/关键字/GEO 附近 |
| 优惠券 | `/coupon/**` | 列表、领取、我的券 |
| 秒杀 | `/coupon-order/seckill/{dealId}` | 闪购下单（受理后 Kafka 异步落库，返回字符串 orderId 防 JS 精度丢失） |
| 帖子 | `/post/**` `/follow/**` | 发布、点赞、关注、滚动分页 |
| 签到 | `/user/sign` | 按月签到 + 连续天数 |
| AI 客服 | `/agent/chat`（SSE）`/agent/confirm` `/agent/history/{id}` | 流式对话、敏感操作确认 |
| 上传 | `/upload/**` | 图片上传（公开） |

SSE 事件协议：`thinking / tool_call / tool_result / confirm / chunk / done / error`。

---

## 测试

测试方案与执行报告分列 `doc/test-plan/`（方案）与 `doc/test-reports/`（报告），一一对应：

```text
doc/test-plan/       测试方案：01 接口 · 02 联调E2E · 03 功能 · 04 缓存 · 05 秒杀 · 06 一致性
                     07 Agent · 08 RAG · 09 安全 · 10 性能 · 11 回归 · 12 吞吐基准
doc/test-reports/    测试报告：00 基线 ~ 12 吞吐（与方案对应）
doc/final-plan.md    顶层规划（项目概览 / 架构 / Phase 划分 / 技术决策）
tools/               运行时回归脚本（api-smoke / concurrency / perf / throughput / security ...）
```

- 单元测试：`mvn test`（**174/174 全绿**，JDK17）
- 运行时回归：`node tools/<script>.js`（接口契约 40+、秒杀并发无超卖、安全护栏、RAG 降级、秒杀吞吐等，脚本索引见 `doc/test-plan/README.md`）

### 核心测试指标

| 维度 | 指标 | 实测值 | 阈值 / 结论 |
|---|---|---|---|
| 秒杀 | 成功路径 P99 | **54ms** | <150ms ✅ |
| 秒杀 | 分层耗时（Bloom / Caffeine / Lua / 总计） | 17–191μs / 5–6μs / 5.7–8.9ms / 17–24ms | — |
| 秒杀 | 30 并发抢 10 库存 | 恰 10 成功、0 超卖、0 重复、DB 增量=10 | 100% 正确 ✅ |
| 秒杀 | 库存耗尽后秒杀层 Redis 命中 | **0 次**（L1 负缓存，MONITOR 验证） | 快速失败 ✅ |
| 缓存 | 商户详情缓存 P99（2000 次 GET） | **4ms**（DB 0 查询） | <5ms ✅ |
| 缓存 | 布隆拦截非法秒杀请求 | **13ms**（未触 Redis/DB） | — |
| Agent | 首 token（TTFT） | **1494ms** | <2s ✅ |
| Agent | SSE 流式吞吐 | **25.4–28.5 字/s** | >20 字/s ✅ |
| RAG | 首 chunk（含 LLM 往返） | **2581ms** | <4s ✅ |
| 异步落库 | 100 并发 / 库存 10000 压测 | 落库增量 = 成功下单数（两次复跑一致） | 不丢单 ✅ |
| 异步落库 | 峰值生产 TPS / 消费者吞吐 | **1374 / ~1200** | 消费者非瓶颈 ✅ |
| 异步落库 | 消费批次数 / 平均每批 | **7418→57**（130×↓）/ 1.3→175 条 | 批量聚合 ✅ |
| 安全 | Prompt/SQL 注入拦截 · 限流 · PII 三模式 · 敏感操作二次确认 | 单元 42/42 + 运行时 6/6 | 全通过 ✅ |
| 规模 | 全量回归运行时脚本 | **13 脚本 143/144**（1 项为 auth 拦截器环境级下限） | 发布门禁 8/8 ✅ |

---

## 安全设计

- **登录鉴权**：`RefreshTokenInterceptor` 滑动续期 + `LoginInterceptor` 401 拦截
- **输入清洗**：Prompt 注入 / SQL 注入检测（灵敏度可配 `injection-sensitivity`）
- **PII 脱敏**：`MASK / REMOVE / PASS` 三模式
- **敏感操作**：退款等工具二次确认（60s 超时 + 归属校验）
- **限流**：令牌桶（`rate-limit-per-minute` 可配）
- **幻觉检测**：`hallucination-check` 输出校验
- **越权防护**：Agent SSE 会话基于 ThreadLocal 用户身份，无跨用户泄漏

---

## 文档

- `CLAUDE.md` — 项目开发约定（结构、Redis Key 规范、构建注意）
- `doc/final-plan.md` — 顶层规划文档
- `doc/performance-report.md` — 性能测试报告（秒杀 / 缓存 / Agent / RAG）
- `doc/test-plan.md` + `doc/test-plan/` — 测试方案总索引 + 12 个模块用例
- `doc/test-reports/` — 测试执行报告（00 基线 ~ 12 吞吐）
- `doc/design/` — 设计文档 01~09（缓存 / 秒杀 / 异步一致性 / Agent / 检索图谱 / 安全 / 前端）
- `doc/backend-dev-process.md` / `doc/frontend-dev-process.md` — 前后端开发流程
- `doc/phase-a-report.md` ~ `phase-e-report.md` — 各阶段报告

---

## License

Internal / educational use.
