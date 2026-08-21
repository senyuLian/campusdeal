# CampusDeal · 校园生活服务平台

校园 O2O 平台：**商户发现 / 优惠券闪购 / 社交内容 / AI 智能客服**。面向高校场景，围绕「附近商户 + 限时秒杀 + 种草社区 + 智能助手」四大业务，覆盖从高并发秒杀到 LLM Agent 的完整技术栈。

> 项目由 Spring Boot 3.1.10 + Redis + DeepSeek 驱动，含 Phase 2 多级缓存秒杀与 Phase 3 AI Agent / RAG。

---

## 核心特性

| 模块 | 能力 |
|---|---|
| 商户 | 分类浏览、关键字搜索、GEO 附近商户（Redis GEO）、逻辑过期缓存防击穿 |
| 优惠券 | 普通券 / 闪购券，多级缓存 + 布隆过滤 + Lua 原子扣减秒杀 |
| 秒杀 | Bloom → Caffeine L1 → Redis Lua 三层快速失败，同步落库 + Outbox 补偿，Kafka 异步解耦 |
| 社交 | 帖子发布、点赞（Redis ZSet）、关注、共同关注、滚动分页 Feed |
| AI 客服 | LangGraph4j ReAct 智能体、函数调用工具（查单/退款/商户/FAQ）、SSE 流式输出 |
| RAG | BM25（内存）+ 向量（PGVector 可选）+ 知识图谱（Neo4j 可选）混合检索 |
| 安全 | 输入清洗（Prompt 注入 / SQL 注入）、PII 脱敏三模式、敏感操作二次确认、限流 |

---

## 技术栈

- **Java 17 · Spring Boot 3.1.10**（jakarta namespace）
- **MyBatis-Plus 3.5.5**（spring-boot3-starter）· **MySQL 8.0.33**
- **Redis**：Spring Data Redis（Lettuce 共享连接）+ **Redisson 3.23.5**（分布式锁）
- **Hutool 5.7.17** · Lombok · commons-pool2 · Actuator
- **LLM**：DeepSeek API（LangChain4j）
- **Agent**：LangGraph4j（ReAct 状态图）
- **消息**：Kafka（可选，缺失时降级 Outbox 重试）

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
├── config/       MvcConfig, MybatisConfig, RedisConfig, WebExceptionAdvice
├── agent/        AI 客服：LangGraph4j 状态图、工具、SSE、安全护栏
├── rag/          RAG：BM25 索引、向量检索、知识图谱、混合检索
├── cache/        布隆过滤器、Caffeine 缓存、CacheClient
├── canal/        Canal 监听 Binlog → 缓存失效
├── mq/           Kafka 生产/消费、Outbox 补偿调度
├── seckill/      FlashDealContext / Result（秒杀上下文）
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
| DeepSeek | API Key（环境变量 `CAMPUSDEAL_DEEPSEEK_API_KEY` 或 `application-local.yaml`） |

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
| 用户 | `/user/code` `/user/login` `/user/me` `/user/logout` | 手机号验证码登录，Redis Token |
| 商户 | `/merchant/**` `/merchant-type/**` `/merchant/list/by-type` | 详情/列表/关键字/GEO 附近 |
| 优惠券 | `/coupon/**` | 列表、领取、我的券 |
| 秒杀 | `/coupon-order/seckill/{dealId}` | 闪购下单（返回字符串 orderId 防 JS 精度丢失） |
| 帖子 | `/post/**` `/follow/**` | 发布、点赞、关注、滚动分页 |
| 签到 | `/user/sign` | 按月签到 + 连续天数 |
| AI 客服 | `/agent/chat`（SSE）`/agent/confirm` `/agent/history/{id}` | 流式对话、敏感操作确认 |
| 上传 | `/upload/**` | 图片上传（公开） |

SSE 事件协议：`thinking / tool_call / tool_result / confirm / chunk / done / error`。

---

## 测试

测试与规划文档见 `doc/` 目录：

```
doc/final-plan.md           顶层规划（项目概览 / 架构 / Phase 划分 / 技术决策）
doc/performance-report.md   性能测试报告（秒杀 / 缓存 / Agent / RAG）
tools/                      运行时回归脚本（api-contract / concurrency / perf / security ...）
```

- 单元测试：`mvn test`（**171/171 全绿**，JDK17）
- 运行时回归：`node tools/<script>.js`（覆盖接口契约、并发不超卖、安全护栏、RAG 降级、性能等）
- 关键结论：秒杀 30 并发 P99=54ms、无超卖；库存耗尽后秒杀层 0 Redis 命中（L1 负缓存）；Agent TTFT<2s、流式 >20 字/s。

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
- `doc/performance-report.md` — 性能测试报告

---

## License

Internal / educational use.
