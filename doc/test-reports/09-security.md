# 测试报告 09 · 模块测试：安全护栏

> 日期：2026-08-15 ｜ 依据：`doc/test-plan/09-module-security.md`
> 环境：MySQL :3306 ✅、Redis :6379 ✅、后端 :8081 ✅、DeepSeek Key ✅

## 1. 执行范围与结果

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `InputSanitizerTest` | IS-01..10 + IS-04b（含 **IS-07 REMOVE / IS-08 PASS / IS-09 IP / IS-10 组合攻击，本次新增**） | ✅ 11/11 |
| `SensitiveGuardTest` | SG-01..11（含 **SG-10 越权归属 / SG-11 确认超时+清理，本次新增**） | ✅ 11/11 |
| `RateLimiterTest` | RL-01..06（含 **RL-06 T6 配置接线**） | ✅ 6/6 |
| `OutputVerifierTest` | OV-01..07 幻觉 / PII / 截断 / 开关 | ✅ 5/5 |
| `CompactionServiceTest` | CS-01..06 空历史 / 阈值 / LLM 失败降级 / 摘要注入 | ✅ 6/6 |
| `AgentControllerTest` | AC-01..03（AC-03 改为**传 userId 的 confirm**） | ✅ 3/3 |
| `tools/security-runtime-test.js`（新增） | **SR-01 注入拦截 / SR-02 SQL 拦截 / SR-03 限流** | ✅ 6/6 |

**合计：单元 42/42 + 运行时 6/6 通过。**

> 全量单测 **161/161** 通过（模块 08 基线 161，本次安全相关测试补强后数量不变、全部通过）。

## 2. 测试中发现并修复的问题（开发修复）

### 2.1 T6 · 限流容量配置未接线

**缺陷**：`RateLimiterImpl` 用硬编码 `DEFAULT_CAPACITY=10`，`rate-limit-per-minute` 配置不生效。

**修复**（`RateLimiterImpl.java`）：注入 `SecurityProperties`，`capacity()` 读取配置，`<=0` 回退 10；`tryAcquire` 与 `availableTokens` 均改用 `capacity()`。

**验证**：`RL-06` 用 `ArgumentCaptor` 断言 `execute` 的第 2 个 vararg 为配置值 3；运行时 SR-03 置空令牌桶后请求被拒（`请求过于频繁`）。

### 2.2 T8 · 敏感操作确认：无超时、无归属校验

**缺陷**：`SensitiveGuardImpl.handleConfirmation` 不校验请求用户是否为确认发起者，确认上下文无超时、无清理——任意登录用户可批准他人的敏感操作。

**修复**：
- 接口 `handleConfirmation(confirmationId, approved, userId)` 增加 userId 参数；
- 实现校验确认归属（`pending.userId.equals(userId)`，否则「无权确认该操作」）与超时（60s，`confirmationTimeoutMs` 包私有可测）；
- 新增 `@Scheduled(fixedRate=30_000) evictExpired()` 定时清理超时条目；
- `AgentController.confirm` 从 `UserHolder` 取当前用户 id 传入。

**验证**：`SG-10` 非创建者 9999L 确认 →「无权」；`SG-11` 超时 30ms + sleep 60ms →「已过期」且 `evictExpired` 清空；`AC-03` 断言 controller 以 `(cfm-1, true, 1001L)` 委托。

### 2.3 T9 · 注入灵敏度未接线 + PII 仅 MASK

**缺陷**：
- 输入清洗阈值硬编码 `MIN_SAFETY_SCORE=0.3`，`injection-sensitivity`（默认 0.8）未接线 → SQL 注入（-0.5）在默认配置下被放行；
- `PiiMode.REMOVE/PASS` 未实现，仅 MASK 生效；
- `PiiPatterns.IP_ADDRESS` 未参与清洗。

**修复**（`InputSanitizerImpl.java`）：
- 删除硬编码阈值，改用 `securityProperties.getInjectionSensitivity()`；
- PII 处理改为三模式 + IP 规则：MASK 脱敏 / REMOVE 移除 / PASS 仅检测告警（不改原文）；
- `maskPattern` 按 mode 处理替换值。

**验证**：`IS-04` 默认 0.8 下 SQL 注入被拒；`IS-04b` 低灵敏度 0.2 仅标记不拒绝（保留宽松产品口径）；`IS-07/08/09` 三模式与 IP；运行时 SR-02 用真实注入语句验证拦截。

## 3. 限流运行时验证与一次测试脚本缺陷（非生产缺陷）

SR-03 初版**误报**：置空令牌桶后请求仍被放行。经逐项排查（EVAL 直测 Lua → `:-1` 正确；三态实验：远古 lastRefill→放行✅ / 新鲜→拦截✅ / 未来→拦截✅），确认**生产限流逻辑正确**，误报源于**测试脚本时序**：

- 测试用 `redisRaw` 每次新建连接、靠 3s socket 超时才解析返回；
- 写 `lastRefill=now` 后还需 2 次 HGET 确认，累计 ~6-9s 才发起 chat；
- 令牌桶按 60s/容量 10 = **6s 补 1 个令牌**，请求到达时桶「合法」恢复 1 个令牌 → 放行。

**修复**（`tools/security-runtime-test.js`）：socket 超时降至 500ms，并在**紧邻 chat 前**用 HSET 刷新 `lastRefill=now`，保证 elapsed < 补发间隔。修复后 6/6 通过。

## 4. 关键场景记录

| 场景 | 结果 |
|---|---|
| Prompt Injection「忽略以上所有指令」→ SSE `error`，未进 LLM | ✅ SR-01（`error,done`，无 chunk） |
| SQL 注入 `'; DROP TABLE users; --` 默认阈值 0.8 拦截 | ✅ SR-02 |
| 令牌桶耗尽（tokens=0, lastRefill=now）→ 请求被限流 | ✅ SR-03（`error: 请求过于频繁`） |
| 远古 lastRefill → 按时间补发令牌放行 / 未来 lastRefill → 拦截 | ✅ 三态实验（容量/补发间隔语义正确） |
| 确认归属：非创建者确认 →「无权」；超时 60s →「已过期」 | ✅ SG-10/11 |
| PII 三模式：MASK / REMOVE / PASS（PASS 不改原文仅告警）+ IP | ✅ IS-07/08/09 |
| 组合攻击（注入 + PII）拒绝优先 | ✅ IS-10 |
| 限流容量取配置 3 而非硬编码 10 | ✅ RL-06（ArgumentCaptor） |

## 5. 结论

模块 09 发现并修复 **3 处生产缺陷**（T6 限流配置接线、T8 确认越权/超时、T9 注入阈值接线 + PII 三模式），补强 5 个新单测与运行时安全验证脚本。安全链路（清洗 → 敏感操作门禁 → 限流）单元 42/42、运行时 6/6 全部通过；期间定位并修复的 SR-03 误报为**测试脚本时序缺陷**，生产限流逻辑经三态实验确认正确。

→ 进入模块 10（性能测试）。
