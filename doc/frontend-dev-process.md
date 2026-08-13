# CampusDeal 前端开发流程（Frontend Development Process）

> 定位：本文是 **CampusDeal 主站前端（SPA）从需求到发布的全流程操作规程**，回答"一个前端功能/页面/组件应该怎么落地"。
> 适用对象：所有参与前端开发的开发者（含 AI 协作会话）。
> 配套文档：[`CLAUDE.md`](../CLAUDE.md)（技术栈/环境速查）、[`doc/design/`](design/)（各功能设计文档 01–09）、[`doc/implementation-plan.md`](implementation-plan.md)（总实施计划）。

---

## 1. 架构总览

### 1.1 技术形态：**无构建 SPA**

前端**没有** `npm` / `vite` / `webpack` 构建链，而是：

- **Vue 3.4 + Vue Router** 以 ES Module 方式引入（`js/vendor/vue.global.prod.js` / `vue-router.global.prod.js`，本地 vendored，非 CDN 外链）。
- 页面、组件均为 **原生 JS ES Module 文件**，`<script type="module">` 在 `index.html` 加载入口 `js/app.js`。
- **浏览器源码即产物**：改动任何 `.js/.css/.html` 文件 → 刷新浏览器即生效（无需编译打包）。

这意味着：**开发 = 改静态文件 + 刷新验证**，但也意味着 **没有 lint / type-check / 单测兜底**，必须依赖"约定 + 人工/脚本化验证"保证质量。

### 1.2 请求链路

```
浏览器 (用户)
   │
   ▼
nginx:8080  ── html/campusdeal/  静态资源（index.html / js/ / css/ / imgs/）
   │
   ├─ location /imgs/   → html/campusdeal/imgs/   （图片直出）
   │
   └─ location /api     → 反代 rewrite /api(/.*) $1 break
        │
        ▼
   upstream 127.0.0.1:8081 / 8082  （Spring Boot，轮询负载）
```

- 前端所有接口统一 **`/api` 前缀**（见 `js/api.js` 的 `BASE_URL = '/api'`）。
- **认证**：请求头 `Authorization: <token>`（token 键 `campusdeal-token`），由 `api.js` 请求拦截器自动注入；401 时自动清除 token。
- **响应约定**：后端统一 `{ success, errorMsg, data, total }`；`api.js` 响应拦截器在 `success=false` 时 reject。

### 1.3 前端目录拓扑（`nginx-1.18.0/html/campusdeal/`）

```
html/campusdeal/
├── index.html                 # SPA 唯一入口（加载 app.js）
├── css/app.css                # 设计系统（CSS 变量 :root）+ 全部页面样式
├── js/
│   ├── app.js                 # 入口：Store.init → createApp → 注册路由/全局/组件
│   ├── router.js              # hash 路由表 + 路由守卫
│   ├── store.js               # 全局状态（登录态/用户/商户分类/工具方法）
│   ├── api.js                 # axios 封装（baseURL/token/错误处理）
│   ├── utils.js               # Utils（toast/时间/坐标…）
│   ├── agent/sse.js           # Agent SSE 流式客户端（fetch + ReadableStream）
│   ├── vendor/                # 本地化的 Vue / Vue Router / axios
│   ├── components/            # 公共组件（app-header/foot-bar/merchant-card/post-card/…）
│   └── pages/                 # 页面组件（home/shop-list/shop-detail/chat/…）
├── _old/                      # 旧版 MPA 归档（引用淘汰）
└── archive/                   # 更早版本归档（引用淘汰）
```

> `html/campusdeal/` 本身是 git 仓库（`.git`），前端版本由此管理。**这是唯一的前端代码仓库**，后端 Java 代码在 `campusdeal/` 仓库中——两者分开。

---

## 2. 开发环境准备

### 2.1 前置依赖

