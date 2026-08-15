# 02 · 前后端联调测试 + E2E

> 覆盖 nginx 部署架构、前端页面 × 后端接口对应关系、Agent SSE 联调协议、E2E 场景 S1–S5、
> 前端页面三态验证与 CDP 脚本化回归。

## 1. 部署架构与联调关键点

```
浏览器 → http://localhost:8080 (nginx 静态 SPA: index.html + js/pages/*.js)
              │  /api/* 反代（去前缀）
              └→ http://localhost:8081 (后端)
```

### 前端静态资源结构（nginx html/campusdeal）

```
index.html
css/app.css
js/
├── api.js            # axios 封装（/api 前缀、token 注入、401 广播）
├── app.js            # 入口 + pageViewClass 登记
├── router.js         # Vue Router hash 模式 + requiresAuth 守卫
├── store.js          # 登录态 Store（唯一判登录入口）
├── utils.js          # 工具（safeHtml 等）
├── agent/sse.js      # fetch + ReadableStream 消费 /agent/chat SSE
├── components/       # app-header/foot-bar/post-card/merchant-card/coupon-card/rate-star/status-views
├── pages/            # home/shop-list/shop-detail/post-detail/post-edit/profile/user-profile/login/chat/flash/coupons/nearby/search
└── vendor/           # vue/vue-router/axios（CDN 本地化）
```

### 联调关键点

| 关键点 | 说明 | 测试关注 |
|---|---|---|
| API 前缀 | 前端一律 `/api/xxx`，nginx 反代剥前缀 | 所有页面请求无 404 |
| Token 存储 | `localStorage['campusdeal-token']`（主站与 chat 统一） | 登录后刷新不掉登录态 |
| Token 注入 | `api.js` 拦截器写 `authorization` 头；401 广播 `campusdeal:unauthorized` | 401 后自动跳登录并清态 |
| SSE 流式 | `/api/agent/chat` 用 fetch+ReadableStream（EventSource 不能带 Authorization）；**nginx 须关闭缓冲**（`proxy_buffering off`） | 逐字输出、断流处理 |
| 大数精度 | 订单 ID 以字符串下发（P2-6） | 前端展示/比较不丢尾位 |
| 静态公开 | `/chat.html`、`/css/**`、`/js/**` 在公开路径 | 未登录可打开登录页 |

## 2. 页面 × 接口矩阵

| 路由/页面 | 依赖接口 | 鉴权 |
|---|---|---|
| `/` home | `GET /merchant-type/list`、`GET /post/hot`、`GET /post/{id}`、`PUT /post/like/{id}` | 公开 |
| `/shops/:typeId` shop-list | `GET /merchant/of/type?typeId&current&x&y` | 公开 |
| `/shop/:id` shop-detail | `GET /merchant/{id}`、`GET /coupon/list/{shopId}`、`POST /coupon-order/seckill/{id}` | 浏览公开/秒杀需登录 |
| `/post/:id` post-detail | `GET /post/{id}`、`GET /post/likes/{id}`、`PUT /post/like/{id}`、`GET /follow/or/not/{id}`、`PUT /follow/{id}/{isFollow}`、`GET /user/me` | 浏览公开/交互需登录 |
| `/post-edit` | `GET /merchant/of/name`、`POST /post`、`POST /upload/post`、`POST /upload/delete` | 需登录 |
| `/profile` | `GET /user/me`、`GET /post/of/me`、`GET /post/of/follow?lastId&offset`、`POST /user/sign`、`GET /user/sign/count` | 需登录 |
| `/user/:id` user-profile | `GET /user/{id}`、`GET /post/of/user?current&id` | 公开 |
| `/login` | `POST /user/code?phone=`、`POST /user/login` | 公开 |
| `/chat`（智能助手） | `POST /agent/chat`(SSE)、`GET /agent/history/{sessionId}`、`POST /agent/confirm` | 需登录 |
| `/flash` | `GET /coupon/flash/list` | 公开 |
| `/shops/nearby` | `GET /merchant/nearby?x&y&current` | 公开 |
| `/coupons` | `GET /coupon/list/all` | 公开 |
| `/search` | `GET /merchant/of/name?name`、分类直达、Agent 兜底 `/chat?q=` | 公开 |

