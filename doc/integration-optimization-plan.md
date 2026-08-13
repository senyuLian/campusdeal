# CampusDeal 前后端联调测试与优化 · 开发方案

> 版本：v1.0 · 日期：2026-08-13
> 前置文档：`doc/frontend-dev-process.md`、`doc/backend-dev-process.md`、`doc/design/09-home-top-redesign.md`
> 适用对象：CampusDeal 主站（Spring Boot 3.1.10 + Vue3 CDN SPA + Redis + MySQL）

---

## 1. 目标与范围

在「模块 01–06 后端 + 主站前端 Phase 1–6 + 首页两排重设计（Doc09）」均已交付的基础上，完成一次**系统级联调测试与优化**，产出三个可验证结果：

1. **全链路跑通**：登录 → 首页 → 商家/卡券/闪购/附近/搜索 → 秒杀下单 → 订单落库 → Agent 对话，端到端无阻断。
2. **缺陷清零（P0/P1）**：修复联调暴露的正确性、一致性、可用性缺陷（见 §3）。
3. **性能与质量达标**：缓存命中、秒杀吞吐、无 N+1 严重查询、日志/安全基线符合验收标准（见 §8）。

**范围边界**：仅覆盖主站前后端（不扩展新业务功能）；Agent 只做可用性联调，不做模型效果调优；canal/Neo4j/PGVector 为可降级组件，按「降级可用」验收。

---

## 2. 现状盘点

### 2.1 技术栈与运行环境

| 项 | 现状 |
|---|---|
| 后端 | Spring Boot 3.1.10 · Java 17（⚠️ 系统默认 JDK 25 会破坏 Lombok，须 `JAVA_HOME=D:/program/develop/jdks/jdk17`） |
| 数据库 | MySQL 8.0.33 `localhost:3306/campusdeal`，11 张表（`tb_*`） |
| 缓存 | Redis 6.x `localhost:6379`（密码 123456）+ Caffeine L1 + Redisson 锁 |
| MQ | Spring Kafka（生产者已接，消费者 `auto-startup: false`） |
| 前端 | Vue3 CDN ES-module SPA，无构建；nginx `:8080` 反代 `/api` → `:8081` |
| 端口 | 前端 8080 / 后端 8081 / 备份后端 8082（未运行） |

### 2.2 已交付物

- **后端 9 控制器**：User / Merchant / MerchantType / Coupon / CouponOrder / Follow / Post / PostComments / Upload，外加 `agent/AgentController`（SSE）。
- **后端 4 大模块**：多级缓存+布隆（Module 01）、秒杀核心 Lua（Module 02）、异步订单+Outbox（Module 03）、Agent ReAct+RAG+安全（Module 04–06）。
- **前端 13 页面**：home / shop-list / shop-detail / post-detail / post-edit / profile / user-profile / login / chat / search / flash / nearby / coupons。
- **已有单测**：`src/test` 覆盖 agent / cache / canal / mq / rag 共 20+ 测试类；**秒杀主流程无集成测试**。

### 2.3 依赖组件运行状态（联调前置，实测）

| 组件 | 端口 | 状态 | 影响 |
|---|---|---|---|
| MySQL | 3306 | ✅ 运行 | — |
| Redis | 6379 | ✅ 运行 | — |
| 后端 | 8081 | ✅ 运行 | — |
| nginx | 8080 | ✅ 运行 | — |
| **Kafka** | 9092 | ❌ **未运行** | 秒杀发消息阻塞 5s 后 500（见 P0-1） |
| **canal** | 11111 | ❌ 未运行 | 缓存失效仅靠 30s 逻辑过期兜底（见 P1-2） |
| DeepSeek | api.deepseek.com | ⚠️ 需外网+Key | 无 Key 时 Agent 可启动、调用提示未配置 |
| PGVector / Neo4j | — | ❌ 未部署 | 已设计降级（BM25 仍可用） |

---

## 3. 已发现问题清单（调查结论，按优先级）

### P0 — 阻断/正确性（联调必现或功能不可用）

