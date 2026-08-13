# 设计文档 08：智能助手与主站前端集成

> 所属 Phase：Phase 3 收尾（前端一体化）
> 模块定位：把独立部署的智能助手聊天页（Spring Boot static MPA）**整合进主站 SPA**，作为主站的一个页面，由主站首页按钮进入
> 依赖关系：依赖 04-Agent / 06-安全模块的后端接口（`/agent/**`），依赖 07-主站前端重构产出的 SPA 基础设施

---

## 1. 现状分析与集成目标

### 1.1 当前两个"前端"的割裂

```
┌─────────────────────────────────────────────┐      ┌────────────────────────────────────────────┐
│  主站前端 (SPA)                               │      │  智能助手前端 (MPA)                           │
│  nginx:8080  →  html/campusdeal/                   │      │  Spring Boot:8081  →  static/chat.html       │
│  · Vue3 + Vue Router (hash)                  │      │  · 原生 JS (IIFE)                            │
│  · 8 个路由页面 / 统一 Store / Api 层         │      │  · 自带登录面板                              │
│  · token: campusdeal-token                   │      │  · token: campusdeal-token（不统一！）             │
│  · /api 前缀走 nginx 反代                     │      │  · 裸 /agent/chat（直连 8081）               │
└─────────────────────────────────────────────┘      └────────────────────────────────────────────┘
```

**割裂带来的问题：**

| 编号 | 问题 | 影响 |
|------|------|------|
| C01 | **双前端双入口**：用户从主站点不到智能助手，智能助手页在另一个端口 `8081/chat.html` | 体验割裂 |
| C02 | **token 键不统一**：主站用 `campusdeal-token`，chat.js 用 `campusdeal-token` | 主站登录态在智能助手页不生效，需重复登录 |
| C03 | **智能助手页自带登录面板**：与主站 login 页重复，流程冗余 | 维护成本翻倍 |
| C04 | **API 前缀不一致**：chat.js 直接 `fetch('/agent/chat')`（依赖 8081 直连），主站统一走 `/api` 反代 | 集成后必须改为 `/api/agent/chat` |
| C05 | **样式体系隔离**：chat.css 独立写死颜色值，未复用主站 CSS 变量 | 视觉不统一 |
| C06 | **无会话恢复**：sessionId 只存内存，刷新即丢失，/agent/history 接口未被使用 | 体验缺失 |

### 1.2 集成目标

> 一句话：**智能助手成为主站 SPA 的一个页面（`/chat`），从主站首页底部导航的「智能助手」按钮进入；登录态、token、样式与主站完全统一。**

```
主站首页 (home)
   │  ┌─ 底部导航「🤖 智能助手」
   ▼  │
  /chat ────────────────► 智能助手页面（ChatPage）
   │                         · 复用 Store 登录态（无需再登录）
   │                         · 通过 /api 反代调 /agent/chat (SSE)
   │                         · 复用主站设计系统样式
   ▼
app-header（带返回按钮）→ 返回主站
```

### 1.3 设计约束（不变量）

1. **不改后端**：Agent 接口契约（`/agent/chat`、`/agent/history/{id}`、`/agent/confirm`）保持不变，仅前端整合。
2. **前后端分离**：前端文件统一收口到 `nginx-1.18.0/html/campusdeal/`，由 nginx:8080 提供；Spring Boot static 下的聊天文件标记废弃。
3. **SSE 必须用 fetch 流式读取**：`EventSource` 无法携带 `Authorization` 头（接口受 `LoginInterceptor` 保护），继续沿用 `fetch + ReadableStream` 方案。
4. **登录态完全复用主站 Store**：智能助手页不出现登录表单；未登录访问 `/chat` 由路由守卫重定向到 `/login?redirect=/chat`。

---

## 2. 后端接口契约（集成依赖）

### 2.1 接口清单

| 接口 | 方法 | 鉴权 | 说明 |
|------|------|------|------|
| `/agent/chat?message=&sessionId=` | POST | ✅ 需 token | SSE 流，事件：thinking / tool_call / tool_result / confirm / chunk / done |
| `/agent/history/{sessionId}` | GET | ✅ 需 token | 返回 `Result<AgentSession>`（含 messages 历史），用于会话恢复 |
| `/agent/confirm` | POST | ✅ 需 token | body `{ confirmationId, approved }`，敏感操作二次确认 |
| `/user/code`、`/user/login` | POST | ❌ 公开 | 主站 login 页已覆盖，智能助手页不再需要 |