## 3. SSE 事件流协议（Agent 联调）

`POST /api/agent/chat?message=...&sessionId=...`（需 `authorization` 头）
响应 `Content-Type: text/event-stream`，SseEmitter 超时 300s。

### 3.1 事件定义

| 事件名 | data 结构 | 前端消费 |
|---|---|---|
| `thinking` | `{"type":"thinking","content":"正在理解您的问题：...","timestamp":<epochMs>}` | 显示"思考中" |
| `tool_call` | AgentEvent，`content` 为 **ToolCall JSON 字符串** `{id,name,arguments}` | 二次 parse 渲染工具卡片 |
| `tool_result` | AgentEvent，`content` 文本 `"<name> 执行完成，耗时 Xms"` / `"被拒绝：<msg>"` | 工具结果卡片 |
| `confirm` | AgentEvent，`content` 为 **GuardDecision JSON 字符串** `{action,message,confirmationId,timeoutSeconds}` | 弹确认框 → `/agent/confirm` |
| `chunk` | **裸 token 字符串** | 逐字追加气泡 |
| `done` | **裸 AgentResponse JSON** `{sessionId,answer,totalTokens,toolCallCount,elapsedTimeMs}` | 结束并更新 sessionId |
| `error` | ①限流=裸字符串"请求过于频繁，请稍后再试。"；②清洗/异常=AgentEvent | 展示错误并结束 |

> ⚠️ **协议不一致是已知点**：`chunk`/`done`/限流`error` 是裸数据；`thinking/tool_call/tool_result/confirm/清洗或异常 error` 是 `AgentEvent` 包裹，且 `tool_call`/`confirm` 的 content 是二次编码 JSON。联调断言须按事件分别处理。

### 3.2 事件流顺序

```
正常：    thinking → chunk* → done
工具：    thinking → tool_call → tool_result → chunk* → done
敏感操作： thinking → tool_call → confirm →(用户确认)→ tool_result → chunk* → done
拒绝/异常：thinking → error
限流：    error（无 thinking）
```

### 3.3 SSE 联调用例

| ID | 场景 | 步骤 | 预期 |
|---|---|---|---|
| SSE-01 | 正常问答 | 登录后 POST chat | 200+SSE；thinking→chunk→done |
| SSE-02 | 工具调用 | "查一下我的订单" | 出现 tool_call→tool_result，内容为本人订单 |
| SSE-03 | 敏感确认 | "我要退款" | 出现 confirm 事件；确认弹窗 |
| SSE-04 | 确认批准 | 拿 confirmationId 调 `/agent/confirm` {approved:true} | 工具执行，GuardResult.executed=true |
| SSE-05 | 确认拒绝 | approved:false | 不执行，fail |
| SSE-06 | 流式中断 | 对话中切路由（AbortController） | 后端不挂起，前端无残留 |
| SSE-07 | 断流超时 | 后端 5min 无响应 | SseEmitter 超时关闭，前端兜底提示 |
| SSE-08 | 未登录 | 无 token | 401 |
| SSE-09 | 会话恢复 | 传入旧 sessionId | 上下文延续（或压缩摘要注入） |
| SSE-10 | 无 Key 降级 | DeepSeek Key 为空 | thinking→error 收尾，不挂起 |

## 4. E2E 场景 S1–S5

### S1 登录 → 签到 → 角标

```
登录(13800001111) → POST /user/sign → GET /user/sign/count → 页面签到角标
```