| # | 问题 | 影响 | 位置 |
|---|---|---|---|
| P0-1 | **Kafka 未运行 + 生产者同步阻塞**：`FlashDealProducerImpl.send()` 用 `kafkaTemplate.send().get(5s)`，失败抛 `KafkaException`。秒杀在 Lua 已扣库存后进入发消息，阻塞 5 秒 → 接口 500，但库存已减、`sadd` 已记购买 → **数据不一致** | 秒杀接口实际不可用且可能超卖/丢单 | `mq/FlashDealProducerImpl.java:39` |
| P0-2 | **`outbox` 表缺失**：`OutboxScheduler` 定时 `selectList` 报 `Table 'campusdeal.outbox' doesn't exist` | 日志持续刷错；补偿机制失效 | `entity/Outbox.java`（缺 DDL） |
| P0-3 | **订单异步落库链路未打通**：消费者 `spring.kafka.listener.auto-startup: false`，即使 Kafka 起了也不消费 → 秒杀成功后订单**永不写 `tb_voucher_order`** | 用户「我的订单」查不到、库存不回补 | `application.yaml:41` + `mq/FlashDealConsumer.java` |
| P0-4 | **`/merchant/**` 全放行**：`MvcConfig` exclude `/merchant/**`，导致 `POST/PUT /merchant`（新增/更新商户）**无需登录即可写** | 越权写接口 | `config/MvcConfig.java:30` |

### P1 — 性能/安全/一致性

| # | 问题 | 影响 | 位置 |
|---|---|---|---|
| P1-1 | 秒杀 Lua 扣减成功后、Kafka 落库前若进程崩溃，Redis 已扣库存但无订单，靠消费者幂等/补偿才能对账（当前补偿又因 P0-2/P0-3 失效） | 弱一致性风险 | `seckill.lua` + Module 03 |
| P1-2 | canal 未运行，缓存失效退化为「30s 逻辑过期」+ 手动 `DEL`；商户更新后最长 30s 读到旧数据 | 缓存一致性弱 | `canal/CanalClientImpl` |
| P1-3 | 前端 `api.js` 收到 401 仅 `removeItem(TOKEN_KEY)`，不同步 `Store.token`；Store 里 `state.token` 仍为旧值 → `isLoggedIn` 误判、后续请求仍带失效 token | 鉴权状态不一致 | `js/api.js:36-38` + `js/store.js` |
| P1-4 | 日志级别 `com.campusdeal: debug`（生产应 info），高频接口刷屏 | 性能/日志噪音 | `application.yaml:106` |
| P1-5 | DeepSeek API Key 明文写死在 yaml（有 env 覆盖占位但默认值为真实 Key） | 密钥泄漏风险 | `application.yaml:70` |
| P1-6 | `GET /upload/post/delete` 用 GET 做删除（前端 `Api.get('/upload/post/delete?...')`） | REST 不规范、易被爬虫/预取误删 | `UploadController` + `js` 调用处 |

### P2 — 代码质量/可维护性

| # | 问题 | 位置 |
|---|---|---|
| P2-1 | `MerchantServiceImpl.queryNearby` 全表加载后在内存算距离+排序，商户量大时 O(n) 内存/CPU（应 GEO 或 SQL 边界框） | `service/impl/MerchantServiceImpl.java` |
| P2-2 | `PostController` / 部分 `*Impl` 存在注释掉的旧实现（`query().eq(...).page(...)`），应清理 | 多处 |
| P2-3 | 无秒杀主流程集成测试（只有 mq 单测） | `src/test` |
| P2-4 | 前端 `pageViewClass` 用硬编码数组维护「内嵌滚动页」，新增页易漏 | `js/app.js` |
| P2-5 | `status-views.js` EmptyView 补 slot 后，其余 slot 用法需回归确认 | `js/components/status-views.js` |

---

## 4. 联调测试方案

### 4.1 测试环境与前置