> ⚠️ **路径前缀**：主站 SPA 由 nginx:8080 提供，后端仅经 `/api` 反代可达（`/api/agent/chat` → 后端 `/agent/chat`）。智能助手页所有请求**必须带 `/api` 前缀**，这是与旧 chat.html 最大的调用差异。

### 2.2 SSE 事件协议（前端消费方视角）

```
POST /api/agent/chat?message=...&sessionId=...
  Content-Type: text/event-stream  ← 后端返回的流

事件序列（一次完整对话）：
  event: thinking     data: {"content":"正在查询您的订单..."}    可多次
  event: tool_call    data: {"content":"{\"tool\":\"queryOrders\",...}"}
  event: tool_result  data: {"content":"查询到 3 笔订单..."}
  event: confirm      data: {"content":"{\"confirmationId\":\"...\",\"message\":\"...\"}"}
  event: chunk        data: "最终回答的增量文本"                   可多次
  event: done         data: {"sessionId":"...","totalTokens":1234}  收尾
  event: error        data: {"content":"错误提示"}
```

### 2.3 确认请求体

```json
POST /api/agent/confirm
Authorization: <token>
{ "confirmationId": "xxxx", "approved": true }
```

---

## 3. 集成方案总览

### 3.1 新前端文件结构

```
nginx-1.18.0/html/campusdeal/
├── index.html                          # 不变
├── css/
│   └── app.css                         # ✚ 追加 chat-* 样式段
└── js/
    ├── utils.js                        # ✚ 追加 renderMarkdown()
    ├── router.js                       # ✚ 追加 /chat 路由
    ├── agent/
    │   └── sse.js                      # ✚ 新增：Agent SSE 客户端（fetch + ReadableStream）
    └── pages/
        └── chat.js                     # ✚ 新增：ChatPage Vue 组件（替代 static/chat.js）
```

### 3.2 关键决策（ADR）

| # | 决策 | 理由 |
|---|------|------|
| D1 | 聊天页做成 SPA 路由 `/chat`，而非 iframe 嵌入 | 完整复用 header/footer/token/路由守卫；iframe 会引入双滚动条、双登录态、样式难统一 |
| D2 | 入口为底部导航第 4 个按钮「智能助手」+ 个人页入口双保险 | 用户明确要求"主站下方三个按钮处再增加一个入口"；底部导航在首页/个人页常驻、入口显眼，个人页再加一行提高可达性 |
| D3 | 登录复用主站 login 页（`requiresAuth` 守卫），删除智能助手页内嵌登录面板 | 消除 C02/C03，一套登录逻辑 |
| D4 | 封装独立 SSE 客户端模块 `js/agent/sse.js` | 与 Vue 生命周期解耦，可单独测试；组件卸载时可 Abort 取消流 |
| D5 | Markdown 渲染迁入 `Utils.renderMarkdown` | chat.js 原有极简渲染器（escape→代码块→行内码→加粗→换行）复用，无 marked.js 依赖 |
| D6 | 会话 sessionId 持久化到 `sessionStorage['campusdeal-chat-session']` | 刷新/返回后可恢复同一会话；可选调用 `/agent/history/{id}` 回放历史 |
| D7 | 样式并入 `app.css`（`chat-` 前缀），废弃独立 chat.css | 复用设计系统 CSS 变量，视觉统一 |

---

## 4. 路由与入口设计

### 4.1 路由注册（`js/router.js`）

```js
{
    path: '/chat',
    name: 'chat',
    component: () => import('./pages/chat.js'),
    meta: { requiresAuth: true, title: '智能助手' },
},
```

- `requiresAuth: true` → 未登录自动跳 `/login?redirect=/chat`，登录后回跳（路由守卫已实现，无需改代码）。
- 路由守卫现状（`router.js` beforeEach）已兼容，无需改动。

### 4.2 底部导航第 4 个按钮（主入口）