| 依赖 | 要求 | 备注 |
|------|------|------|
| JDK | **17**（`D:/program/develop/jdks/jdk17`） | 系统默认 JDK 25 会因 Lombok 不支持而编译失败，**必须切 JDK17** |
| MySQL | 8.0.33，`localhost:3306/campusdeal`，root/123456 | 需导入/执行 schema |
| Redis | `localhost:6379`，密码 `123456` | 登录态/缓存/秒杀依赖 |
| nginx | `D:/program/develop/redis_project/nginx-1.18.0` | 前端静态 + `/api` 反代 |
| Node.js | 24+（内置 WebSocket） | 仅用于 CDP 自动化验证脚本 |

### 2.2 启动顺序

```bash
# 1) 启动 MySQL / Redis（各自服务或容器）

# 2) 启动后端（工作目录 campusdeal，注意 JDK17）
export JAVA_HOME="D:/program/develop/jdks/jdk17"      # PowerShell: $env:JAVA_HOME="D:\program\develop\jdks\jdk17"
mvn spring-boot:run                                    # 监听 8081

# 3) 启动 nginx（工作目录 nginx-1.18.0）
./nginx.exe                                           # 监听 8080，静态 + /api 反代

# 4) 访问 http://localhost:8080/ 即可
```

### 2.3 后端接口自测（改前端前先确认接口可用）

```bash
# 公开接口，直接 curl
curl "http://localhost:8080/api/merchant-type/list"

# 登录态接口，先登录取 token
TOKEN=$(curl -s -X POST "http://localhost:8080/api/user/login" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","code":"123456"}' | ...提取 token)
curl "http://localhost:8080/api/user/me" -H "Authorization: $TOKEN"
```

> 中文参数必须 **URL 编码**（`curl` 裸传中文会 400）：`?name=%E5%A5%B6%E8%8C%B6`。

---

## 3. 代码组织与命名规范

### 3.1 分层职责

| 层 | 位置 | 职责 | 禁止 |
|----|------|------|------|
| 页面 | `js/pages/*.js` | 路由组件：模板 + 页面级数据/方法 | 直接写原始 `fetch`；直接操作 DOM 外的全局副作用 |
| 组件 | `js/components/*.js` | 可复用 UI 块，props 入 / 事件出 | 依赖具体页面状态 |
| API | `js/api.js`（统一） | 所有后端请求封装 | 在页面/组件里 new axios |
| 状态 | `js/store.js` | 登录态、用户、缓存（商户分类）、校区 | 存"页面瞬时状态" |
| 工具 | `js/utils.js` | 无状态纯函数 | 有副作用的业务逻辑 |
| 样式 | `css/app.css` | 全局设计系统 + 所有 BEM 类 | 内联写死色值（用 CSS 变量） |

### 3.2 命名约定

| 项 | 约定 | 示例 |
|----|------|------|
| 文件 | 小写 kebab-case | `post-card.js`, `shop-detail.js` |
| JS 标识符 | camelCase | `fetchUser`, `isLiked` |
| CSS 类 | **BEM**：`block__element--modifier` | `.type-grid__icon--active` |
| 路由 name | 小写 kebab-case | `'shop-detail'` |
| 页面组件 name | PascalCase + Page 后缀 | `ShopDetailPage` |
| 事件 | 统一 `@click`/`@change`，自定义用 emit `update:xxx` | `@like`, `@retry` |

### 3.3 关键基础设施约定

- **路由**（`js/router.js`）：hash 模式；需要登录的页面加 `requiresAuth: true`，路由守卫统一处理 → 跳 `/login?redirect=原地址`，登录后回跳。
- **页面滚动容器**：首页/商家列表/智能助手自带内部滚动，须在外层 `App` 模板的 `pageViewClass` 里登记（`home` / `shop-list` / `chat` → `page-view--embedded`），否则出现嵌套滚动冲突。
- **全局变量访问**：模板里用 `Utils.xxx` / `Store.xxx` / `Api.xxx`，它们注册在 `app.config.globalProperties`。
- **登录态判断**：一律用 `Store.isLoggedIn` / `Store.user`，**不要**自己读 localStorage。
- **接口调用**：一律 `Api.get/post/put/delete(url, params/data)`，返回 `body`（已解包 `Result`），调用方取 `.data`。
- **图片地址**：资源放 `imgs/`，模板里 `'/imgs/xxx.png'` 或后端返回的相对路径。

