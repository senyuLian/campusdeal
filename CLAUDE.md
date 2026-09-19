# CampusDeal - Campus Life Service Platform

A campus-focused O2O platform: merchant discovery / flash deals / social feed / AI customer service. Built on **Spring Boot 3.1.10 + Redis + DeepSeek**.

## Tech Stack

- Java 17, Spring Boot 3.1.10 (jakarta namespace)
- MyBatis-Plus 3.5.5 (`spring-boot3-starter`), MySQL 8.0.33
- Redis: Spring Data Redis (Lettuce) + Redisson 3.23.5 (distributed locks)
- Kafka: spring-kafka (seckill async order persistence); Canal client (binlog → cache invalidation)
- Hutool 5.7.17, Lombok, commons-pool2, Actuator
- LLM: DeepSeek API (via LangChain4j)
- Agent: LangGraph4j (ReAct state graph)

## Environment

- Port: `8081`
- MySQL: configure `CAMPUSDEAL_DB_URL`, `CAMPUSDEAL_DB_USERNAME`, and `CAMPUSDEAL_DB_PASSWORD`
- Redis: configure `CAMPUSDEAL_REDIS_HOST`, `CAMPUSDEAL_REDIS_PORT`, and `CAMPUSDEAL_REDIS_PASSWORD`
- Image upload dir: configure `CAMPUSDEAL_UPLOAD_ROOT` (default `./data/uploads`)

## Project Structure

```
src/main/java/com/campusdeal
├── CampusDealApplication.java   # Entry: @MapperScan + @EnableAspectJAutoProxy(exposeProxy=true)
├── controller/   PostController, MerchantController, CouponController, UserController, UploadController, etc.
├── dto/          Result, LoginFormDTO, UserDTO, ScrollResult
├── entity/       Post, Merchant, MerchantType, Coupon, FlashDeal, CouponOrder, User, Outbox, etc.
├── mapper/       MyBatis-Plus BaseMapper
├── service/      IService interfaces + impl (ServiceImpl<Mapper, Entity>)
├── config/       MvcConfig, MybatisConfig, RedissonConfig, RedisConfig, WebExceptionAdvice
├── agent/        AI agent: LangGraph4j state graph, 6 tools, SSE, session/compaction
├── rag/          RAG: BM25 index, PGVector, Neo4j knowledge graph, RRF hybrid retrieval
├── security/     input sanitization, PII masking, sensitive-op guard, rate limiting, output verification
├── cache/        Bloom filter, Caffeine L1, cache properties
├── canal/        Canal binlog listener → cache invalidation
├── mq/           FlashDealProducer / FlashDealConsumer (batch) / OutboxScheduler
├── seckill/      FlashDealContext/Result, LuaScriptConfig, RedissonLockHelper
└── utils/        CacheClient, RedisIdWorker, UserHolder, interceptors, lock, constants
```

## Core Conventions

### Unified Response `Result`
`{ success, errorMsg, data, total }`, static factories: `Result.ok(data)` / `Result.fail(msg)`.
All Controllers return `Result`. New endpoints follow this convention.

### Layered Architecture
Controller → Service interface → `ServiceImpl<XxxMapper, XxxEntity>` → Mapper.
Extend `ServiceImpl` to get `getById` / `query().eq(...)` for free.

### Redis Key Convention (`utils/RedisConstants.java`)
- Login code: `login:code:{phone}` (TTL 2min)
- Login token: `login:token:{token}` (Hash stores UserDTO, TTL 36000s)
- Merchant cache: `cache:merchant:{id}` (logical expire)
- Nearby merchants: `merchant:geo:{typeId}`
- Post likes: `post:liked:{postId}`
- Follow feed: `feed:{userId}`
- Sign-in: `sign:{userId}:{year}:{month}`
- Flash deal stock: `flashdeal:stock:{dealId}`
- Lock: `lock:merchant:{id}`, `lock:order:{userId}`

### Auth & Security (`config/MvcConfig.java`)
- `RefreshTokenInterceptor` (order=0): Read `authorization` header → Redis Hash → `UserHolder`, sliding TTL refresh
- `LoginInterceptor` (order=2): `UserHolder.getUser() == null` → 401
- Public paths: `/user/code`, `/user/login`, `/post/hot`, `/post/*`, `/post/likes/*`, `/post/of/user`, `/user/public/*`, `/merchant/**`, `/merchant-type/**`, `/upload/**`, `/coupon/**`, `/chat.html`, `/css/**`, `/js/**`
- **New protected endpoints (e.g. `/agent/**`) are blocked by default** — add to MvcConfig excludes or require token

### Cache & Concurrency Tools
- `CacheClient`: Cache penetration (null-value caching) + breakdown (logical expire, `RedisData` wrapper + background thread rebuild + mutex lock)
- `RedisIdWorker`: Global unique ID (timestamp 32-bit + Redis INCR sequence 32-bit)
- `UserHolder`: ThreadLocal for current user, `UserHolder.getUser().getId()` for user ID
- Flash deals: Bloom filter → Caffeine L1 → Redis Lua atomic deduction; Kafka async persistence (consumer SETNX idempotency + UNIQUE KEY + Outbox compensation)

## Build & Run

⚠️ **The system default JDK may be newer than the project's required Java 17**. Compile with a Java 17 distribution because the pinned Lombok version may not support newer JDKs (symptom: `@Data` getters/setters "cannot find symbol").

```bash
export JAVA_HOME="<path-to-jdk17>"
mvn compile
mvn spring-boot:run
```

## AI Customer Service Agent

- LangGraph4j state graph orchestrating ReAct Agent (max 5 iterations)
- Function Call tools (6): order query, coupon lookup, refund, merchant search, FAQ search, knowledge-graph search
- RAG: BM25 + vector (PGVector) RRF hybrid retrieval; Neo4j knowledge graph as a separate tool
- SSE streaming output (`thinking / tool_call / tool_result / confirm / chunk / done`)
- Safety: input sanitization (prompt/SQL injection), PII masking (MASK/REMOVE/PASS), sensitive-op confirmation, hallucination check, rate limiting, context compaction

## Key Differences from campusdeal (Original Project)

- Entity renames: Blog→Post, Shop→Merchant, Voucher→Coupon, SeckillVoucher→FlashDeal
- Fixed: login verification logic (line 81 was `&&` → now `||`)
- Fixed: logout now invalidates Redis token (was only clearing ThreadLocal)
- Added: Phase 2 (multi-level cache + seckill) & Phase 3 (Kafka async persistence / Canal / Agent / RAG / security) modules with full dependencies