现有底部导航有 3 个按钮（首页 / 发帖 / 我的），**新增第 4 个按钮「智能助手」**作为主入口。底部导航在首页、个人页常驻，入口随时可达。

**FootBar 组件改造**（`js/components/foot-bar.js`）：

```html
<nav class="foot-bar" role="navigation" aria-label="主导航">
    <div class="foot-bar__item" :class="{ 'foot-bar__item--active': isActive('home') }"
         @click="navigate('/')">
        <span class="foot-bar__icon">🏠</span>
        <span>首页</span>
    </div>
    <div class="foot-bar__item" :class="{ 'foot-bar__item--active': isActive('chat') }"
         @click="handleChat">
        <span class="foot-bar__icon">🤖</span>
        <span>智能助手</span>
    </div>
    <div class="foot-bar__item foot-bar__item--center"
         @click="handlePost">
        <span class="foot-bar__icon">＋</span>
        <span>发帖</span>
    </div>
    <div class="foot-bar__item" :class="{ 'foot-bar__item--active': isActive('profile') }"
         @click="navigate('/profile')">
        <span class="foot-bar__icon">👤</span>
        <span>我的</span>
    </div>
</nav>
```

**FootBar methods 追加：**

```js
handleChat() {
    if (!Store.isLoggedIn) {
        this.$router.push('/login?redirect=/chat');
    } else {
        this.$router.push('/chat');
    }
},
```

**app.css 适配：**

```css
/* 4 个按钮等宽排布（默认 space-around 即可，微调间隙） */
.foot-bar { gap: 4px; }
```

> 说明：
> - `isActive('chat')` 保证进入聊天页后「智能助手」按钮高亮。
> - 顺序：**首页 → 智能助手 → 发帖 → 我的**；「发帖」保留居中放大样式（`--center`）。
> - 未登录点击「智能助手」自动跳登录（`/login?redirect=/chat`），登录后回跳聊天页。

### 4.3 个人页入口（辅助入口，可选）

> 底部导航已含「智能助手」按钮且常驻个人页，故本入口为**可选冗余**。若保留，可在 ProfilePage 的 Actions 区加一行：

```html
<div style="display:flex;padding:12px 16px;gap:12px;background:var(--color-surface);">
    <button @click="$router.push('/chat')"
            style="flex:1;border:1px solid var(--color-border);background:var(--color-surface);padding:8px;border-radius:8px;cursor:pointer;font-size:13px;color:var(--color-primary);">
        🤖 智能助手
    </button>
    <button @click="doLogout" ...>退出登录</button>
</div>
```

### 4.4 顶部导航与页脚

| 路由 | app-header | showBack | foot-bar |
|------|-----------|----------|----------|
| `home` | 隐藏（有自定义 search-bar） | — | ✅（含「智能助手」按钮） |
| `chat` | ✅ 标题「智能助手」 | ✅ 返回上一页 | ❌（全屏对话） |

- app.js 现有逻辑：`showBack` 排除 `['home','profile']` → chat 页自动显示返回按钮；`showFooter` 仅 `['home','profile']` → chat 页自动隐藏底部导航，**均无需改**。
- ⚠️ 但 `pageViewClass` 需把 `'chat'` 加入 `--embedded` 列表（chat 页消息区需内部滚动、输入区固定底部，与首页同型）。
- 底部导航由 3 按钮扩为 4 按钮（首页 / 智能助手 / 发帖 / 我的），改动收敛在 `foot-bar.js`。

---

## 5. ChatPage 组件设计（`js/pages/chat.js`）

### 5.1 职责与数据

```js
export default {
    name: 'ChatPage',
    data() {
        return {
            messages: [],        // [{ role: 'user'|'assistant', text, type? }]
            input: '',
            busy: false,         // 流式进行中
            sessionId: sessionStorage.getItem(SESSION_KEY) || null,
            controller: null,    // AbortController，卸载时中断 SSE
        };
    },
    computed: {
        userName() { return Store.user?.nickName || '用户'; },
    },
    async mounted() {
        // 1. 欢迎语
        // 2. 若存在历史会话，可选调用 /api/agent/history/{sessionId} 回放
    },
    beforeUnmount() {
        if (this.controller) this.controller.abort();
    },
    methods: {
        sendMessage() { ... },       // 入口
        appendMessage(role, text) {},
        handleEvent(name, payload) {}, // 分发 SSE 事件
        confirmAction(id, approved) {},
        clearHistory() {},            // 新会话
    },
};
```

