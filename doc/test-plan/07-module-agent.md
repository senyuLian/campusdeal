# 07 · 模块测试：AI Agent

> 覆盖 LangGraph4j ReAct 状态图、DeepSeek 客户端、工具注册与执行、SSE 流式协议、
> 会话存储与上下文压缩、敏感操作确认。

## 1. 状态图

```
START → sanitize →(条件) think →(条件) act / answer / finish
                        ↑            │
                        └── act ─────┘
answer / finish → verify → END
```

| 节点 | 职责 |
|---|---|
| sanitize | 输入清洗（见 09 安全文档）；拒绝则直接 END |
| think | 调 LLM；路由：`iteration>=maxIterations(5)` → finish；有 pendingToolCalls → act；否则 → answer |
| act | 执行工具 / 推 confirm / 记录 tool_result |
| answer | 流式回答（chunk 事件） |
| finish | 迭代超限收尾 |
| verify | 输出幻觉检测与 PII 脱敏（见 09） |

边界：最多 5 次 think、4 次 act。

## 2. SSE 事件协议（与 02 文档一致）

`POST /agent/chat?message&sessionId` → `text/event-stream`，SseEmitter 超时 300s。
事件：`thinking / tool_call / tool_result / confirm / chunk / done / error`。
格式注意：`chunk`、`done`、限流`error` 是裸数据；其余是 `AgentEvent` 包裹；
`tool_call`/`confirm` 的 `content` 为二次编码 JSON。

## 3. 工具契约

| 函数名 | 入参 | 说明 | 鉴权/边界 |
|---|---|---|---|
| `query_order` | 无 | 查本人订单 | 未登录→`{"error":"用户未登录"}` |
| `search_merchant` | `keyword`(必填) | LIKE name limit 10 | keyword 空→error |
| `query_coupon` | 无 | status=1 limit 10 | - |
| `apply_refund` | `orderId`(必填) | status 1/2→5 | 未登录/不存在/越权/状态不允许 → error |
| `search_faq` | `keyword` | RAG FAQ 检索 topK=5 | 见 08 |
| `search_graph` | `query` | Neo4j 图谱查询 | 见 08 |

### 工具测试用例（当前零测试，T10）

| ID | 用例 | Mock | 预期 |
|---|---|---|---|
| TOOL-01 | query_order 正常 | 用户有订单 | `{"orders":[{"id":..,"voucherId":..,"status":..}]}` |
| TOOL-02 | query_order 空 | 无订单 | `{"orders":[]}` |
| TOOL-03 | query_order 未登录 | UserHolder 空 | `{"error":"用户未登录"}` |
| TOOL-04 | search_merchant 命中 | name like | 商户列表 ≤10 |
| TOOL-05 | search_merchant 空 keyword | - | `{"error":"keyword 不能为空"}` |
| TOOL-06 | query_coupon | status=1 | 券列表 |
| TOOL-07 | apply_refund 正常 | 本人订单 status=1 | success=true，status→5 |
| TOOL-08 | apply_refund 越权 | 他人订单 | `{"error":"无权操作他人订单"}` |
| TOOL-09 | apply_refund 订单不存在 | null | `{"error":"订单不存在"}` |
| TOOL-10 | apply_refund 状态不允许 | status=5 | `{"error":"订单状态不允许退款"}` |
| TOOL-11 | JSON 转义 | 商户名/标题含引号 | 返回合法 JSON（`%s` 手拼有风险） |

## 4. 编排用例（SG）

| ID | 用例 | 输入 | 预期 |
|---|---|---|---|
| SG-01 | 直接回答 | LLM 无工具调用 | sanitize→think→answer→verify→done |
| SG-02 | 单轮工具 | LLM 调 1 工具 | think→act→think→answer |
| SG-03 | 多轮工具 | 连续 2+ 工具 | act→think 回环正确 |
| SG-04 | 迭代超限 | 5 次 think | finish 收尾，不无限循环 |
| SG-05 | verify 拒绝 | 输出幻觉 | 兜底回复"请稍后再试" |
| SG-06 | 工具执行失败 | 工具抛异常 | ToolResult 转 error，流程不中断 |
| SG-07 | 清洗拒绝 | 注入输入 | 直接 END，error 事件 |
| SG-08 | 异步完成 | 真实 chat() | 返回 emitter，异步执行 completeChat + complete() |
| SG-09 | 工具调用 UserHolder | act 执行工具 | UserHolder 正确传递当前用户 |