---

## 4. 功能开发标准流程（Feature Workflow）

> 核心原则：**先文档、后编码；先接口、后页面；先自测、后发布。**

```
┌────────┐ ┌────────────┐ ┌────────┐ ┌──────────┐ ┌──────────┐
│ 需求澄清 │→│ 设计文档     │→│ 任务拆分 │→│ 接口就绪   │→│ 前端实现   │
└────────┘ └────────────┘ └────────┘ └──────────┘ └──────────┘
                                              │
┌────────┐ ┌──────────┐ ┌──────────┐ ┌────────▼─┐
│ 发布    │←│ 归档备份  │←│ 联调验证  │←│ 本地自测  │
└────────┘ └──────────┘ └──────────┘ └──────────┘
```

### 步骤 0：需求澄清

- 用 `AskUserQuestion` 澄清歧义（例：本次顶部重设计，先问清"两排按钮"指哪两排）。
- 确认范围、入口位置、登录要求、目标用户。

### 步骤 1：设计文档（强制）

- 每个**跨页面/多文件/有交互设计**的功能，先在 `doc/design/NN-*.md` 写设计文档（沿用 01–09 编号递增，如 `10-…`）。
- 文档必含：现状与问题 → 目标 → 布局/交互 → **接口契约** → 涉及文件 → 实施计划 → 测试用例 → 风险。
- 与后端相关的接口先列契约，标注"新增 / 复用 / 降级方案"。
- 设计文档写完 → 用户确认后再动手编码（**不要跳过确认直接写代码**）。

### 步骤 2：任务拆分

- 用 `TaskCreate` 把功能拆成可执行的子任务（阶段 A/B/C…）。
- 每个任务有明确产出文件；复杂功能按"先可跑通、再补齐细节"排序。

### 步骤 3：接口就绪检查

- 复用接口：`curl` 冒烟（见 §2.3），确认返回结构与设计文档一致。
- 新增接口：先与后端约定（或按本文档 §8 的"前后端并行"策略），**前端可用 mock 先行**，联调时切换真实接口。

### 步骤 4：前端实现

按依赖顺序落地（每个文件遵循 §3 规范）：

1. **数据/状态层**：`store.js`（新缓存/校区/用户态）、`utils.js`（纯函数）。
2. **路由**：`router.js` 登记新路由（name/title/requiresAuth）。
3. **组件/页面**：`components/` → `pages/`，模板里用全局 `Utils/Store/Api`。
4. **样式**：`css/app.css` 追加 BEM 类，复用 `:root` 变量，不引入新色值。
5. **入口登记**：若页面是"嵌入式滚动容器"，同步改 `app.js` 的 `pageViewClass`。

### 步骤 5：本地自测

- 静态资源改动 → 刷新浏览器即生效（注意浏览器缓存，必要时强刷/清缓存）。
- 用 §5 的 **CDP 无头验证脚本** 跑自动化断言 + **手动验证清单**。
- 重点验证：登录/未登录两态、空态/错误态、接口错误 toast、路由守卫回跳。

### 步骤 6：联调验证

- 与后端真实环境联调（新接口、SSE、秒杀、签到等）。
- 验证 `Authorization` 传递、401 处理、`/api` 反代路径、nginx 缓存。

### 步骤 7：归档备份

- 被替换的旧文件移入 `_old/`（保留，供回滚），**不直接删除**。
- 涉及旧版本改动在 `archive/` 找历史。

### 步骤 8：发布

按 §6 发布流程执行：确认清单 → 备份 → 生效 → 冒烟 → 记录。

---

## 5. 验证与测试流程

### 5.1 接口冒烟（curl）

覆盖每个新接口的最小链路：参数合法 / 参数缺失 / 未登录 / 登录成功，共 4 条。

```bash
# 未登录应 401
curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/user/sign/count"

# 已登录正常（需先取 token）
curl -s "http://localhost:8080/api/user/sign/count" -H "Authorization: $TOKEN"
```

### 5.2 无头浏览器自动化验证（CDP + Node，推荐）

