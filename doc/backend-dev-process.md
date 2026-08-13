# CampusDeal 后端开发流程（Backend Development Process）

> 定位：本文是 **CampusDeal 后端（Spring Boot）从需求到发布的全流程操作规程**，回答"一个后端接口 / 业务模块应该怎么落地"。
> 适用对象：所有参与后端开发的开发者（含 AI 协作会话）。
> 配套文档：[`CLAUDE.md`](../CLAUDE.md)（技术栈/环境速查）、[`doc/design/`](design/)（各功能设计文档 01–09）、[`doc/frontend-dev-process.md`](frontend-dev-process.md)（前端开发流程，本文的后端契约伙伴）。

---

## 1. 架构总览

### 1.1 技术栈与形态

- **Java 17 + Spring Boot 3.1.10**（`jakarta.*` 命名空间，非 `javax`）。
- **MyBatis-Plus 3.5.5**（`spring-boot3-starter`）ORM，实体 + `BaseMapper` + `ServiceImpl`。
- **MySQL 8.0.33**（库 `campusdeal`），**Redis**（Spring Data Redis / Lettuce）+ **Redisson 3.23.5** 分布式锁。
- **Hutool**（Bean/字符串/工具）、**Lombok**、**Actuator**。
- 异步消息：**Kafka**（秒杀订单 Topic `flash-deal-orders`，无环境时消费者不自动启动）。
- 扩展模块：**Canal**（缓存旁路）、**Caffeine + Bloom Filter**（多级缓存）、**DeepSeek/LangChain4j**（Agent）、**LangGraph4j**（ReAct）、**PGVector / Neo4j**（RAG 检索，可降级禁用）。

### 1.2 分层架构（强制）

```
Controller（参数校验 + 调 Service + 返回 Result）
   ↓
IService 接口
   ↓
ServiceImpl<XxxMapper, XxxEntity>  ← 业务逻辑、事务、分布式锁
   ↓
XxxMapper extends BaseMapper<XxxEntity>  ← 数据访问
   ↓
MySQL / Redis
```

**关键点：**

- Controller **只做薄转发**：接收参数 → 调 Service → 返回 `Result`。业务逻辑不进 Controller。
- Service 实现类继承 `ServiceImpl<Mapper, Entity>`，自动获得 `getById` / `query().eq(...)` / `save` / `updateById` 等能力。
- Mapper 继承 `BaseMapper<Entity>`；复杂 SQL 写 `resources/mapper/XxxMapper.xml`（需在 `application.yaml` 未显式配置时也能被 MyBatis-Plus 扫描到，见 §3.3）。
- 跨服务调用、定时任务、MQ 消费器分别落在 `mq/`、`cache/`、`canal/`、`agent/` 等子包。

### 1.3 请求链路

```
nginx:8080 ── /api 反代 ──► Spring Boot:8081（upstream 轮询 8081/8082）
   │
   ├─ RefreshTokenInterceptor (order=0)：读 Authorization → Redis Hash → UserHolder，滑动续期
   ├─ LoginInterceptor (order=2)：UserHolder.getUser()==null → 401
   │      └─ 公开路径在 MvcConfig.excludePathPatterns 白名单（见 §3.5）
   ├─ Controller → Service → Mapper → MySQL / Redis
   └─ 全局异常 WebExceptionAdvice → Result.fail(...)
```

### 1.4 后端目录拓扑（`src/main/java/com/campusdeal`）

```
├── CampusDealApplication.java   # 入口：@MapperScan + @EnableAspectJAutoProxy(exposeProxy=true)
├── controller/   # 前端控制器（薄层）
├── dto/          # Result / LoginFormDTO / UserDTO / ScrollResult
├── entity/       # MyBatis-Plus 实体（表映射）
├── mapper/       # BaseMapper 接口 + resources/mapper/*.xml
├── service/      # IService 接口 + impl/ServiceImpl
├── agent/        # LangGraph4j ReAct Agent：编排/工具/RAG/会话
├── cache/        # Caffeine 多级缓存 + BloomFilter
├── canal/        # Canal 缓存旁路
├── mq/           # Kafka 秒杀订单生产者/消费者
├── config/       # MvcConfig / MybatisConfig / RedissonConfig / WebExceptionAdvice
├── utils/        # CacheClient / RedisIdWorker / UserHolder / 拦截器 / 锁 / 常量
└── exception/    # BusinessException（业务异常）
```