```bash
export JAVA_HOME="D:/program/develop/jdks/jdk17"
# 后端
mvn -q compile && mvn spring-boot:run   # 8081
# 前端由 nginx 8080 提供，无需构建，改动后强刷
# 数据前置（已有）：
#   tb_seckill_voucher 时间窗口 2026-08-01 ~ 2026-12-31
#   Redis: flashdeal:stock:{10,11,12}
```

**测试数据约定**：手机号 `13800001111`；闪购券 id `10/11/12`；普通券 id `1..9`；校区坐标 校本部(120.149993,30.334229)。

### 4.2 后端接口测试矩阵（curl 冒烟 + 鉴权断言）

> 鉴权规则：未列 `exclude` 即「需登录」；`/merchant/**`、`/coupon/**`、`/merchant-type/**`、`/upload/**`、`/user/code`、`/user/login`、`/user/public/*`、`/post/hot`、`/post/*`、`/post/likes/*`、`/post/of/user` 为公开。

| # | 方法 | 路径 | 鉴权 | 参数 | 预期 |
|---|---|---|---|---|---|
| 1 | POST | `/user/code` | 公开 | phone | 发码，Redis `login:code:{phone}` |
| 2 | POST | `/user/login` | 公开 | {phone,code} | 返回 token |
| 3 | POST | `/user/logout` | 需登录 | header authorization | 失效 Redis token |
| 4 | GET | `/user/me` | 需登录 | — | UserDTO |
| 5 | GET | `/user/info/{id}` | 需登录 | id | UserInfo（时间字段置空） |
| 6 | GET | `/user/{id}` | 需登录 | id | UserDTO |
| 7 | GET | `/user/public/{id}` | 公开 | id | UserDTO（公开） |
| 8 | POST | `/user/sign` | 需登录 | — | 幂等成功；BitMap 置位 |
| 9 | GET | `/user/sign/count` | 需登录 | — | 本月连续签到天数 |
| 10 | GET | `/merchant/{id}` | 公开 | id | 商户详情（走缓存） |
| 11 | POST | `/merchant` | ⚠️ 应需登录(P0-4) | Merchant | 新增商户 |
| 12 | PUT | `/merchant` | ⚠️ 应需登录(P0-4) | Merchant | 更新商户+失效缓存 |
| 13 | GET | `/merchant/of/type` | 公开 | typeId,current,x?,y? | 分页商户 |
| 14 | GET | `/merchant/nearby` | 公开 | x,y,current | 距离升序分页（含 distance 米） |
| 15 | GET | `/merchant/of/name` | 公开 | name,current | 名称模糊分页 |
| 16 | GET | `/merchant-type/list` | 公开 | — | 分类列表 |
| 17 | GET | `/coupon/list/{shopId}` | 公开 | shopId | 店铺券列表 |
| 18 | GET | `/coupon/flash/list` | 公开 | — | 进行中闪购券（含 shopName） |
| 19 | GET | `/coupon/list/all` | 公开 | — | 全站券（普通+闪购） |
| 20 | POST | `/coupon-order/seckill/{id}` | 需登录 | id | 见 §4.4 秒杀场景 |
| 21 | PUT | `/follow/{id}/{isFollow}` | 需登录 | id,isFollow | 关注/取关 |
| 22 | GET | `/follow/or/not/{id}` | 需登录 | id | 是否已关注 |
| 23 | GET | `/follow/common/{id}` | 需登录 | id | 共同关注 |
| 24 | GET | `/post/hot` | 公开 | current | 热门帖子分页 |
| 25 | GET | `/post/{id}` | 公开 | id | 帖子详情 |
| 26 | GET | `/post/likes/{id}` | 公开 | id | 点赞列表 |
| 27 | GET | `/post/of/user` | 公开 | id,current | 他人帖子 |
| 28 | GET | `/post/of/me` | 需登录 | current | 我的帖子 |
| 29 | GET | `/post/of/follow` | 需登录 | lastId,offset | 关注 feed |
| 30 | POST | `/post` | 需登录 | Post | 发帖 |
| 31 | PUT | `/post/like/{id}` | 需登录 | id | 点赞/取消 |
| 32 | POST | `/upload` | 公开 | multipart | 图片上传 |
| 33 | GET | `/upload/post/delete` | ⚠️ 建议改 POST/DELETE(P1-6) | name | 删除图片 |
| 34 | POST | `/agent/chat` | 需登录 | SSE | 流式输出 |
| 35 | GET | `/agent/history/{sessionId}` | 需登录 | sessionId | 历史消息 |
| 36 | POST | `/agent/confirm` | 需登录 | confirm | 敏感操作确认 |

