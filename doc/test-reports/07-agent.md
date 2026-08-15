# 测试报告 07 · 模块测试：AI Agent

> 日期：2026-08-15 ｜ 依据：`doc/test-plan/07-module-agent.md`
> 环境：MySQL :3306 ✅、Redis :6379 ✅、后端 :8081 ✅（local profile + DeepSeek Key，真实 LLM 调用）

## 1. 执行范围与结果

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `AgentToolsTest`（新增，T10） | TOOL-01..11 工具契约（query_order/search_merchant/query_coupon/apply_refund + **T11 JSON 转义回归**） | ✅ 12/12 |
| `SensitiveGuardTest` | SG-01..06 门禁 + **SG-07/08/09（T3 命名归一化回归）** | ✅ 9/9 |
| `AgentOrchestratorTest` | 状态图 act/answer/verify 编排 | ✅ 7/7 |
| `SessionManagerTest` | 会话存储与压缩 | ✅ 5/5 |
| `AgentControllerTest` | `/agent/chat`、`/agent/confirm` 接口 | ✅ 3/3 |
| `ToolRegistryTest` | 工具注册 | ✅ 5/5 |
| `DeepSeekChatClientTest` | LLM 客户端契约 | ✅ 5/5 |
| `SearchFaqToolTest` / `SearchGraphToolTest` | FAQ / 图检索工具 | ✅ 2/2 + 2/2 |
| `tools/agent-confirm-test.js`（新增） | **CONF-01/02 + T3 运行时端到端**：真实 LLM 调 `apply_refund` → SSE `confirm` → 批准 → 退款落库 | ✅ 10/10 |

**合计：单元 50/50 + 运行时 10/10 通过。**

> 全量单测 **160/160** 通过（模块 06 基线 145，本次 +15：AgentToolsTest 12 + SensitiveGuardTest +3）。

## 2. 测试中发现并修复的问题

### 2.1 修复 1（T3 · confirm-tools 命名不匹配导致敏感操作被绕过）

**现象**：默认配置 `security.confirm-tools: [applyRefund]`（camelCase），而工具注册名是 `apply_refund`（snake_case）。旧实现用精确匹配比较，`evaluate("apply_refund", …)` 永远命中不了 → 门禁返回 `ALLOWED` → **退款直接执行、无二次确认**。

**修复**（`SensitiveGuardImpl.matches`）：比较前统一归一化 `toSnakeCase`（camelCase→snake_case，已是 snake 原样返回），denied/confirm 两个名单都走归一化。

**验证**：
- 单元：SG-07（`apply_refund` 命中配置 `applyRefund` → CONFIRM）、SG-08（确认后按 snake 名执行原工具）、SG-09（denied 名单同样归一化）✅
- **运行时（重启后端后）**：真实 LLM 流式调用 `apply_refund` → SSE 出现 `confirm` 事件（`message=您确定要申请退款吗？退款后优惠券将失效`）→ `POST /agent/confirm {approved:true}` → 返回 `{"executed":true,"result":"{\"success\":true,\"message\":\"退款申请已提交\"}"}` → 订单 `tb_voucher_order.status` 1→5 且 `refund_time` 落库。**修复前同一场景直接执行退款、无 confirm 事件。**

### 2.2 修复 2（T11 · 工具手拼 JSON 在特殊字符下非法）

**现象**：`SearchMerchantTool`/`QueryCouponTool` 用 `String.format` 手拼 JSON，商户名/券标题含引号或反斜杠时产出非法 JSON（如 `{"name":"Coffee "Latte" \ Cafe"}`），LLM 解析失败、工具契约破坏。

**修复**：改用 Hutool `JSONUtil.createArray()/createObj().set(...)` 序列化，字段值自动转义。

**验证**：TOOL-11 两例——商户名 `Coffee "Latte" \ Cafe`、标题 `充值"立减"券` → `JSONUtil.parseObj` 往返取值完全一致 ✅。

### 2.3 修复 3（T10 · 工具层零测试）

**现象**：模块 07 测试计划标注"工具测试用例当前零测试"。

**修复**：新增 `AgentToolsTest` 12 用例（TOOL-01..11），覆盖正常/空/未登录/越权/不存在/状态不允许/JSON 转义，含 MyBatis-Plus `UpdateChainWrapper` 链式调用 mock 与 `any(Object[].class)` varargs 匹配等关键姿势。

## 3. 关键场景记录

| 场景 | 结果 |
|---|---|
| **T3 运行时回归：`apply_refund` 触发 confirm（未被绕过）** | ✅ SSE `confirm` 事件 + 工具不立即执行 |
| **CONF-02：批准后 guard 代执行退款** | ✅ `executed=true`，订单 status 1→5 + refund_time |
| CONF-03/04/05：拒绝 / 过期 / denied 名单 | ✅ SG-04/05/06 单测覆盖 |
| SSE 协议完整流：`thinking→tool_call→tool_result→confirm→chunk→done` | ✅ 10/10 脚本断言 |
| 工具 JSON 契约（含特殊字符） | ✅ TOOL-01..11 单测 |
| 会话管理 / LLM 客户端 / 工具注册 | ✅ SP/LL/TR 套件 |

## 4. 测试工程经验（本模块发现）

1. **陈旧后端是最大的运行时陷阱**：本轮先在**修复前编译产物**上跑通「LLM 调 `apply_refund` → 无 confirm → 直接退款」的错误现象，排查发现后端进程启动于 20:52、而 `SensitiveGuardImpl.class` 编译于 20:57——**运行的是旧字节码**。重启后端后同一脚本即回归通过。测试流程必须「改源码 → 编译 → **重启后端** → 运行时脚本」串行执行。
2. **PII 掩码会打断数字 ID 的端到端传递**：用户在消息里给的订单 ID（19 位数字）被安全层打成 `********`，LLM 无法用其匹配订单、转而要求澄清。正确姿势是让 LLM 从 `query_order` 工具结果（不掩码的 JSON）拿到真实 ID 再调用 `apply_refund`。
3. **JS Number 超 2^53 丢精度**：订单 ID（7.6e16）经 `JSON.parse` 成 number 后变 `…110`≠真实 `…114`，导致校验/清理查无此单。测试脚本必须用正则按**字符串**提取大整数 ID。

## 5. 待跟进（已记录，推迟至模块 09）

- `/agent/confirm` 的 `handleConfirmation` 未校验确认者 `userId` 与创建者一致（越权批准风险）；
- 待确认上下文无超时清理（`PendingConfirmation` 存活于内存 Map，60s 超时仅体现在 `timeoutSeconds` 字段、无调度清理）。

→ 进入模块 08（RAG 测试）。