---

## 2. 开发环境准备

### 2.1 前置依赖

| 依赖 | 要求 | 备注 |
|------|------|------|
| JDK | **17**（`D:/program/develop/jdks/jdk17`） | **系统默认 JDK 25 编译必失败**（Lombok 1.18.30 不支持），必须切 JDK17 |
| Maven | 3.x | 或使用 `mvnw` |
| MySQL | 8.0.33，`localhost:3306/campusdeal`，root/123456 | schema 在 `src/main/resources/db/campusdeal.sql` |
| Redis | `localhost:6379`，密码 `123456` | 登录态/缓存/秒杀/分布式锁 |
| Kafka / Canal / PGVector / Neo4j | **可选** | 未启动时应用照常启动（消费者 `auto-startup: false`，检索降级） |

### 2.2 编译与启动

```bash
export JAVA_HOME="D:/program/develop/jdks/jdk17"     # PowerShell: $env:JAVA_HOME="D:\program\develop\jdks\jdk17"
mvn compile                                            # 编译校验
mvn spring-boot:run                                    # 启动，监听 8081
```

> 注意：配置了 **双实例负载均衡**（nginx upstream 8081/8082），本地单实例跑 8081 即可。

### 2.3 配置管理

- `application.yaml` 统一入口；业务模块配置以 `campusdeal.*` 前缀分组（`cache` / `bloom` / `kafka` / `deepseek` / `agent` / `security` / `pgvector` / `neo4j`）。
- **敏感配置走环境变量**：`CAMPUSDEAL_DEEPSEEK_API_KEY` 等，yaml 里用 `${VAR:default}` 占位。**不要把密钥写死在代码里。**
- 数据库迁移工具（Flyway/Liquibase/ddl-runner）均关闭 → **schema 变更手工维护 `db/campusdeal.sql`**，改表时同步更新该文件。

---

## 3. 代码组织与命名规范

### 3.1 命名约定

| 项 | 约定 | 示例 |
|----|------|------|
| 包名 | `com.campusdeal.*` | `com.campusdeal.service.impl` |
| Controller | `XxxController`，`@RequestMapping("/xxx")` | `MerchantController` |
| Service 接口 | `IXxxService` | `IMerchantService` |
| 实现类 | `XxxServiceImpl extends ServiceImpl<XxxMapper, Xxx>` | `MerchantServiceImpl` |
| Mapper | `XxxMapper extends BaseMapper<Xxx>` | `MerchantMapper` |
| 实体 | `Xxx`（与表同构，驼峰） | `Merchant` / `CouponOrder` |
| DTO | `XxxDTO` / `XxxFormDTO` | `UserDTO` / `LoginFormDTO` |
| Redis key 常量 | 集中放 `RedisConstants` | `CACHE_MERCHANT_KEY` |

### 3.2 统一响应 `Result`

```java
Result.ok()                      // 成功无数据
Result.ok(data)                  // 成功带对象
Result.ok(list, total)           // 成功带分页列表
Result.fail("错误信息")           // 失败
```

- **所有 Controller 一律返回 `Result`**（唯一例外：Agent SSE 用 `SseEmitter`）。
- 业务失败抛 `BusinessException` 或直接 `Result.fail(...)`；**未知异常由 `WebExceptionAdvice` 兜底**返回"服务器异常"。

### 3.3 Mapper 与 XML

- 简单查询用 `BaseMapper` 提供的方法：`getById`、`query().eq("col", val)`（来自 `ServiceImpl` 的 `query()`）。
- 分页：`new Page<>(current, size)` + `page()`（`MybatisConfig` 已配分页插件）。
- 复杂 SQL：`resources/mapper/XxxMapper.xml`，namespace 指向 Mapper 接口；写后**用 `@Mapper` 或 `@MapperScan` 确认扫描**（入口类已 `@MapperScan("com.campusdeal.mapper")`）。