| 断言 | 期望 |
|---|---|
| 登录返回 token | success=true |
| 签到幂等 | 重复签到成功，连续天数不变 |
| 角标 | 显示连续签到天数，与接口一致 |

### S2 秒杀 → 落库 → 幂等

```
登录 → POST /coupon-order/seckill/11 → 查 tb_voucher_order → 再次秒杀
```

| 断言 | 期望 |
|---|---|
| 首次秒杀 | success=true，data=订单字符串 |
| 落库 | `tb_voucher_order` 新增该用户该券订单 |
| Redis 库存 | `flashdeal:stock:11` 减 1 |
| 重复秒杀 | fail"Already purchased" |
| 响应时间 | < 100ms（Kafka 未运行不阻塞） |

### S3 缓存一致性

```
GET /merchant/1（写缓存） → PUT /merchant（改数据） → GET /merchant/1
```

| 断言 | 期望 |
|---|---|
| 更新后缓存失效 | `cache:merchant:1` 被删除 |
| 二次查询 | 返回新数据（重新走 DB 重建缓存） |

### S4 Agent SSE

| 断言 | 期望 |
|---|---|
| 无 token | 401 |
| 有 token | 200 + text/event-stream |
| 事件流 | 以 done 或 error 收尾，不挂起 |
| 历史回读 | `GET /agent/history/{sessionId}` 返回消息 |

### S5 鉴权/越权

| 断言 | 期望 |
|---|---|
| 未登录受保护接口 | 401 |
| P0-4 `/merchant` 写接口 | 匿名 401 |
| **P0-5 越权泄漏** | 40 次并发匿名请求全部 401（详见 11 文档） |
| 他人订单 | 工具/接口不返回他人数据 |

## 5. 前端页面三态验证

每个页面验证 `loading → ready / empty / error` 三态，且 `JS_ERRORS=0`。

| ID | 用例 | 期望 |
|---|---|---|
| FT-01 | 页面正常渲染 | 数据真实、无 JS 报错 |
| FT-02 | 未登录访问受保护路由 | 跳 `/login`，登录后回跳 |
| FT-03 | 空数据 | EmptyView 非白屏 |
| FT-04 | 接口失败/断网 | ErrorView + 重试 |
| FT-05 | 刷新状态保持 | 登录态/会话保持 |
| FT-06 | 订单号完整 | `"76487002400227347"` 原样显示 |
| FT-07 | 401 广播清态 | 跳登录、清 token/user |
| FT-08 | XSS 富文本 | `<script>`/`onerror` 被 `safeHtml()` 过滤 |
| FT-09 | 金刚区入口 | 抢购/附近/卡券/动态/签到可达 |
| FT-10 | 搜索 | 商户名/分类直达/兜底 Agent 三态命中 |
| FT-11 | 校区切换 | localStorage.campus 持久化，坐标联动 |

## 6. CDP 脚本化回归

| 脚本 | 命令 | 断言 |
|---|---|---|
| `node tools/cdp-pages.js` | 13+ 页面 | CDP PASS + JS_ERRORS=0 |
| `node tools/cdp-verify-doc09.js` | 金刚区/搜索/签到三态 | 三态齐全 + 入口可达 |

### CDP 使用原则

- 断言 + JS 报错捕获双条件（页面存在 ≠ 无报错）。
- 登录态：`Runtime.evaluate` 写 `localStorage['campusdeal-token']` 后刷新。
- 每次独立 `--user-data-dir`，避免串态。
- **SSE 场景不能** `--dump-dom --virtual-time-budget`，须 CDP 实时等待事件帧。

## 7. 联调环境检查清单

- [ ] nginx 反代：`curl http://localhost:8080/api/merchant-type/list` 200
- [ ] 静态页：`curl http://localhost:8080/` 200
- [ ] nginx `proxy_buffering off`（SSE 需要）
- [ ] `/api/agent/**` 正确反代到 8081
- [ ] 后端日志 INFO 级别，无 P0 ERROR