前端无构建链，无法单测；**用 CDP 驱动真实 Chrome** 做端到端断言。项目内固定放一个验证脚本模板（建议 `tools/cdp-verify.mjs`）：

```js
// tools/cdp-verify.mjs — 用法：node tools/cdp-verify.mjs "<url>" "<断言:全局JS表达式>"
const url = process.argv[2];
const assertExpr = process.argv[3] || 'document.querySelector(".search-bar") !== null';
const chrome = 'C:/Program Files/Google/Chrome/Application/chrome.exe'; // 按实际路径改

const { spawn } = await import('node:child_process');
const proc = spawn(chrome, [
  '--headless=new', '--disable-gpu', '--remote-debugging-port=9222',
  '--user-data-dir=./.chrome-verify', url,
], { stdio: 'ignore' });

const sleep = ms => new Promise(r => setTimeout(r, ms));
for (let i = 0; i < 20; i++) {                       // 等 DevTools 端口就绪
  try { await fetch('http://127.0.0.1:9222/json'); break; } catch { await sleep(500); }
}

const list = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = list.find(t => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
const send = (id, method, params = {}) =>
  ws.send(JSON.stringify({ id, method, params }));

await new Promise(res => { ws.onopen = res; });
let id = 0, jsErrors = [];
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.method === 'Runtime.exceptionThrown') jsErrors.push(m.params.exceptionDetails.text);
  if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') jsErrors.push(m.params.entry.text);
};
send(++id, 'Runtime.enable'); send(++id, 'Log.enable');
await sleep(2500);                                    // 等渲染/请求

const r = await new Promise(res => {
  send(++id, 'Runtime.evaluate', { expression: assertExpr, returnByValue: true });
  ws.onmessage = ev => {
    const m = JSON.parse(ev.data);
    if (m.id === id) res(m.result.result.value);
  };
});
console.log('ASSERT:', r ? 'PASS' : 'FAIL', '| JS_ERRORS:', jsErrors.length ? jsErrors : 'none');
proc.kill();
process.exit(r && !jsErrors.length ? 0 : 1);
```

使用示例：

```bash
node tools/cdp-verify.mjs "http://localhost:8080/" "document.querySelectorAll('.type-grid__icon').length === 5 && document.querySelector('.search-bar input').disabled === false"
```

> 脚本原则：
> 1. 断言 + **JS 报错捕获**双条件（无构建，运行时错误是最主要回归源）。
> 2. 登录态场景：先用 `Runtime.evaluate` 写入 `localStorage['campusdeal-token']` 再刷新页面。
> 3. 每次用独立 `--user-data-dir`，避免状态串扰。
> 4. **流式场景（SSE）不能用 `--dump-dom --virtual-time-budget`**（对持续流不可靠），必须 CDP 实时等待断言。

### 5.3 手动验证清单（模板）

每个功能发布前至少过一遍：

| 类别 | 检查项 |
|------|--------|
| 双态 | 未登录访问 → 跳登录并回跳；登录后功能可用 |
| 空态 | 列表为空 → EmptyView；接口失败 → ErrorView + 重试 |
| 交互 | 加载中 LoadingView；点击无响应排查（事件绑定/路由） |
| 样式 | 复用 CSS 变量；断点/窄屏不溢出；iOS 安全区 `--safe-bottom` |
| 缓存 | 刷新后状态正确；localStorage 读写正确 |

### 5.4 SSE 专项验证（Agent 对话）

- **后端可用**：`curl` 直接打 `/api/agent/chat` 应**渐进返回**（加 `-N` 不缓冲）。
- **前端链路**：CDP 打开 `/chat` → 发送消息 → 断言"思考中 → 输出块逐个出现 → done 事件设置 sessionId"。
- **已知坑**：SSE 事件名与 `handlers` 键名约定（`onXxx`）必须一致（`js/agent/sse.js` 的 `resolveHandler` 已兼容）；nginx 反代下 SSE 需确认不被缓冲（本环境已验证 `curl -N` 渐进正常）。

### 5.5 兼容性注意