### 3.4 Redis 使用约定

- 用 `StringRedisTemplate`（JSON 字符串存取）+ **实体 `BeanUtil.toBean` / `toMap`** 序列化；key 常量统一在 `RedisConstants.java`（如 `CACHE_MERCHANT_KEY = "cache:merchant:"`）。
- 缓存三大问题按既有方案处理，**不要自行造轮子**：
  - 穿透：`CacheClient` 空值缓存 / BloomFilter（`BloomFilterService`）。
  - 击穿：逻辑过期（`RedisData` 包装）+ 后台线程重建 + 互斥锁。
  - 雪崩：TTL 加随机偏移（见 `CacheClient`）。
- 分布式锁：**用 Redisson `RLock`**（`RedissonConfig` 已配），`lock.lock()` + `try/finally unlock`；秒杀场景配合 `AopContext.currentProxy()` 保证事务与锁边界一致。
- 全局唯一 ID：`RedisIdWorker`（时间戳 + 序列）。

### 3.5 鉴权与公开路径

- 新增**受保护接口**默认即被 `LoginInterceptor` 拦截；需要公开时在 `MvcConfig.excludePathPatterns` 显式加入白名单（参照 `/merchant/**`、`/coupon/**` 等）。
- Controller 里拿当前用户：`UserHolder.getUser()`（`UserDTO`），禁止自己解析 token。
- **凡是 `/agent/**` 等敏感接口一律要求登录**，除非明确公开。

### 3.6 事务与异步

- 需要事务的方法加 `@Transactional`；**自调用不生效** → 用 `AopContext.currentProxy()`（入口已 `@EnableAspectJAutoProxy(exposeProxy=true)`）。
- 秒杀订单落库走 **MQ 异步解耦**（`mq/FlashDealConsumer`）而非同步写库；消息体见 `FlashDealOrderMessage`。
- 幂等：写操作（如秒杀）用 `IdempotentService` 防重。

### 3.7 Agent / RAG / 安全子模块（Phase 3）

- Agent 工具注册：`ToolAutoRegister` + `ToolExecutor`；新增工具实现 `Tool` 接口并在 `@Component` 中暴露。
- RAG：混合检索（BM25 + Embedding），`rag/` 包下 `HybridRetriever`；新增语料放 `resources/rag/`。
- 安全：`security/` 包负责输入净化、PII 脱敏、敏感操作确认（`security.confirm-tools`）、幻觉检测、限流。新增敏感工具需加入 `confirm-tools` 配置。

---

## 4. 后端开发标准流程（Backend Workflow）

> 核心原则：**先契约、后实现；先数据、后接口；先单测、后联调。**

```
┌────────┐ ┌────────────┐ ┌────────┐ ┌──────────┐ ┌─────────────┐
│ 需求澄清 │→│ 设计文档     │→│ 任务拆分 │→│ 契约/数据  │→│ 分层实现     │
└────────┘ └────────────┘ └────────┘ └──────────┘ └─────────────┘
                                              │
┌────────┐ ┌──────────┐ ┌──────────┐ ┌────────▼─┐
│ 发布    │←│ 回滚预案  │←│ 联调验证  │←│ 单测+冒烟  │
└────────┘ └──────────┘ └──────────┘ └──────────┘
```

### 步骤 0：需求澄清

- 明确：接口语义、入参/出参、权限（公开/登录）、是否写库/写 Redis、是否需要事务/锁/MQ。
- 与前端约定接口契约（路径、方法、参数、返回 `Result.data` 结构）。

### 步骤 1：设计文档（强制）

- 跨模块/有并发/涉数据变更的功能，先在 `doc/design/NN-*.md` 写设计文档（沿用 01–09 编号递增）。
- 后端相关必含：**表结构变更**、Redis key 设计、并发/一致性方案（锁/事务/MQ）、接口契约、回滚方案。

### 步骤 2：任务拆分

- 用 `TaskCreate` 拆分：数据层 → 服务层 → 接口层 → 测试 → 联调。
- 每个任务可独立编译通过（保证任意中间态不破坏构建）。