### 5.2 发送消息主流程

```
sendMessage()
  ├─ input 非空 & !busy
  ├─ 追加用户气泡；busy=true
  ├─ 创建 assistant 空气泡容器
  ├─ controller = new AbortController()
  ├─ chatClient.send(message, sessionId, {
  │      onThinking, onToolCall, onToolResult,
  │      onConfirm, onChunk, onDone, onError
  │  }, { signal: controller.signal })
  ├─ onDone: 保存 sessionId → sessionStorage；busy=false
  └─ catch: 显示错误气泡；finally: busy=false
```

### 5.3 欢迎语与问候

- 无历史会话：`「您好！我是 CampusDeal 智能助手，可以帮您查询订单、浏览优惠券、查找商户等。」`
- 有历史会话：先回放历史消息，再追加 `「已恢复上次会话，继续聊聊吧。」`

### 5.4 快速提问卡片（可选增强）

在输入框上方展示 3~4 个常用问题快捷入口（点按直接发送）：

| 问题 | 触发工具 |
|------|----------|
| 我最近的订单有哪些？ | queryOrders |
| 有哪些优惠券可以领？ | queryCoupons |
| 帮我找一下附近的奶茶店 | searchMerchant |
| 怎么申请退款？ | 触发确认流程 |

---

## 6. 核心模块设计

### 6.1 SSE 客户端（`js/agent/sse.js`）

抽取自旧 chat.js 的 `consumeSse` / `parseSseBlock`，改成**无 DOM 依赖、可中断**的模块。

```js
/**
 * Agent SSE 客户端（ES Module）
 * 用 fetch + ReadableStream 解析 text/event-stream，
 * 因接口受 LoginInterceptor 保护，不能用 EventSource（无法带 Authorization）。
 */
const AGENT_BASE = '/api/agent';

export async function chat(message, sessionId, handlers, opts = {}) {
    const { signal } = opts;
    const token = localStorage.getItem('campusdeal-token');
    const params = new URLSearchParams({ message });
    if (sessionId) params.set('sessionId', sessionId);

    const resp = await fetch(`${AGENT_BASE}/chat?${params.toString()}`, {
        method: 'POST',
        headers: { Authorization: token },
        signal,
    });
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    await consumeSse(resp.body, handlers);
}

function parseSseBlock(block) { /* 同旧实现：event/data 提取 */ }

async function consumeSse(stream, handlers) {
    const reader = stream.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buffer.indexOf('\n\n')) !== -1) {
            const block = buffer.slice(0, idx).trim();
            buffer = buffer.slice(idx + 2);
            if (!block) continue;
            const evt = parseSseBlock(block);
            handlers[evt.name] && handlers[evt.name](evt.body);
        }
    }
}
```

> **事件 → 处理器映射表**（ChatPage 内实现）：
>
> | SSE 事件 | 处理器 | 页面动作 |
> |----------|--------|----------|
> | `thinking` | onThinking | 显示"💭 正在…"占位 |
> | `tool_call` | onToolCall | 渲染工具调用卡片（黄色高亮） |
> | `tool_result` | onToolResult | 卡片内追加 ✅ 结果 |
> | `confirm` | onConfirm | 弹出确认对话框，await 用户选择 |
> | `chunk` | onChunk | 追加到当前 assistant 气泡，渐进渲染 |
> | `done` | onDone | 保存 sessionId，解锁输入 |
> | `error` | onError | 气泡内显示错误 |

### 6.2 Markdown 渲染（迁入 `Utils.renderMarkdown`）

沿用旧 chat.js 的极简渲染器（离线无 marked.js），并确保**先 escape 再还原白名单**，防 XSS：

