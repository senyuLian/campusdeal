# Prime Agent 架构分析 & 对 campusdeal 智能助手的可复用思路

> 分析目标：[PrimeIntellect-ai/prime-agent](https://github.com/PrimeIntellect-ai/prime-agent)（TypeScript，MIT License，~13.8k stars）
> 编写日期：2026-08-12

---

## 1. Prime Agent 是什么

Prime Agent 是一个**自改进的 RLM（Recursive Language Model）编码 Agent**，面向长运行时间的自主任务。核心由两个抽象组成：

- **RLM（Recursive Language Model）**：把上下文视为变量（prompt-as-a-variable），把子 Agent 当作函数调用（`rlm(...)`），运行在持久化 IPython REPL 中
- **Continual Harness**：将补充 prompt、记忆、技能描述、子 Agent 规格等作为持久化状态，支持通过 `/refine` 进行小步、证据驱动的自我改进

一句话概括：**将 Agent 的完整生命周期（会话、上下文、技能、子任务、调度）作为一等公民管理**。

---

## 2. 架构全景

```
┌──────────────┐     ┌──────────────┐     ┌─────────────────────────────┐
│  TUI / CLI   │────▶│  Supervisor  │────▶│  Session Worker (进程隔离)    │
│  (客户端)     │     │  (路由+附加)  │     │  ┌───────────────────────┐  │
└──────────────┘     └──────────────┘     │  │ AgentSessionRuntime    │  │
       ▲                                  │  │  ├─ AgentSession        │  │
       │                                  │  │  │  ├─ IPython Kernel  │  │
       │                                  │  │  │  └─ RLM Children   │  │
       │                                  │  │  ├─ Scheduler          │  │
       │                                  │  │  └─ Goals / Heartbeats │  │
       │                                  │  └───────────────────────┘  │
       │                                  └─────────────────────────────┘
       │                                              │
       └──────────────────────────────────────────────┘
                  JSONL 持久化 + Artifacts
```

关键设计决策：

| 决策 | 说明 | 对 campusdeal 的启示 |
|---|---|---|
| **进程隔离** | Worker 和 Kernel 是独立进程，生命周期隔离，崩溃可恢复 | Agent 请求处理可异步化，与 HTTP 请求线程解耦 |
| **IPython 是唯一内置工具** | 文件读写、shell、子 Agent 全部从 Python 内核发起，不每种能力一个工具调用 | 工具统一走 Function Call，不扩散工具类型 |
| **Daemon 后台运行** | 终端断开后 session 仍存活，可重新 attach | 客服会话可在用户离开后保留，支持异步通知 |
| **JSONL 树形存储** | 会话支持分支（branch/fork/clone），所有版本可追溯 | 对话可支持"重新回答"分支，形成多条回答链 |

---

## 3. RLM 编程模型 — 最核心的设计理念

### 3.1 四个不变式

```
1. 执行是程序化的（Execution is programmatic）
   - IPython 是模型唯一的内置工具，所有操作通过 Python 代码完成
   - Python 状态在多次工具调用和压缩之间持续存活

2. 子 Agent 是原生的 RLM 调用
   - rlm("review auth flow", name="auth-reviewer") 直接生成子 Agent
   - 立即返回 handle，不等结果；结果通过消息或文件异步到达
   - 子 Agent 注册表在压缩和内核重启后仍然存活

3. 技能是可执行的能力包
   - SKILL.md 做渐进披露（名称+描述在系统 prompt，完整指令按需加载）
   - Python-backed skill 直接在 IPython 内核中可调用

4. 状态设计为跨越多次交互
   - 自动压缩、Daemon、心跳、目标、自主模式让长任务不断档
```

### 3.2 执行流程

```
用户提示 → AgentSession → LLM Stream → [文本 | IPython工具调用]
                      ↑                        ↓
                   压缩/目标           IPython Kernel 执行
                                                ↓
                                     [Host Request（类型安全）]
                                     [普通结果/输出/错误]
```

### 3.3 对 campusdeal 的启示 — ReAct 循环类比

RLM 的循环本质就是 ReAct 模式：
- **Reasoning** = LLM 文本输出 + IPython 代码推理
- **Action** = IPython 工具调用 = 我们的 Function Call
- **Observation** = 工具返回结果

campusdeal 用 **LangGraph4j** 实现完全对应，IPython 的角色由 Java 端的工具调用机制替代。

---

## 4. 上下文压缩策略 — 直接可复用 ⭐

### 4.1 Compaction 机制

Prime Agent 的 compaction 是最值得直接借鉴的设计：

```
触发条件：contextTokens > contextWindow - reserveTokens
      默认 reserveTokens = 16384, keepRecentTokens = 20000

  entry:  0     1     2     3      4     5     6      7      8     9
        ───────────────────┬────────────────── ─────────────────────
              要压缩的消息（旧）         保留的消息（新）
                               ▲
                      firstKeptEntryId

压缩后 LLM 看到的上下文：
  ┌──────────┬──────────┬──────────────────────────┐
  │ 系统提示  │  摘要    │  从 firstKeptEntryId 开始的消息  │
  └──────────┴──────────┴──────────────────────────┘
```

### 4.2 结构化摘要格式 — 核心参考

```markdown
## Goal
[用户想完成什么]

## Constraints & Preferences
- [用户提到的限制条件]

## Progress
### Done
- [x] [已完成的任务]

### In Progress
- [ ] [正在进行的工作]

### Blocked
- [遇到的问题]

## Key Decisions
- **[决策]**：[理由]

## Next Steps
1. [下一步行动]

## Critical Context
- [继续工作所需的关键信息]

<read-files>
path/to/file1.ts
</read-files>

<modified-files>
path/to/changed.ts
</modified-files>
```

### 4.3 对 campusdeal 的应用

```
campusdeal 智能助手的上下文管理：

1. 滑动窗口 + 历史摘要（task.txt 已提及）直接复用这个结构
   - 保留最近 N 轮完整对话
   - 更早的对话压缩为以上格式的结构化摘要
   - 摘要跨轮递进（新摘要 merge 旧摘要）

2. 适配业务场景的摘要模板：
   ## Goal → 用户本次咨询的目的（退券/查订单/找店铺）
   ## Progress → 已完成的操作（已查到订单/已确认退款金额）
   ## Key Decisions → 用户选择（选了方案A/确认了退款方式）
   ## Critical Context → 订单号、优惠券ID等关键数据
   ## Next Steps → 待用户确认的操作
```

---

## 5. 技能系统 — 渐进披露模式 ⭐

### 5.1 设计

```
启动时：只有技能的 name + description 进系统 prompt（一次性加载）
使用时：Agent 读取完整 SKILL.md 获取详细指令（按需加载）

SKILL.md 格式：
---
name: my-skill
description: 何时使用此技能的精确描述（决定触发条件）
---

# 完整指令、脚本路径、参考资料引用
```

### 5.2 对 campusdeal 的应用 — 工具定义的渐进披露

```
当前规划（task.txt）：
  工具通过 Function Call 暴露给 LLM

可优化为两级披露：
  Level 1 — 系统 prompt 中的工具清单（短描述，始终在上下文）
    "query_order: 查询用户订单（需要订单号）"
    "refund: 发起退款（需要用户二次确认）"
    "search_shop: 按名称/分类搜索店铺"
    ...

  Level 2 — 完整工具文档（Agent 判断需要时才拉取）
    包含参数详情、返回值格式、错误码、调用示例
    可存储为 Java resource 文件或 Redis 缓存

好处：
  - 减少系统 prompt 的 token 消耗
  - 复杂工具（如退款流程）的完整说明不会挤占每次对话的上下文
```

---

## 6. 会话管理 — 可用于对话存储

### 6.1 Prime Agent 的会话模型

```
会话 = JSONL 树形结构
  - 每个 entry 有 id + parentId
  - 支持分支（/tree）：从一个历史节点分出多条回答链
  - 支持 fork/clone：创建独立会话文件
  - 支持 compaction entry：标记压缩点，记录 firstKeptEntryId

用途：
  用户说"试另一种方案" → 从分叉点新建分支，不丢失原来的思路
```

### 6.2 对 campusdeal 的应用

```
Redis 中的对话存储设计：

Key: agent:conversation:{sessionId}
类型: List (每轮对话为一个 JSON 元素)

每个对话轮次:
{
  "turnId": "uuid",
  "parentTurnId": "uuid | null",   ← 支持分支
  "role": "user" | "assistant",
  "content": "...",
  "toolCalls": [...],
  "timestamp": 1234567890
}

压缩摘要单独存储:
Key: agent:summary:{sessionId}
Value: 结构化摘要 JSON (Goal/Progress/Decisions/CriticalContext)

优势：
  - parentTurnId 天然支持"重新回答"分支
  - 压缩摘要独立于对话历史，方便更新
  - List 结构支持按索引范围读取（滑动窗口）
```

---

## 7. 安全与可控性 — 对应 task.txt 的要求

### 7.1 Prime Agent 的信任模型

- IPython 内核以用户操作系统的权限运行，**不是安全沙箱**
- 第三方技能需要审查
- 可信仓库、指令、技能才可以运行
- Worker 和内核的进程隔离是为了生命周期管理，不是安全边界

### 7.2 对 campusdeal 的应用

task.txt 已经做了更细化的安全设计，Prime Agent 可参考的是其**分层验证思路**：

```
Prime Agent 的设计：
  Host Request（类型安全网关） → 只有 IPython 内核发起的请求
  需经过 TypeScript 主机的验证才能执行

campusdeal 的对应设计：
  Function Call → Agent Service 层拦截 → 权限校验 + 敏感操作二次确认
  
  安全分层：
  Layer 1 — 输入层：Prompt 注入检测 + PII 脱敏（task.txt 已规划）
  Layer 2 — 执行层：工具调用前做权限校验（UserHolder + 业务规则）
  Layer 3 — 输出层：幻觉溯源校验，不通过 → 降级转人工
  Layer 4 — 敏感操作：强制用户二次确认（退款/下单等）
```

---

## 8. 多 Agent 协作 — 可参考的子 Agent 模式

### 8.1 Prime Agent 的子 Agent 模型

```python
# 并行启动 3 个独立子 Agent
api_review = await rlm("Review the public API", name="api-reviewer")
test_review = await rlm("Review the test coverage", name="test-reviewer")
integration = await rlm("Run the slow integration audit", name="integration-audit")

# 子 Agent 注册表在压缩和内核重启后存活
children = await rlm.list_subagents()

# Agent 间通信
await agent_message.send("check this", receiver_role="child", receiver_name=api_review.name)
```

### 8.2 对 campusdeal 的应用

```
多 Agent 拆分的潜在场景（可选扩展）：

┌─────────────────────┐
│   Router Agent      │  ← 意图识别 + 路由
│   (主客服入口)       │
└──────┬──────────────┘
       │
   ┌───┼───────────┐
   ▼   ▼           ▼
┌─────┐ ┌─────┐ ┌─────┐
│订单  │ │店铺  │ │优惠券│   ← 领域专家 Agent
│Agent │ │Agent │ │Agent │
└─────┘ └─────┘ └─────┘

路由策略（task.txt 的"高频意图路由小模型"）：
  - 简单问题 → 小模型直接回答
  - 复杂/多步操作 → 路由到大模型 + 对应 Agent
  - 子 Agent 间可传递上下文（订单号、店铺ID等）
```

---

## 9. 运行模式 — 长任务支持

### 9.1 Prime Agent 的长运行特性

| 特性 | 机制 | 说明 |
|---|---|---|
| 后台运行 | Daemon worker 进程 | 终端断开后 session 继续存活 |
| 心跳 | `/heartbeat` / `rlm_heartbeat` | 周期性地给 session 注入 prompt |
| 定时 | `prime-agent schedule` | 一次性或 cron 定时触发 |
| 持久目标 | `/goal` | 跨轮次的持久化目标，直到完成/暂停/清除 |
| 自主模式 | `/autonomous` | 在 turn/token/time 预算约束下自主运行 |

### 9.2 对 campusdeal 的应用

```
客服场景的长任务需求：

1. 异步通知
   - 退款处理中 → 用户可离开 → 处理完成后推送通知
   - Redis 存 pending 任务状态 + 结果回调

2. 会话超时
   - N 分钟无新消息 → 自动压缩上下文 → 存为摘要
   - 用户回来时恢复上下文

3. 客服转人工
   - Agent 无法解决 → 标记会话状态 → 人工客服接管
   - Redis 存上下文摘要，人工客服能看到对话历史

4. 定时触发（可选）
   - "券还有 1 小时过期"提醒
   - "关注的店铺上新了"推送
```

---

## 10. 总结：可复用优先级排序

### 直接可用（优先级 P0）

| 来源（Prime Agent） | 应用到 campusdeal |
|---|---|
| **结构化摘要格式**（Compaction summary template） | 滑动窗口上下文压缩的摘要模板：Goal / Progress / Key Decisions / Critical Context / Next Steps |
| **渐进披露**（Skill progressive disclosure） | 工具分两级：短描述始终在 prompt，完整文档按需加载。减少系统 prompt token 消耗 |
| **会话分支持**（parentTurnId） | 对话轮次加 parentId，天然支持"换种回答"分支 |
| **Host Request 类型安全网关** | Function Call 结果经过类型校验 + 业务权限校验后才执行 |

### 需要适配后可用（优先级 P1）

| 来源 | 应用 | 适配工作 |
|---|---|---|
| **ReAct 循环**（RLM loop → LangGraph4j） | 状态图编排：推理 → 行动 → 观察 → 循环。条件边约束行为边界 | task.txt 已规划，直接实现 |
| **子 Agent 模式**（`rlm(...)` → Router + Specialist） | 意图路由 + 领域专家 Agent 拆分 | 先做单 Agent 验证，再拆分 |
| **多 Agent 通信**（`agent_message`） | 子 Agent 间传递上下文（订单号等） | 用 Redis pub/sub 或直接方法调用 |
| **长任务支持**（Daemon → async + Redis 状态） | Agent 异步处理 + 会话恢复 | Spring @Async + Redis 状态存储 |

### 架构理念参考（优先级 P2）

| Prime Agent 理念 | 对 campusdeal 的长期价值 |
|---|---|
| **Continual Harness**（自我改进） | 未来可做：根据客服对话反馈，自动优化 prompt / 工具描述 |
| **自主模式**（autonomous with quality gates） | 批量操作场景：如自动处理退款申请、批量核销券 |
| **Skill as package**（可执行能力包） | 新业务线接入时，以"技能包"形式引入新工具 |

---

## 附录：Prime Agent 与 campusdeal 架构对照表

| 维度 | Prime Agent | campusdeal（规划） |
|---|---|---|
| **语言/框架** | TypeScript + IPython | Java 17 + Spring Boot 3.1 + LangGraph4j |
| **Agent 循环** | RLM（IPython 内核为执行环境） | ReAct（LangGraph4j 状态图 + Function Call） |
| **持久化环境** | IPython Kernel（Python 变量存活） | 无持久化运行时（每次 Agent 轮次独立执行） |
| **会话存储** | JSONL 文件（树形结构） | Redis（List / Hash，含 parentTurnId 支持分支） |
| **上下文管理** | Compaction（结构化摘要 + 滑动窗口） | 滑动窗口 + 历史摘要 + 多层导航（task.txt） |
| **进程模型** | Daemon + Worker + Kernel 三层隔离 | Spring Web 线程池 + @Async 异步 |
| **技能系统** | SKILL.md + Python-backed 可调用包 | Java Service 作为 Function Call Tool |
| **LLM 调用** | 多 Provider（Anthropic/OpenAI/等） | 待定 |
| **安全** | 信任模型（权限=用户） | 四层防护（输入/执行/输出/敏感操作） |