### 步骤 3：契约与数据设计

1. **表变更**：更新 `resources/db/campusdeal.sql`（幂等：`CREATE TABLE IF NOT EXISTS` 或增量 `ALTER`），本地执行验证。
2. **实体**：`entity/Xxx.java`（Lombok `@Data`，`@TableName`/`@TableId`/`@TableField` 标注），注意 `type-aliases-package` 为 `com.campusdeal.entity`。
3. **Redis key**：`RedisConstants` 新增常量，命名 `业务:对象:id`（如 `flashdeal:stock:{dealId}`）。
4. **接口契约**：与前端 `doc/frontend-dev-process.md` §8 对齐（URL/方法/参数/返回结构）。

### 步骤 4：分层实现（自下而上）

1. `mapper/XxxMapper.java`（+ 需要时 `resources/mapper/XxxMapper.xml`）。
2. `service/IXxxService.java` → `service/impl/XxxServiceImpl.java`（业务逻辑/事务/锁）。
3. `controller/XxxController.java`（薄转发 + `Result`）。
4. 鉴权：默认登录保护；需公开的进 `MvcConfig` 白名单。
5. 涉及 Agent 能力 → `agent/tool/` 新增 `Tool` 实现并自动注册。

### 步骤 5：单测 + 接口冒烟

- **单测**（`src/test/java/com/campusdeal/**`）：业务核心（秒杀、缓存、安全、检索、ID 生成）必须有单测；用 `mvn test` 运行。
- **冒烟**（curl，见 §5.1）：公开接口直接 curl；登录接口先取 token 再带 `Authorization` 调用；**中文参数 URL 编码**。

### 步骤 6：联调验证

- 与前端联调：路径、参数名、返回结构、`total` 分页字段、401 时机。
- **SSE**：`/agent/chat` 验证渐进流式（curl `-N`）；nginx 反代下确认不被缓冲。
- 数据一致性：秒杀下单 → 库存扣减 → 订单落库 → 消息消费全链路。

### 步骤 7：回滚预案（涉及数据时必写）

- 代码回滚：git revert / 重启旧 jar。
- 数据回滚：SQL 变更前 `mysqldump` 备份；提供反向 SQL。

### 步骤 8：发布

按 §6 执行：编译 → 打包 → 重启 → 冒烟 → 记录。

---

## 5. 测试流程

### 5.1 接口冒烟（curl）

```bash
# 公开接口
curl "http://localhost:8080/api/merchant-type/list"

# 登录 → 取 token（验证码模式 13800138000 / 123456）
curl -s -X POST "http://localhost:8080/api/user/login" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","code":"123456"}'
# → data.token，记为 $TOKEN

# 受保护接口
curl "http://localhost:8080/api/user/sign/count" -H "Authorization: $TOKEN"

# 未登录应 401
curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/user/sign/count"
# → 401

# SSE 流式（curl -N 不缓冲，观察渐进输出）
curl -N "http://localhost:8080/api/agent/chat?message=%E4%BD%A0%E5%A5%BD" \
  -X POST -H "Authorization: $TOKEN"
```

每接口至少覆盖：**参数合法 / 参数缺失 / 未登录 / 登录成功** 四条。

### 5.2 单元测试

- 目录：`src/test/java/com/campusdeal/**`（现有：seckill / cache / rag / security / service / mq / utils）。
- 优先覆盖：并发正确性（秒杀锁）、缓存失效（击穿/穿透）、Lua 脚本（`seckill.lua`）、ID 唯一性、安全净化。
- 无外部依赖（Redis/MySQL）的纯逻辑单测可直接跑；依赖 Redis 的测试确保本地 Redis 已启动。
- 运行：`mvn test`。

### 5.3 前端集成验证

- 后端改动发布后，用 `doc/frontend-dev-process.md` §5 的 **CDP 脚本** 跑端到端断言（页面 → 真实接口 → 数据渲染）。
- 重点：401 处理、`/api` 反代路径、空态/错误态、SSE。

---

## 6. 发布流程

### 6.1 发布前检查清单