- `color-mix()` 等较新 CSS 需目标浏览器支持，否则给回退值。
- 无构建环境无 sourcemap：调试靠 `console.log` + CDP `Runtime.evaluate`。
- 浏览器缓存：改动 JS/CSS 后强刷（`Ctrl+F5`）或确认 nginx `expires` 配置。

---

## 6. 发布流程

### 6.1 发布前检查清单

- [ ] 设计文档已存在并反映最终实现
- [ ] CDP 断言 PASS、JS_ERRORS = 0
- [ ] 手动清单过完（双态/空态/交互）
- [ ] 新接口冒烟通过（含 401）
- [ ] 旧文件已移入 `_old/`（可回滚）
- [ ] `html/campusdeal` git 有提交点（便于回退）

### 6.2 备份策略

- **文件级**：被替换文件移动（`move` 而非删除）到 `_old/<功能名>/`，保留原相对路径。
- **版本级**：`html/campusdeal` 是 git 仓库，改动前 `git add -A && git commit`（先于改动，便于 diff 与回退）。

### 6.3 发布执行（静态资源）

```bash
# 前端：文件就位即生效，无需重启 nginx
# 确认 nginx 无语法问题（改动 conf 时）
cd /d/program/develop/redis_project/nginx-1.18.0
./nginx.exe -t          # 语法检查
# 若有 conf 改动：./nginx.exe -s reload
```

```bash
# 后端：Java 改动才需要重新构建（工作目录 campusdeal）
export JAVA_HOME="D:/program/develop/jdks/jdk17"
mvn package -DskipTests
# 停止旧进程 → 启动新 jar（或 mvn spring-boot:run）
```

### 6.4 回滚

- **前端**：`git checkout -- <文件>` 或从 `_old/` 恢复；刷新即回退。
- **后端**：重启为上一个 jar / 上次 git commit。
- **数据**：涉及 `SQL 变更`（如重灌 `tb_shop_type`）前必须导出备份；回滚执行反向 SQL。

---

## 7. 速查（Checklist）

### 新增一个页面

1. `pages/xxx.js` 写 Vue 组件（PascalCase + Page 名）。
2. `router.js` 加路由（hash、`name`、`title`、必要时 `requiresAuth`）。
3. 若是嵌入式滚动页，`app.js` 的 `pageViewClass` 加白名单。
4. `css/app.css` 加 BEM 样式。
5. CDP 验证 + 手动清单。

### 新增一个接口调用

1. 确认后端已就绪（curl 冒烟）。
2. `Api.get(url, params)` / `Api.post(url, data)`，取 `.data`。
3. 处理 `error`（`Utils.toast(msg, 'error')`）。

### 新增一个公共组件

1. `components/xxx.js`，props 定义齐全，事件 emit。
2. 需要时在 `app.js` 全局注册（懒加载 `import()`）。

### 样式规范

- 一律 CSS 变量（`:root`），不写死色值/圆角/间距。
- 类名 BEM；组件根类 = 组件名。

### 登录态相关

- 读：`Store.isLoggedIn` / `Store.user`。
- 接口：`Api` 自动带 token；401 自动清除。
- 页面：路由 `requiresAuth`；按钮级登录拦截在组件内判断后 `this.$router.push('/login?redirect=...')`。

---

## 8. 前后端并行与接口契约

- 前端不依赖后端实现细节，**只依赖接口契约**（URL / 方法 / 参数 / 返回结构）。
- 新接口设计按 `doc/design` 内约定：统一 `Result` 包装、RESTful、参数与 Redis key 见 `RedisConstants.java`。
- 后端未就绪时：前端用 mock 数据（`Api` 层临时拦截返回固定 `{ success:true, data:[...] }`），联调时切换。

---

## 9. 与既有文档的关系

| 文档 | 作用 |
|------|------|
| `CLAUDE.md` | 技术栈 / 环境 / 构建 / 约定速查（最高优先级） |
| `doc/final-plan.md` | 全局业务规划与模块地图 |
| `doc/implementation-plan.md` | 分阶段实施总计划 |
| `doc/design/01–09` | 各功能模块设计文档（编号递增） |
| 本文档 | **前端开发流程本身**（环境 → 规范 → 开发 → 验证 → 发布） |
