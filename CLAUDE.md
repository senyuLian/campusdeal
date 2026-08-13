# CampusDeal - Campus Life Service Platform

A campus-focused O2O platform: merchant discovery / flash deals / social feed / AI customer service. Built on **Spring Boot 3.1.10 + Redis + DeepSeek**.

## Tech Stack

- Java 17, Spring Boot 3.1.10 (jakarta namespace)
- MyBatis-Plus 3.5.5 (`spring-boot3-starter`), MySQL 8.0.33
- Redis: Spring Data Redis (Lettuce) + Redisson 3.23.5 (distributed locks)
- Hutool 5.7.17, Lombok, commons-pool2, Actuator
- LLM: DeepSeek API (via LangChain4j, Phase 3)
- Agent: LangGraph4j (ReAct state graph, Phase 3)

## Environment

- Port: `8081`
- MySQL: `localhost:3306/campusdeal`, root / 123456 (see `application.yaml`)
- Redis: `localhost:6379`, password `123456`
- Image upload dir: `D:\program\develop\redis_project\nginx-1.18.0\html\campusdeal\imgs` (`SystemConstants.IMAGE_UPLOAD_DIR`)

## Project Structure

```
src/main/java/com/campusdeal
├── CampusDealApplication.java   # Entry: @MapperScan + @EnableAspectJAutoProxy(exposeProxy=true)
├── controller/   PostController, MerchantController, CouponController, UserController, etc.
├── dto/          Result, LoginFormDTO, UserDTO, ScrollResult
├── entity/       Post, Merchant, MerchantType, Coupon, FlashDeal, CouponOrder, User, etc.
├── mapper/       MyBatis-Plus BaseMapper
├── service/      IService interfaces + impl (ServiceImpl<Mapper, Entity>)
├── config/       MvcConfig, MybatisConfig, RedissonConfig, WebExceptionAdvice
├── agent/        ★ Agent module (Phase 3): LangGraph4j state graph, tools, RAG, security
├── utils/        CacheClient, RedisIdWorker, UserHolder, interceptors, lock, constants
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
- Public paths: `/user/code`, `/user/login`, `/post/hot`, `/merchant/**`, `/merchant-type/**`, `/upload/**`, `/coupon/**`
- **New protected endpoints (e.g. `/agent/**`) are blocked by default** — add to MvcConfig excludes or require token

### Cache & Concurrency Tools
- `CacheClient`: Cache penetration (null-value caching) + breakdown (logical expire, `RedisData` wrapper + background thread rebuild + mutex lock)
- `RedisIdWorker`: Global unique ID (timestamp 32-bit + Redis INCR sequence 32-bit)
- `UserHolder`: ThreadLocal for current user, `UserHolder.getUser().getId()` for user ID
- Flash deals: Redisson `RLock` distributed lock + `AopContext.currentProxy()` for transaction guarantee

## Build & Run

⚠️ **System default JDK is 25 (`D:\program\develop\jdk`), but project requires Java 17**. Compiling with system JDK will fail because Lombok 1.18.30 does not support JDK 25 (symptom: `@Data` getters/setters "cannot find symbol").

```bash
export JAVA_HOME="D:/program/develop/jdks/jdk17"
mvn compile
mvn spring-boot:run
```

## In Progress — AI Customer Service Agent

- LangGraph4j state graph orchestrating ReAct Agent
- Function Call tools: order query, coupon lookup, refund, merchant search
- RAG hybrid retrieval (BM25 + Embedding)
- SSE streaming output
- Safety: input sanitization, PII masking, sensitive-op confirmation, hallucination traceability, human handoff

## Key Differences from campusdeal (Original Project)

- Entity renames: Blog→Post, Shop→Merchant, Voucher→Coupon, SeckillVoucher→FlashDeal
- Fixed: login verification logic (line 81 was `&&` → now `||`)
- Fixed: logout now invalidates Redis token (was only clearing ThreadLocal)
- Added: pom.xml Phase 2/3 dependency placeholders