- [ ] `mvn compile` 通过（JDK17）
- [ ] 设计文档反映最终实现
- [ ] 单测通过（`mvn test`）
- [ ] curl 冒烟 4 条通过（含 401）
- [ ] 前端 CDP 端到端 PASS
- [ ] 数据变更已备份 / 有反向 SQL
- [ ] `db/campusdeal.sql` 已同步表结构

### 6.2 构建

```bash
export JAVA_HOME="D:/program/develop/jdks/jdk17"
mvn clean package -DskipTests        # 产出 target/campus-deal.jar（或同名）
```

### 6.3 启动 / 重启

```bash
# 开发态
mvn spring-boot:run

# 生产态（先停旧进程）
java -jar target/campus-deal.jar --server.port=8081
```

> 双实例负载均衡：如需 8082 再启一个实例（`--server.port=8082`），nginx upstream 自动轮询。**改端口需同步 nginx 配置**。

### 6.4 回滚

| 类型 | 回滚方式 |
|------|----------|
| 代码 | git 回退到上一提交 → 重新打包 → 重启 |
| 数据 | 恢复 `mysqldump` 备份 / 执行反向 SQL |
| 配置 | 改回 `application.yaml` 上一版本 → 重启 |

---

## 7. 速查（Checklist）

### 新增一个接口

1. `controller` 加方法（`@GetMapping/@PostMapping`），参数用 `@RequestParam` / `@RequestBody`。
2. 调 Service 拿数据 → `Result.ok(data)`。
3. 需登录则不加白名单；需公开则 `MvcConfig` 加 exclude。
4. `mvn compile` → curl 冒烟。

### 新增一张表 / 实体

1. `db/campusdeal.sql` 增表（幂等写法）。
2. `entity/Xxx.java`（`@TableName` + `@Data`）。
3. `mapper/XxxMapper.java extends BaseMapper<Xxx>`。
4. `service/IXxxService.java` + `impl/XxxServiceImpl.java extends ServiceImpl<XxxMapper, Xxx>`。
5. 需要分页/复杂查询 → `resources/mapper/XxxMapper.xml`。

### Redis 读改写

- 读：查缓存 → 未命中查库 → 回填（`CacheClient` 或手动）。
- 写：先写库 → 删/更新缓存（保持一致性）。
- 并发写：Redisson `RLock` + 事务（`AopContext.currentProxy()`）。

### Agent 新增工具

1. 实现 `Tool` 接口（`agent/tool/XxxTool.java`）。
2. `@Component` 暴露，`ToolAutoRegister` 自动注册。
3. 敏感操作 → `application.yaml` `security.confirm-tools` 加名称。
4. `agent/AgentController` 无需改动（编排器按注册表分发）。

---

## 8. 前后端契约对齐

| 维度 | 约定 |
|------|------|
| 路径 | 前端统一 `/api` 前缀，后端不写 `/api`；nginx `rewrite` 去除 |
| 响应 | 一律 `Result{success,errorMsg,data,total}` |
| 认证 | `Authorization` 头传 token；401 → 前端自动登出 |
| 分页 | `current` / `size` 参数，`data` 为列表、`total` 为总数 |
| 参数 | GET 用 `@RequestParam`；POST 传 JSON `@RequestBody` |
| 时间 | 统一返回时间戳或 ISO 字符串，前端 `Utils` 格式化 |
| 图片 | 返回相对路径，前端拼 `/imgs/` 或 `SystemConstants.IMAGE_UPLOAD_DIR` 前缀 |

---

## 9. 与既有文档的关系

| 文档 | 作用 |
|------|------|
| `CLAUDE.md` | 技术栈 / 环境 / 构建 / 约定速查（最高优先级） |
| `doc/final-plan.md` | 全局业务规划与模块地图 |
| `doc/implementation-plan.md` | 分阶段实施总计划（模块 01–06） |
| `doc/design/01–09` | 各功能模块设计文档（编号递增） |
| `doc/frontend-dev-process.md` | 前端开发流程（接口契约的消费方） |
| 本文档 | **后端开发流程本身**（环境 → 规范 → 开发 → 测试 → 发布） |
