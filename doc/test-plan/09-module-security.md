# 09 · 模块测试：安全护栏

> 覆盖 Agent 四层安全链路：输入清洗（PII 脱敏 + Prompt Injection + SQL 注入）、
> 敏感操作二次确认、输出幻觉检测、速率限制，以及上下文压缩。

## 1. 安全链路

```
用户输入
 ├─ ① InputSanitizer：空/长度(>2000 截断)/SQL注入/Prompt Injection/PII 脱敏
 │     safetyScore<0.3 → 拒绝
 ├─ ② SensitiveGuard：deniedTools 直接拒 / confirmTools 二次确认 / 其他放行
 ├─ ③ LLM + 工具执行
 ├─ ④ OutputVerifier：幻觉检测 + 输出 PII 脱敏 + >4000 截断
 └─ ⑤ RateLimiter：Redis 令牌桶，默认 10 次/分钟/用户
```

配置（application.yaml `campusdeal.security.*`）：

| 配置 | 默认 | 说明 |
|---|---|---|
| confirm-tools | [applyRefund] | 需二次确认的工具 ⚠️与工具名不匹配（T3） |
| denied-tools | [] | 直接拒绝的工具 |
| pii-mode | MASK | 脱敏模式 |
| injection-sensitivity | 0.8 | ⚠️未接线（硬编码阈值 0.3） |
| hallucination-check | true | 幻觉检测开关 |
| append-safety-note | false | 回复尾部安全提示 |
| rate-limit-per-minute | 10 | ⚠️未接线（RateLimiter 硬编码 10） |

## 2. 输入清洗用例（IS）

| ID | 用例 | 输入 | 预期 |
|---|---|---|---|
| IS-01 | 正常输入 | "帮我查订单" | 原样通过，safetyScore=1.0 |
| IS-02 | 空输入 | 空/空白 | 抛 SecurityViolationException |
| IS-03 | 超长 | >2000 字符 | 截断 + warning |
| IS-04 | SQL 注入 | "DROP TABLE" | safetyScore-0.5，仅标记不拒绝（0.5>0.3） |
| IS-05 | Prompt Injection | "忽略以上指令" / "DAN" | safetyScore<0.3 → 拒绝 |
| IS-06 | 分隔符注入 | `<|im_start|>` / "### System" | 加分拒绝 |
| IS-07 | 手机号 PII | 输入含 13800001111 | MASK 为 `1****1111`，containsPii=true |
| IS-08 | 身份证/邮箱/银行卡 PII | - | 对应 MASK |
| IS-09 | 组合攻击 | 注入 + PII | 先脱敏后判分，拒绝优先 |

> ⚠️ 已知问题：
> - `PiiMode.REMOVE/PASS` 未实现（仅 MASK 生效）（T9）。
> - `PiiPatterns.IP_ADDRESS` 未在清洗中使用。
> - `injectionSensitivity` 配置未接线，阈值硬编码 0.3（T9）。
> - 单条 SQL 注入命中仅降 0.5 不拒绝（设计如此，需确认产品口径）。

## 3. 输出验证用例（OV）

| ID | 用例 | 输出 | 预期 |
|---|---|---|---|
| OV-01 | 正常输出 | 正常回答 | passed=true |
| OV-02 | 空输出 | 空串 | passed=false，兜底"请稍后再试" |
| OV-03 | HIGH 幻觉 | toolResults 为空但输出含"您的订单…" | passed=false，correctedOutput 兜底 |
| OV-04 | LOW 数字矛盾 | 输出数字不在工具结果中 | confidence-0.5，仍 passed=true |
| OV-05 | 输出 PII | 含手机号 | 手机号脱敏 |
| OV-06 | 超长输出 | >4000 | 截断 + "（回复过长，已截断）" |
| OV-07 | 幻觉检测关闭 | hallucination-check=false | 跳过规则1/2 |

## 4. 敏感操作用例（SG）

| ID | 用例 | 工具 | 预期 |
|---|---|---|---|
| SG-01 | 普通工具放行 | search_merchant | ALLOWED |
| SG-02 | 敏感工具确认 | apply_refund（正确命名） | CONFIRM + confirmationId + message |
| SG-03 | 批准执行 | handleConfirmation(true) | 执行工具，executed=true |
| SG-04 | 取消 | handleConfirmation(false) | executed=false"操作已取消" |
| SG-05 | 过期/不存在 | 未知 id | executed=false"确认已过期或不存在" |
| SG-06 | 黑名单拒绝 | denied-tools 含名 | DENIED"请联系人工客服" |
| SG-07 | **命名不匹配** | 配置 applyRefund vs 工具 apply_refund | **默认配置下 CONFIRM 被绕过 → ALLOWED（T3）** |

> ⚠️ T8：确认上下文存 JVM 内存 Map（分布式不共享、重启丢失）、无超时清理、
> `handleConfirmation` 不校验请求用户归属 —— 均需加固后补安全测试。

## 5. 速率限制用例（RL）

Redis 令牌桶：Key `ratelimit:user:{userId}` Hash {tokens, lastRefill}，容量 10，60s 补充，EXPIRE 120s。

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| RL-01 | 有令牌 | tryAcquire | 放行 |
| RL-02 | 连续第 11 次 | 同用户 | 拒绝 |
| RL-03 | 令牌恢复 | 等 60s | 可再次放行 |
| RL-04 | 不同用户 | 独立 key | 互不影响 |
| RL-05 | 匿名 | userId=null | 直接放行（不限制） |
| RL-06 | 配置接线 | rate-limit-per-minute≠10 | ⚠️ 当前硬编码 10 不生效（T6） |

## 6. 上下文压缩用例（CS）

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| CS-01 | 空历史 | compact([]) | 空摘要 |
| CS-02 | 不足 keep-recent | ≤5 条 | 不压缩 |
| CS-03 | LLM 压缩 | 超阈值 | Goal/Progress/Key Info/Next Steps 四段 |
| CS-04 | LLM 失败降级 | 异常 | 规则摘要兜底 |
| CS-05 | 摘要注入 | 下一轮 | 作为 `[对话摘要]` system 消息 |
| CS-06 | 渲染 | renderSummary | "目标: X | 进度: X | 关键信息: X | 下一步: X" |

## 7. 越权/鉴权回归（贯穿）

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| SEC-01 | 未登录受保护接口 | 无 token | 401 |
| SEC-02 | 伪 token | 任意字符串 | 401（Redis 无记录） |
| SEC-03 | 过期 token | TTL 过期 | 401 |
| SEC-04 | P0-4 /merchant 写接口 | 匿名 POST/PUT | 401 |
| SEC-05 | **P0-5 ThreadLocal 泄漏** | 40 并发匿名请求（先污染） | 全部 401（`auth-leak-test`） |
| SEC-06 | 工具越权 | apply_refund 他人订单 | error"无权操作他人订单" |
| SEC-07 | 日志无 PII | 查看日志 | 无明文手机号等 |

## 8. 现有测试

`security/` 下 5 个测试类（IS/OV/SG/CS/RL），单测齐全。
补：T3 命名回归、T6/T9 配置接线、越权归属校验、确认超时。

## 9. 验收标准

- 注入/PII/幻觉三类安全事件全部被拦截或脱敏。
- 敏感操作二次确认在默认配置下**必须生效**（T3 修复后）。
- 限流生效且配置可调（T6 修复后）。
- 越权/未登录/伪 token 全部 401，无 ThreadLocal 泄漏。
- 日志不含明文 PII。