**鉴权冒烟**：每个「需登录」接口分别测「无 token → 401」「有效 token → 200」。

### 4.3 前端页面测试矩阵（CDP 无头 Chrome）

| 路由 | 页面 | 关键断言 |
|---|---|---|
| `/` | home | 搜索栏、金刚区 5 入口、宫格 5+更多、校区切换、搜索面板热词/历史 |
| `/shops/:typeId` | shop-list | 商户卡片、分页、类型标题 |
| `/shop/:id` | shop-detail | 详情、券列表、`merchant-card` |
| `/search?kw=` | search | 分类直达（美发→丽人·美发）、空态「问智能助手」 |
| `/flash` | flash | 进行中闪购券 ≥1、`coupon-card` |
| `/shops/nearby` | nearby | 距离排序、无限滚动 |
| `/coupons` | coupons | 全部/普通/闪购 tab、券卡片 |
| `/post/:id` | post-detail | 详情、点赞 |
| `/post-edit` | post-edit | 需登录守卫 |
| `/profile` | profile | 需登录守卫、用户信息 |
| `/user/:id` | user-profile | 公开主页 |
| `/login` | login | 验证码流程 |
| `/chat` | chat | 需登录守卫、SSE 流式 |

**三态断言**：每页 `loading → ready/empty/error`；接口失败触发 ErrorView+重试；空态 EmptyView。

### 4.4 端到端核心场景（必测）

**S1 登录→签到→角标**
发码 → Redis 读码 → 登录得 token → 首页签到 → 角标显示 1 → 重复签到不变。
（已验证通过，纳入回归。）

**S2 秒杀下单 → 订单落库（当前断裂，P0 修复后重点）**
1. 登录态 `POST /coupon-order/seckill/{id}`。
2. 断言：Redis `flashdeal:stock:{id}` 减 1、`flashdeal:order:{id}` 含 userId。
3. 断言（P0-3 修复后）：`tb_voucher_order` 出现该 orderId。
4. 重复下单 → `Already purchased`（幂等）。
5. 库存为 0 → `Out of stock`；并发压测无超卖。

**S3 缓存一致性**
1. `GET /merchant/{id}` 命中 `cache:merchant:{id}`。
2. `PUT /merchant` 更新后 → 缓存失效（canal 未起时验证 30s 逻辑过期 + 主动失效）。

**S4 Agent SSE**
`POST /agent/chat` 渐进返回 `thinking → 输出块 → done(sessionId)`；`/agent/history/{sessionId}` 可回读。

**S5 鉴权与越权**
未登录访问「需登录」接口 → 401；P0-4 修复后 `POST/PUT /merchant` 未登录 → 401。

### 4.5 测试工具

- **后端**：`curl` 冒烟脚本（`tools/api-smoke.sh`）+ 可选 JMeter/ab 压秒杀。
- **前端**：`tools/cdp-verify-doc09.js` 扩展为全页面回归（CDP + Node WebSocket，无 puppeteer）。
- **单测**：补 `FlashDealServiceImpl` 集成测试（H2/Testcontainers 或 mock Redis）。

### 4.6 测试验收标准

- 后端 36 接口：公开接口 200、需登录接口无 token=401/有 token=200、参数缺失优雅报错。
- 前端 13 页面：CDP 断言 PASS、`JS_ERRORS=0`、三态齐全。
- 5 大端到端场景全绿；秒杀并发无超卖、订单最终落库。
- 日志无 P0 级 ERROR（除可降级组件的预期告警）。

---

## 5. 优化方案

### 5.1 P0（先做，阻断项）