> 现有 `AgentOrchestratorTest`（SG-01..07）只断言 emitter.complete()，
> **未断言事件名/data/顺序**——补 SSE 协议级断言（S4 脚本覆盖 E2E）。

## 5. 敏感操作确认用例

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| CONF-01 | 触发 confirm | actNode 遇 confirm-tools | 推 confirm 事件，工具不执行 |
| CONF-02 | 批准 | /agent/confirm approved:true | guard 代执行工具，executed=true |
| CONF-03 | 拒绝 | approved:false | 不执行，fail"操作已取消" |
| CONF-04 | 过期/不存在 | 未知 confirmationId | fail"确认已过期或不存在" |
| CONF-05 | 工具为 denied | denied-tools 含名 | DENIED，不执行 |
| CONF-06 | **命名不匹配回归** | 配置 `applyRefund` vs 工具 `apply_refund` | **当前默认配置下 confirm 被绕过（T3）**，修复后须回归 |

> ⚠️ T3：`campusdeal.security.confirm-tools:[applyRefund]`（驼峰）与工具注册名 `apply_refund`
> 不匹配，`SensitiveGuard.evaluate` 用 `toolName` contains 判断 → 默认配置走 ALLOWED。
> 另：确认上下文在 JVM 内存 Map、`handleConfirmation` 不校验 userId、无超时清理（T8）。

## 6. 会话与压缩用例（SP）

### 6.1 存储

- Key：`agent:session:{sessionId}`（Redis Hash），TTL=30min（每次 save 刷新）。
- 字段：`userId`、`messages`（JSON 数组，≤20 条）、`summary`（压缩摘要）。
- `MAX_HISTORY=20`：超出裁最旧；工具消息不入历史。

### 6.2 压缩

- 触发：`save` 后 `history.size() > 15`。
- 策略：`KEEP_RECENT=5`，压缩最旧 N-5 条；LLM 生成 Goal/Progress/Key Info/Next Steps，
  失败降级为规则摘要。
- 摘要注入：下一轮作为 `[对话摘要] ...` system 消息。

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| SP-01 | 新会话 | 无 sessionId | 生成 UUID，空会话 |
| SP-02 | 已有会话 | 传 sessionId | Redis 解析出历史 |
| SP-03 | save 追加 | 一轮对话 | messages 追加 user+assistant，TTL 刷新 |
| SP-04 | 裁剪 | 超 20 条 | 保留最近 20 |
| SP-05 | 压缩触发 | 超 15 条 | summary 字段写入 Redis |
| SP-06 | 摘要残留 | 压缩后历史回落 | 旧 summary 不清理（已知行为） |
| SP-07 | 跨会话隔离 | A/B 两个 sessionId | 互不串扰 |
| SP-08 | TTL 过期 | 30min | 会话丢失（可接受） |

## 7. DeepSeek 客户端用例（LL）

| ID | 用例 | 预期 |
|---|---|---|
| LL-01 | chatSync 无工具 | 委托 OpenAiChatModel |
| LL-02 | chatSync 带工具 | toolSpecifications 透传 |
| LL-03 | chatStream | 委托 OpenAiStreamingChatModel |
| LL-04 | 空 Key 构造 | 可构造，不崩 |
| LL-05 | 空 Key 调用 | 抛 IllegalStateException（编排层降级） |
| LL-06 | 429/超时 | 有重试/超时策略（依赖实现） |

## 8. 工具注册用例（TR）

| ID | 用例 | 预期 |
|---|---|---|
| TR-01 | 注解扫描注册 | `ToolAutoRegister` 收集全部 `@Tool` 方法 |
| TR-02 | 规格生成 | 参数类型映射（Long→integer、Double→number、Boolean→boolean、其余→string），全必填 |
| TR-03 | 参数转换 | Jackson convertValue 按名取值 |
| TR-04 | 未知工具 | error |
| TR-05 | 执行异常 | error，不崩 |

## 9. 验收标准

- 状态图所有分支可达且收敛（不无限循环、不挂起）。
- SSE 事件按协议输出，前端可消费（含二次 JSON.parse 场景）。
- 工具正确性 + 越权/状态校验。
- 会话持久化、TTL、裁剪、压缩正确。
- 敏感操作二次确认生效（**T3 修复后**）。
- 无 DeepSeek Key 时降级可用（thinking→error 收尾）。
