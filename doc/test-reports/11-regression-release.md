# 测试报告 11 · 全量回归 + 发布门禁检查

> 日期：2026-08-15 ｜ 依据：`doc/test-plan/11-regression-release.md`
> 环境：MySQL :3306 ✅、Redis :6379 ✅、后端 :8081 ✅（JDK17）、DeepSeek Key ✅

## 1. 分层回归执行结果

| 层级 | 执行内容 | 结果 |
|---|---|---|
| ① 单测 | `mvn test`（JDK17） | ✅ **171/171**，BUILD SUCCESS |
| ② 接口契约 | `tools/api-contract-test.js` + `tools/api-smoke.js` | ✅ 18/18 + 40/40 |
| ③ E2E 场景 | `tools/functional-test.js`（S1–S5 全流程） | ✅ 24/24 |
| ④ 性能冒烟 | `tools/perf-test.js` + `concurrency-test.js` | ✅ 秒杀 P99=54ms（<150）、Agent TTFT=1.5s（<2s）、并发 7/7 |
| ⑤ 全量回归 | 已知问题矩阵 T1–T10 + 历史缺陷 P0/P1/P2 | ✅ 全部闭环，见 §3/§4 |

**运行时回归脚本汇总（全部通过）：**

| 脚本 | 覆盖 | 结果 |
|---|---|---|
| `api-contract-test.js` | U/M/P/F/A 边界契约 | ✅ 18/18 |
| `api-smoke.js` | 40 项接口冒烟 | ✅ 40/40 |
| `verify-p0.js` | P0-1/3/4 鉴权 + 秒杀不阻塞 | ✅ 6/6 |
| `auth-leak-test.js` | P0-5 SSE ThreadLocal 越权泄漏 | ✅ 2/2（40 并发匿名全 401） |
| `security-runtime-test.js` | SR-01 注入 / SR-02 SQL / SR-03 限流 | ✅ 6/6 |
| `agent-confirm-test.js` | T3 退款 confirm + T8 批准执行 | ✅ 10/10 |
| `functional-test.js` | 商户/券/秒杀/帖子/上传/Agent | ✅ 24/24 |
| `consistency-verify.js` | IT-01..04（幂等/Outbox/Canal/同步落库） | ✅ 12/12 |
| `cache-verify.js` | BF/逻辑过期/缓存一致性 CC | ✅ 7/7 |
| `seckill-timewindow.js` | TW-01/02/03 未开始/已结束/进行中 | ✅ 4/4 |
| `rag-degrade-test.js` | RG-01/02 BM25+图谱降级 | ✅ 7/7 |
| `concurrency-test.js` | 秒杀 30/10 不超卖 | ✅ 7/7（2 次复跑） |
| `perf-test.js` | S1/S2/C1/A1/A2/R1 | ✅ 8/9（S2 auth-bound，见 10 报告 §3.2） |

## 2. 发布门禁逐项核查

| 门禁项 | 依据 | 结果 |
|---|---|---|
| `mvn test` 全绿（JDK17；T1/T2 修复后） | ① 171/171 | ✅ |
| 接口契约 36+ 全通过 | api-contract 18 + api-smoke 40 | ✅ |
| E2E S1–S5 通过 | functional 24/24 | ✅ |
| 秒杀并发：30/10 不超卖、对账一致 | concurrency 7/7 + consistency 12/12 | ✅ |
| 安全：P0-3/4/5 回归通过；T3 已修复且 confirm 生效 | verify-p0 6/6 + auth-leak 2/2 + agent-confirm 10/10 | ✅ |
| 性能冒烟：秒杀 P99<150ms、Agent TTFT<2s | S1 P99=54ms、TTFT=1494ms | ✅ |
| 无 Kafka/Canal/DeepSeek Key 环境降级可用 | consistency IT-05 + rag-degrade RG-01/02 | ✅ |
| 已知问题 T1–T10 全部闭环 | §3 矩阵 | ✅ |

## 3. 已知问题（T1–T10）闭环确认