```js
renderMarkdown(text) {
    let html = Utils.escapeHtml(text);
    html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) =>
        '<pre><code>' + code.replace(/\n$/, '') + '</code></pre>');
    html = html.replace(/`([^`\n]+)`/g, '<code>$1</code>');
    html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\n/g, '<br>');
    return html;
},
```

### 6.3 操作确认弹窗

复用 `Utils.confirm(message)`（返回 Promise<boolean>），再调 `/api/agent/confirm`：

```js
async confirmAction(confirmationId, message) {
    const approved = await Utils.confirm(message || '请确认是否继续此操作');
    try {
        const resp = await Api.post('/agent/confirm', { confirmationId, approved });
        return resp.data?.result || '已处理';
    } catch (e) {
        return e.message || '确认失败';
    }
}
```

> ⚠️ `Api.post`（axios）响应拦截器会对 `success=false` 的响应 reject（如"确认已过期"），这里 catch 后把错误文案展示在卡片中，而不是抛异常。

### 6.4 会话恢复（可选，推荐实现）

```js
async restoreSession() {
    const sid = this.sessionId;
    if (!sid) return;
    try {
        const resp = await Api.get('/agent/history/' + sid);
        if (resp && resp.data && resp.data.messages) {
            resp.data.messages.forEach(m =>
                this.messages.push({ role: m.role, text: m.content }));
        }
    } catch { /* 会话失效则忽略，走新会话 */ }
}
```

- `MessageRecord` 字段：`role` / `content` / `timestamp`。
- 恢复后保留 `sessionId`，后续对话继续追加到同一 Redis 会话。
- 「新会话」按钮：`clearHistory()` → 清空消息、删除 sessionStorage 会话键、置 `sessionId = null`。

### 6.5 样式适配（`app.css` 追加 `chat-*` 段）

统一用设计系统变量，与主站视觉一致：

```css
/* ── Chat Page ── */
.chat-page { display:flex; flex-direction:column; flex:1; min-height:0; }
.chat-page__messages {
    flex:1; min-height:0; overflow-y:auto; -webkit-overflow-scrolling:touch;
    padding: var(--space-md);
    background: var(--color-bg);
}
.chat-page__row { display:flex; gap:var(--space-sm); margin-bottom:var(--space-md); align-items:flex-start; }
.chat-page__row--user { flex-direction:row-reverse; }
.chat-page__avatar {
    width:36px;height:36px;border-radius:var(--radius-round);
    display:flex;align-items:center;justify-content:center;
    font-size:18px;flex-shrink:0;
    background:var(--color-surface); box-shadow: var(--shadow-card);
}
.chat-page__bubble {
    max-width:72%; padding:10px 14px; border-radius:var(--radius-md);
    line-height:1.6; word-break:break-word; font-size:var(--font-md);
}
.chat-page__row--user .chat-page__bubble {
    background:var(--color-primary); color:#fff; border-top-right-radius:2px;
}
.chat-page__row--assistant .chat-page__bubble {
    background:var(--color-surface); color:var(--color-text); border-top-left-radius:2px;
    box-shadow: var(--shadow-card);
}
.chat-page__tool { background:#fff9c4; border-left:4px solid #ffc107; border-radius:6px; padding:8px 12px; font-size:var(--font-sm); margin:6px 0; }
.chat-page__input-area {
    display:flex; gap:var(--space-sm); padding:var(--space-sm) var(--space-md);
    border-top:1px solid var(--color-border); background:var(--color-surface);
    align-items:flex-end;
}
.chat-page__input { flex:1; border:1px solid var(--color-border); border-radius:var(--radius-md); padding:10px 12px; font-size:var(--font-md); resize:none; outline:none; font-family:inherit; }
.chat-page__input:focus { border-color:var(--color-primary); }
.chat-page__send {
    background:var(--color-primary); color:#fff; border:none;
    border-radius:var(--radius-md); padding:10px 20px; font-size:var(--font-md);
    cursor:pointer; flex-shrink:0;
}
.chat-page__send:disabled { background:var(--color-text-disabled); cursor:not-allowed; }
```

> 复用现有 `.toast`、`.dialog`（Utils）样式即可，无需重复定义确认框。

---

## 7. 鉴权与安全

### 7.1 登录链路

```
未登录用户点击底部导航「智能助手」按钮
   → handleChat() → router.push('/chat')
   → beforeEach 守卫：meta.requiresAuth && !Store.isLoggedIn
   → 跳 /login?redirect=/chat
   → 登录成功后 this.$router.replace('/chat')
```

- 登录成功后 `Store` 已写入 `campusdeal-token`，ChatPage 无需关心 token 来源。
- 会话中途 token 失效（SSE 返回 401）：显示"登录已过期，请重新登录"，点击跳转 `/login?redirect=/chat`。

### 7.2 XSS 防护

- 用户输入 / AI 输出一律经 `Utils.escapeHtml` 转义后再渲染；Markdown 渲染在转义基础上仅还原白名单标签（pre/code/strong/br）。
- 工具调用参数（`tool_call.arguments` 为 JSON 字符串）用 `Utils.escapeHtml` 包裹后展示，杜绝 `innerHTML` 直接注入。

### 7.3 数据防泄露

- 后端已有 `InputSanitizer`（PII 脱敏）与 `OutputVerifier`（输出脱敏/截断），前端不做二次脱敏。
- 前端只在**会话归属**上约束：`sessionId` 由后端与 `userId` 绑定（`SessionManager.getOrCreate` 校验），前端不信任任何来历不明的 sessionId。

---

## 8. 旧页面处置

| 文件 | 处置 | 说明 |
|------|------|------|
| `src/main/resources/static/chat.html` | 废弃，保留备份 | 入口已被 SPA 替代；MvcConfig 中 `/chat.html`、`/css/**`、`/js/**` 放行项可保留（无害）或二期清理 |
| `src/main/resources/static/js/chat.js` | 废弃，保留备份 | 逻辑迁入 `js/agent/sse.js` + `js/pages/chat.js` |
| `src/main/resources/static/css/chat.css` | 废弃，保留备份 | 样式迁入 `app.css` `chat-*` 段 |

> **迁移时保留文件、只做功能下线**：避免破坏 `mvn spring-boot:run` 对 static 目录的既有假设，也便于回滚。

---

## 9. 实施计划（Phase 分解）

| Phase | 内容 | 产出 | 验证点 |
|-------|------|------|--------|
| **P0** | `js/agent/sse.js`：抽取 SSE 客户端 + `Utils.renderMarkdown` | 2 个纯 JS 模块 | 用 `node` 或浏览器控制台单测 `parseSseBlock` |
| **P1** | `js/pages/chat.js`：ChatPage 组件（消息气泡/输入/发送/事件渲染/确认弹窗） | 1 个 Vue 组件 | 直接访问 `#/chat`（登录态）能完成一轮对话 |
| **P2** | 路由注册 + 底部导航第 4 按钮 + 个人页入口 + `app.css` 样式 | router / foot-bar / profile / css | 底部导航 4 按钮跳转、返回链路通 |
| **P3** | 会话恢复（history 回放）+ 新会话按钮 + 快速提问 | ChatPage 增强 | 刷新后会话仍在；可一键开启新会话 |
| **P4** | 联调与收尾：401 兜底、双端 token 兼容、旧页面下线检查 | 全量回归 | 见 §10 测试清单 |

> **双端 token 兼容注意点**：旧 chat.html 曾把 token 写入 `localStorage['campusdeal-token']`。若用户此前登录过智能助手页，迁移后需重新走主站登录（`campusdeal-token`）。P0 可在 Store.init 里做一次兜底迁移：若存在 `campusdeal-token` 且无 `campusdeal-token`，读入并写入新键（可选，需后端校验有效性）。

---

## 10. 测试策略

### 10.1 路由与入口

| 编号 | 场景 | 前置 | 操作 | 预期 |
|------|------|------|------|------|
| E-01 | 底部导航 4 按钮可见 | 登录 | 打开 `/` 或 `/profile` | 显示「首页 / 智能助手 / 发帖 / 我的」4 个按钮 |
| E-02 | 未登录点「智能助手」 | 未登录 | 点该按钮 | 跳 `/login?redirect=/chat` |
| E-03 | 登录后回跳 | 已登录 | 登录成功 | 回到 `/chat` 并显示欢迎语 |
| E-04 | 返回主站 | 已登录 | 点 header 返回 | 回到首页，滚动位置保留 |
| E-05 | 个人页入口 | 已登录 | 打开 `/profile` | 显示"智能助手"行，点击可进入 |

### 10.2 对话主流程

| 编号 | 场景 | 输入 | 预期 |
|------|------|------|------|
| M-01 | 普通问答 | "你好" | assistant 气泡渐进输出，done 后解锁输入 |
| M-02 | 工具调用 | "查一下我的订单" | 显示 tool_call 卡片 → ✅ 结果 → 最终回答 |
| M-03 | 流式中断 | 发送后切走路由 | SSE 被 abort，无报错、无内存泄漏 |
| M-04 | 敏感操作 | "帮我退第一单" | 弹出确认框，确认 → 调用 `/api/agent/confirm` → 展示结果 |
| M-05 | 敏感操作取消 | 同上 | 弹窗取消 → 展示"操作已取消" |
| M-06 | 空输入 | 空格/空 | 不发送，无报错 |
| M-07 | 连续发送防护 | 流式进行中再点发送 | 发送按钮 disabled，无重复请求 |
| M-08 | 会话恢复 | 完成一轮对话后刷新 | 历史消息回放，sessionId 不变 |
| M-09 | 新会话 | 有历史 | 点"新会话" → 清空消息，sessionId 重置 |

### 10.3 鉴权与安全

| 编号 | 场景 | 预期 |
|------|------|------|
| A-01 | token 失效后发送 | 收到 401 → 提示重新登录，跳转 login |
| A-02 | 未登录直达 `#/chat` | 路由守卫重定向 login |
| A-03 | AI 输出含 `<script>` | 作为文本展示，不执行（escapeHtml 校验） |
| A-04 | 工具参数含 HTML | 卡片中安全展示（escapeHtml） |

### 10.4 回归

| 编号 | 场景 | 预期 |
|------|------|------|
| R-01 | 首页帖子滚动/无限加载 | 不回归（底部导航为独立 flex 项，不影响 scroll 容器） |
| R-02 | 商家列表页 | 不回归 |
| R-03 | 旧 `8081/chat.html` | 仍可打开（保留），但不作为新入口 |

---

## 11. 附录

### 11.1 涉及文件清单（实现时新增/修改）

| 操作 | 文件 |
|------|------|
| ✚ 新增 | `nginx-1.18.0/html/campusdeal/js/agent/sse.js` |
| ✚ 新增 | `nginx-1.18.0/html/campusdeal/js/pages/chat.js` |
| ✚ 修改 | `nginx-1.18.0/html/campusdeal/js/router.js`（加 `/chat` 路由） |
| ✚ 修改 | `nginx-1.18.0/html/campusdeal/js/components/foot-bar.js`（新增第 4 个按钮「智能助手」+ handleChat） |
| ✚ 修改 | `nginx-1.18.0/html/campusdeal/js/pages/profile.js`（加入口行，可选） |
| ✚ 修改 | `nginx-1.18.0/html/campusdeal/js/app.js`（pageViewClass 加入 `'chat'` 到 embedded 列表） |
| ✚ 修改 | `nginx-1.18.0/html/campusdeal/js/utils.js`（加 renderMarkdown） |
| ✚ 修改 | `nginx-1.18.0/html/campusdeal/css/app.css`（加 chat-* 样式与 foot-bar 间隙微调） |
| ♻️ 可选 | `nginx-1.18.0/html/campusdeal/js/store.js`（campusdeal-token → campusdeal-token 兜底迁移） |
| 🗑️ 废弃保留 | `src/main/resources/static/{chat.html,js/chat.js,css/chat.css}` |

### 11.2 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| 流式 fetch 在切换路由时未中断 | 组件卸载后仍写入 DOM 报错 | `beforeUnmount` + `AbortController` |
| axios 对 `success=false` reject，导致确认结果丢失 | 用户看到"网络异常"而非"已取消" | confirm 请求单独 catch，把错误文案作为结果展示 |
| 旧 `campusdeal-token` 无法迁移 | 老用户需重新登录 | P0 可选兜底迁移 + 登录页说明 |
| SSE 断流（后端超时） | 一直转圈 | 前端加超时兜底（如 60s 无 chunk 则提示重试） |

### 11.3 后续可扩展点

- 会话历史侧栏（左侧最近会话列表）
- 消息失败重发按钮
- 人工客服转接（handoff）入口，配合后端 human-handoff 能力
