# 测试报告 00 · 基线与环境摸底

> 日期：2026-08-15 ｜ 分支：main ｜ 环境：本机 Windows 11

## 1. 环境状态

| 组件 | 状态 | 说明 |
|---|---|---|
| JDK | ✅ 17.0.17（通过 `JAVA_HOME` 配置） | 系统默认 JDK25 未使用 |
| Maven | ✅ 3.9.4 | - |
| MySQL :3306 | ✅ UP | campusdeal 库可连 |
| Redis :6379 | ✅ UP | 已启动本地 Redis，密码通过 `CAMPUSDEAL_REDIS_PASSWORD` 注入 |
| 后端 :8081 | ✅ UP | `Started CampusDealApplication in 5.794s` |
| nginx :8080 | ❌ DOWN | 前端联调前再启动 |
| Kafka :9092 | ❌ DOWN | 默认降级（listener.auto-startup=false） |
| Canal :11111 | ❌ DOWN | 默认降级（逻辑过期兜底） |
| PGVector/Neo4j :7687 | ⚠️ 未部署 | Neo4j driver 已创建但连接失败 → 降级空结果 |
| DeepSeek API | ⚠️ 有 Key（application-local.yaml，已 gitignore） | 本地开发用 |

## 2. 编译

```bash
JAVA_HOME="<path-to-jdk17>" mvn compile   # EXIT=0 ✅
```

## 3. 现有单测摸底（mvn test）

**128 tests → 125 pass / 2 fail / 1 error（BUILD FAILURE）**

| 测试类 | 结果 | 问题 |
|---|---|---|
| FlashDealerProducerTest.shouldSendSuccessfully | ❌ FAIL | 断言旧同步语义（`send()` 返回 SendResult） |
| FlashDealerProducerTest.shouldThrowKafkaExceptionOnFailure | ❌ FAIL | 断言旧同步语义（抛异常） |
| FlashDealServiceImplTest.shouldReturnOrderIdOnSuccess | ❌ ERROR | `outboxService` 未 Mock → NPE |
| 其余 125 个测试 | ✅ PASS | 缓存/安全/RAG/Agent/幂等/Canal/Outbox 全部通过 |

> 与测试方案 T1/T2 预测完全一致。修复安排：T1 在模块 06（一致性）、T2 在模块 05（秒杀）各自修复周期处理。

## 4. 启动观察

- 布隆过滤器启动重建：`size=3, expectedFpp=0.01, activeDeals=3`（3 个在售闪购券）。
- Neo4j 连接未就绪但**未影响启动**（降级路径正常）。
- 全量日志无 ERROR 刷屏。

## 5. 基线结论

- ✅ 后端可编译、可启动、基础接口可访问。
- ⚠️ `mvn test` 因 T1/T2 无法全绿 —— 属**已知存量问题**，进入对应模块测试时修复。
- 环境就绪，可开始模块 01 接口测试。