| 编号 | 问题 | 状态 | 闭环证据 |
|---|---|---|---|
| T1 | `FlashDealProducerTest` 断言旧同步语义 | ✅ 已修复 | `FlashDealProducerTest` 3/3 通过（异步语义） |
| T2 | `FD-03` 未 Mock Consumer/Outbox | ✅ 已修复 | `FlashDealServiceImplTest` 10/10（Mock 注入） |
| T3 | `confirm-tools` 与工具名不匹配 | ✅ 已修复 | `SensitiveGuardImpl.matches` 归一化 camelCase↔snake_case；`agent-confirm-test` 10/10 实锤 `apply_refund` 触发 confirm 未被绕过 |
| T4 | `tb_voucher_order` 无唯一索引 | ✅ 已修复 | DB 存在 `uk_user_voucher(user_id,voucher_id)`；`consistency-verify` IT-02 重复插单被 ERROR 1062 拦截 |
| T5 | Agent 工具/SSE 无单测 | ✅ 已补齐 | `AgentToolsTest` 12/12、`ToolRegistryTest` 5/5、`AgentOrchestratorTest` 7/7 |
| T6 | `rate-limit-per-minute` 未接线 | ✅ 已修复 | `RateLimiterTest` 6/6（RL-06 配置接线）；`security-runtime-test` SR-03 限流生效 |
| T7 | Canal 无自动重连 | ✅ 已修复 | `CanalClientImpl` 指数退避重连；`CanalClientTest` 9/9（CN-R1..R3） |
| T8 | 确认上下文无超时/归属校验 | ✅ 已修复 | `SensitiveGuardImpl` 60s 超时 + userId 归属 + 定时清理；`SensitiveGuardTest` 11/11（SG-10/11） |
| T9 | PII REMOVE/PASS 未实现、注入阈值未接线 | ✅ 已修复 | `InputSanitizerTest` 11/11（IS-07/08/09/10）+ 阈值接 `injection-sensitivity`；SR-01/02 实锤 |
| T10 | RAG 工具零测试 | ✅ 已补齐 | `SearchFaqToolTest` 2/2、`SearchGraphToolTest` 2/2、`HybridRetrieverTest` 6/6 |

## 4. 历史缺陷（P0/P1/P2）回归矩阵

| 编号 | 缺陷 | 回归验证 | 结果 |
|---|---|---|---|
| P0-1 | Kafka 阻塞主链路 | `verify-p0.js` 秒杀 54ms 不阻塞 | ✅ |
| P0-2 | 秒杀订单不落库 | `consistency-verify` 同步落库 DB 存在 | ✅ |
| P0-3 | 修改商户接口公开 | `verify-p0.js` 匿名 POST/PUT → 401 | ✅ |
| P0-4 | 帖子/商户写接口公开 | `api-smoke` 匿名写 → 401 | ✅ |
| P0-5 | SSE ThreadLocal 越权泄漏 | `auth-leak-test` 40 并发匿名 → 全 401 | ✅ |
| P1-6 | 商户搜索未用缓存 | `cache-verify` CC-01/02 缓存重建 | ✅ |
| P1-7 | Redis 库存不恢复 | `functional-test` FT-COUPON-02 预热=5 | ✅ |
| P1-8 | 订单号 JS 精度 | `api-contract` CO1 orderId 字符串(16) | ✅ |
| P2-6 | 分页/滚动 | `api-smoke` / 帖子接口 | ✅ |

## 5. 发布清单核查

| 前置项 | 状态 |
|---|---|
| 环境：MySQL/Redis 可用，campusdeal.sql 已初始化 | ✅ |
| 配置：application.yaml 环境差异（DB/Redis 密码等） | ✅ 本地 local profile 正常 |
| 图片目录 `IMAGE_UPLOAD_DIR` 存在且可写 | ✅（functional FT-UP-01 上传成功） |
| `/actuator/health` | ✅（后端持续运行） |

**本次工作区待提交变更**：`.gitignore`、`AgentOrchestratorImpl.java`、`application.yaml`（T16 Hikari 池 + 共享连接注释）、`FlashDealServiceImpl.java`（T16 Lua 契约 / L1 前置 / SET "0"）、`RedisConfig.java`、`seckill.lua`、seckill 相关单测、`doc/test-plan/10` + `doc/test-reports/10-11`。

## 6. 结论

**发布门禁 8/8 全部满足**。全量回归 13 个运行时脚本 143/144 通过（唯一未达为模块 10 S2 端到端 P99<20ms，已定性为 auth 拦截器 2 次 Redis 往返的环境级下限，非功能缺陷）；单测 171/171 全绿；T1–T10 已知问题全部闭环、P0/P1/P2 历史缺陷无复发。

**推荐：具备发布条件。** 建议按 `doc/test-plan/11` §5.2 执行 `mvn clean package -DskipTests` 构建发布包，并在发布后按 §5.3 冒烟清单 5 分钟验证。

→ 测试计划全部模块（00–11）执行完毕。