**P0-1 + P0-3 秒杀异步链路修复（合并处理）**

方案 A（推荐，环境无 Kafka 时最稳）——**同步落库 + 异步兜底**：
1. `executeFlashDeal` 成功后**同步** `couponOrderMapper.insert`（复用 `FlashDealConsumer.buildOrder`），使订单立即落库；Kafka 发送改为「尽力而为 + 失败只记日志不回滚」。
2. 将 `FlashDealConsumer`/`OutboxScheduler` 降级为**补偿对账**而非主路径。
3. 若坚持异步为主：需本地起 Kafka（`docker run kafka` 或 Windows 单机 KRaft），并把 `auto-startup: true`；生产再切换。**本环境无 Docker/Kafka 依赖，推荐方案 A**。

涉及文件：`mq/FlashDealProducerImpl.java`（send 改为 try-catch 吞异常+异步回调）、`service/impl/FlashDealServiceImpl.java`（同步落库）、`application.yaml`（auto-startup 说明）。

**P0-2 补 outbox DDL**

新增 `src/main/resources/db/outbox.sql`（或随 MyBatis-Plus 启动建表），表结构对齐 `entity/Outbox.java`：
```sql
CREATE TABLE IF NOT EXISTS `outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `message_id` VARCHAR(128) NOT NULL,
  `topic` VARCHAR(64),
  `payload` TEXT,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `retry_count` INT DEFAULT 0,
  `error_msg` VARCHAR(512),
  `create_time` DATETIME,
  `update_time` DATETIME,
  PRIMARY KEY (`id`),
  KEY `idx_status_create` (`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
并在 `OutboxScheduler` 增加「表不存在时跳过并降级日志」的防御（避免刷错）。

**P0-4 收紧 `/merchant` 写权限**

`MvcConfig` 从 exclude 移除 `/merchant/**` 改为：
```java
// 只公开读接口，写接口回归登录保护
"/merchant/**/GET",   // 若框架支持按方法排除；否则：
```
Spring MVC 的 `excludePathPatterns` 不支持按 HTTP 方法区分。**改用**：保留 `/merchant/**` 排除，但在 `MerchantController.saveShop/updateShop` 内部校验 `UserHolder.getUser()==null → 401/Result.fail`；或更优——把写接口移到独立路径（如 `/merchant-admin/**`）并纳入登录保护。**最小改动**：`saveShop`/`updateShop` 增加 `UserHolder` 校验。

### 5.2 P1（随 P0 一起做）

- **P1-3** 前端鉴权状态：`api.js` 401 时通过全局事件/直接调用 `Store.forceLogout()` 清 `token/user`，并可选跳登录。
- **P1-4** 日志级别：`application.yaml` 改 `com.campusdeal: info`（秒杀关键路径保留 debug）。
- **P1-5** 密钥：删除 yaml 默认明文值，改为 `api-key: ${CAMPUSDEAL_DEEPSEEK_API_KEY:}`（空则禁用 LLM 调用）。
- **P1-6** 删除改 `POST /upload/delete`（前端 `Api.post`），保留旧 GET 做 302 兼容过渡。
- **P1-1/P1-2** 一致性：同步落库后 P1-1 基本消除；canal 未起则显式在 `updateShop` 后 `DEL cache:merchant:{id}`（已有）+ 文档注明「生产接 canal」。

### 5.3 P2（可后续迭代）

- **P2-1** nearby 改 Redis GEO（`GEOADD merchant:geo:{typeId}` + `GEOSEARCH`）或 SQL 边界框过滤，避免全表内存排序。
- **P2-2** 清理注释掉的旧实现。
- **P2-3** 补秒杀主流程集成测试。
- **P2-4** `pageViewClass` 改为页面组件内声明 `embedded: true` 元数据，免维护数组。
- **P2-5** 回归 EmptyView slot 改动对旧页面无影响。

---

## 6. 实施步骤（阶段划分）

> 每阶段可独立提交/回滚；标注 ⛔ 为不可逆动作（改表、删数据）需先备份。

### 阶段 A：环境就绪与基线测试（半天）
1. 起后端（JDK17）+ 确认 8080/8081/3306/6379 就绪。
2. 跑 §4.2 接口矩阵 + §4.3 页面矩阵，**建立当前缺陷基线**（记录哪些失败）。
3. 备份：`mysqldump campusdeal > backup-campusdeal-$(date).sql`；`redis-cli SAVE`（⛔ 前）。

### 阶段 B：P0 修复（半天–1 天）
4. 补 `outbox` DDL 并落库（⛔ 前先 dump）。
5. 秒杀改同步落库 + Kafka 降级（P0-1/P0-3）。
6. 收紧 `/merchant` 写权限（P0-4）。
7. `mvn -q compile` 通过 → 重启后端。

### 阶段 C：P1 修复（半天）
8. 前端 401 状态同步（P1-3）。
9. 日志级别 info（P1-4）；密钥去明文（P1-5）；删除接口改 POST（P1-6）。
10. 一致性兜底确认（P1-1/P1-2）。

### 阶段 D：全量回归联调（1 天）
11. 重跑 §4.2 全接口矩阵（含鉴权冒烟）。
12. CDP 全页面回归（扩展 `tools/cdp-verify-doc09.js`）。
13. 端到端 S1–S5 全绿；秒杀并发压测无超卖。

### 阶段 E：性能与收尾（可选，半天）
14. P2-1 nearby GEO 优化（若商户量 > 1k）；P2-3 补集成测试。
15. 更新 `doc/` 文档、清理临时脚本、汇总验收报告。

---

## 7. 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| 秒杀改同步落库引入性能瓶颈 | 落库仅 INSERT 一条，量级可控；异步补偿仍保留 | git 回退 `FlashDealServiceImpl`/`ProducerImpl` |
| outbox 建表与既有表冲突 | 用 `CREATE TABLE IF NOT EXISTS` | drop 前 dump |
| `/merchant` 收权限影响前端写操作 | 前端无写商户入口，影响面 0 | 回退 `MvcConfig` |
| 密钥去明文导致 Agent 不可用 | 走 env 注入；留空时 LLM 降级提示 | 回退 yaml |

---

## 8. 验收清单（最终）

> 执行结果详见 `doc/phase-a-baseline-report.md` ~ `phase-e-report.md`（2026-08-13 全部完成）。

- [x] 后端 36 接口：公开=200、需登录=401/200 正确、异常优雅。（`regression-phase-d.js` 53/53）
- [x] 前端 13 页面：CDP PASS + `JS_ERRORS=0` + 三态齐全。（`cdp-pages.js` 14/14）
- [x] S1 登录签到、S2 秒杀落库、S3 缓存一致性、S4 Agent SSE、S5 鉴权越权 全绿。
- [x] 秒杀并发无超卖；`tb_voucher_order` 与 Redis 库存对账一致。（`concurrency-test.js` 7/7）
- [x] 日志无 P0 ERROR；`outbox` 表存在且调度器不再报错。
- [x] P0/P1 问题项全部关闭（含新增 P0-5 越权泄漏），P2 项有明确 owner/排期（P2-6 已修复）。
- [x] `tools/` 下回归脚本可一键复跑；文档同步更新。

---

## 附：关键文件索引

| 文件 | 作用 |
|---|---|
| `config/MvcConfig.java` | 鉴权 exclude（P0-4 改这里） |
| `mq/FlashDealProducerImpl.java` | Kafka 发送（P0-1 改这里） |
| `mq/FlashDealConsumer.java` | 订单落库+幂等（P0-3 复用 buildOrder） |
| `service/impl/FlashDealServiceImpl.java` | 秒杀编排（P0-3 加同步落库） |
| `entity/Outbox.java` / 新 `db/outbox.sql` | Outbox 表（P0-2） |
| `application.yaml` | 日志级别/密钥/Kafka auto-startup（P1） |
| `js/api.js` / `js/store.js` | 前端 401 状态同步（P1-3） |
| `tools/cdp-verify-doc09.js` | 前端回归脚本（扩展为全页面） |
