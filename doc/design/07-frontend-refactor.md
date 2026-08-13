# 设计文档 07：主站前端重构

> 模块定位：对主站前端进行全面重构——从零散的 Vue 2 + Element UI CDN 架构迁移到现代化单页应用（SPA）
> 依赖关系：不依赖后端改动，复用现有 REST API（路径已在模块 12 中适配为 `/merchant`、`/post`、`/coupon` 等）

---

## 1. 现状分析与问题诊断

### 1.1 现有架构概览

```
nginx-1.18.0/html/campusdeal/
├── index.html              # 首页：商家分类 + 热门帖子瀑布流
├── shop-list.html          # 商家列表：GEO 搜索 + 分类筛选
├── shop-detail.html        # 商家详情：店铺信息 + 优惠券 + 秒杀
├── blog-detail.html        # 帖子详情：图文 + 点赞 + 关注 + 图片轮播
├── blog-edit.html          # 发帖编辑器：图片上传 + 富文本
├── info.html               # 个人主页：我的帖子 + 关注流
├── other-info.html         # 他人主页
├── login.html              # 登录页（旧版，无手机验证码流程）
├── css/
│   ├── element.css         # Element UI v2 样式（CDN 本地化）
│   ├── main.css            # 公共样式（footer、定制 Element UI 变量）
│   ├── index.css           # 首页样式
│   ├── shop-list.css       # 商家列表样式
│   ├── shop-detail.css     # 商家详情样式
│   ├── blog-detail.css     # 帖子详情样式
│   └── blog-edit.css       # 发帖页样式
└── js/
    ├── vue.js              # Vue 2.6.14 (CDN 本地化)
    ├── axios.min.js        # Axios (CDN 本地化)
    ├── element.js          # Element UI v2 (CDN 本地化)
    ├── common.js           # 公共逻辑：axios 拦截器 + util 工具函数
    └── footer.js           # 底部导航栏组件
```

### 1.2 核心问题清单

| 编号 | 类别 | 问题 | 严重度 |
|------|------|------|--------|
| P01 | 架构 | **多页应用（MPA）**：每个页面独立 HTML + Vue 实例，页面间 `location.href` 导航造成全量重载，用户体验割裂 | 🔴 高 |
| P02 | 架构 | **无构建工具**：零散的 JS/CSS 文件，无压缩、无 Tree Shaking、无模块化 | 🔴 高 |
| P03 | 代码 | **重复代码严重**：每个页面自行 `new Vue({el:"#app",...})`，`goBack()`、`formatTime()` 等工具函数在多处重复定义 | 🔴 高 |
| P04 | 状态 | **无跨页面状态管理**：token 存 `sessionStorage`，用户信息每个页面独立获取，无缓存复用 | 🟡 中 |
| P05 | UI | **Element UI 全量引入**：整个 `element.js`（~600KB）每页加载，但实际只用到 Rate、Dropdown、Input、Message 等少量组件 | 🟡 中 |
| P06 | UI | **硬编码模拟数据**：shop-detail 中评价列表为硬编码 HTML（3 条假数据），非 API 驱动 | 🟡 中 |
| P07 | UI | **品牌残留**：`<title>黑马点评</title>` 未改，copyright 仍显示 `campusdeal.com` | 🟢 低 |
| P08 | UX | **无加载状态**：API 请求期间页面空白，无 skeleton/loading/spinner | 🟡 中 |
| P09 | UX | **token 用 sessionStorage**：关闭标签页即丢失，且与 chat.html 的 localStorage 不一致（chat.html 用 `campusdeal-token`，主站用 `token`） | 🟡 中 |
| P10 | 样式 | **CSS 碎片化**：每页 3-4 个 `<link>`，`main.css` 中耦合了 Element UI 重写和公共样式 | 🟢 低 |
| P11 | 样式 | **非响应式**：硬编码 px 值，移动端 viewport `user-scalable=0`（无障碍问题），无 PC 端适配 | 🟡 中 |
| P12 | 脚本 | **axios baseURL 硬编码为 `/api`**：nginx 反向代理依赖，路径 `/api/xxx` → 后端 `/xxx`，增加了一层不必要的耦合 | 🟢 低 |
| P13 | 安全 | **v-html XSS 风险**：`blog-detail.html` 帖子正文用 `v-html` 直接渲染用户输入，无 XSS 过滤 | 🔴 高 |
| P14 | 状态 | **sessionStorage key 冲突**：`info.html`（个人主页）和 `other-info.html`（他人主页）共用 `sessionStorage['userInfo']`，后者覆盖前者 | 🟡 中 |
| P15 | 架构 | **额外旧页面**：`login.html`（短信登录）、`login2.html`（密码登录）、`info-edit.html`（名为编辑实为只读展示）仍存在，与已实现的 chat.html 登录流程冗余 | 🟢 低 |
| P16 | 样式 | **`.header` CSS 在 5 个文件中重复定义**且值冲突（`height` 6%/7%、`position` fixed/static、`border-bottom` 不一致），评论区域 CSS 在 `shop-detail.css` 和 `blog-detail.css` 中完全重复（~70 行） | 🟡 中 |
| P17 | 脚本 | **Token 仅模块加载时捕获一次**：`common.js` 顶部 `let token = sessionStorage.getItem("token")` 是静态快照，不会随登录态动态更新 | 🔴 高 |

### 1.3 现有 API 调用清单

| 页面 | API 调用 | HTTP 方法 |
|------|---------|-----------|
| index.html | `/merchant-type/list` | GET |
| index.html | `/post/hot?current=` | GET |
| index.html | `/post/like/{id}` | PUT |
| index.html | `/post/{id}` | GET |
| shop-list.html | `/merchant-type/list` | GET |
| shop-list.html | `/merchant/of/type?typeId=&current=&x=&y=` | GET |
| shop-detail.html | `/merchant/{id}` | GET |
| shop-detail.html | `/coupon/list/{shopId}` | GET |
| shop-detail.html | `/coupon-order/seckill/{id}` | POST |
| blog-detail.html | `/post/{id}` | GET |
| blog-detail.html | `/merchant/{id}` | GET |
| blog-detail.html | `/post/likes/{id}` | GET |
| blog-detail.html | `/post/like/{id}` | PUT |
| blog-detail.html | `/follow/or/not/{id}` | GET |
| blog-detail.html | `/follow/{id}/{isFollow}` | PUT |
| blog-detail.html | `/user/me` | GET |
| blog-edit.html | `/merchant/of/name?name=` | GET |
| blog-edit.html | `/post` | POST |
| blog-edit.html | `/upload/post` | POST (multipart) |
| blog-edit.html | `/upload/post/delete?name=` | GET |
| info.html | `/user/me` | GET |
| info.html | `/post/of/me?current=` | GET |
| info.html | `/post/of/follow?lastId=&offset=` | GET |
| info.html | `/post/like/{id}` | PUT |
| info.html | `/post/{id}` | GET |
| other-info.html | `/user/{id}` | GET |
| other-info.html | `/post/of/user?current=&id=` | GET |
| other-info.html | `/post/like/{id}` | PUT |
| other-info.html | `/post/{id}` | GET |
| login.html | `/user/code?phone=` | POST |
| login.html | `/user/login` | POST |
| login2.html | `/user/login` (密码登录) | POST |
| info-edit.html | `/user/me` | GET |

> **注**：`login2.html`（密码登录）、`info-edit.html`（只读展示，无编辑功能）将在重构中移除，统一使用 SPA 内的登录页和个人页。

---

## 2. 重构目标与原则

### 2.1 核心目标

| 目标 | 说明 |
|------|------|
| **SPA 架构** | 从 8 个独立 HTML 迁移到单页应用，实现无刷新页面切换 |
| **组件化** | 抽取公共组件（Header、Footer、Rate、PostCard 等），消除重复代码 |
| **统一状态管理** | 单一 Store 管理用户态、Token、商家/帖子缓存 |
| **渐进式加载** | 按路由拆分代码，首页优先渲染，其余懒加载 |
| **品牌统一** | 全站 CampusDeal 品牌标识、配色、文案 |
| **API 层抽象** | 集中的 API 模块，统一错误处理、重试、缓存 |

### 2.2 设计原则

1. **零后端改动**：前端重构不触碰 Java 代码，不改 API 契约
2. **轻量优先**：不引入重型框架（React/Angular），保持体量可控
3. **渐进增强**：先完成核心流程（首页→商家列表→商家详情→登录），再逐步增强
4. **移动优先**：面向手机屏幕设计，同时兼顾 PC 基本可用
5. **纯静态部署**：最终产物仍为静态文件，由 nginx 直接 serve

### 2.3 技术选型

| 维度 | 方案 | 理由 |
|------|------|------|
| **框架** | Vue 3 (CDN) + Vue Router 4 | Vue 2→3 升级获得 Composition API；CDN 引入免构建，保持与现有部署方式一致 |
| **UI 库** | **放弃 Element UI**，改用自研轻量组件 | Element UI ~600KB 全量引入浪费带宽；自研组件仅覆盖需要的 Rate/Dropdown/Message/Input，总体积 < 30KB |
| **HTTP** | Axios（保留） | 现有代码已用，团队熟悉 |
| **状态** | Pinia (CDN) 或简易 reactive Store | Vue 3 `reactive` 即可满足需求，无需引入额外依赖 |
| **构建** | **无需构建** | CDN 引入 Vue 3 + Vue Router + Pinia，直接写 ES module 或 IIFE，nginx serve 静态文件 |
| **CSS** | 单文件 `app.css`（CSS Variables + BEM 命名） | 消除多 CSS 碎片，CSS Variables 支持主题切换 |
| **图标** | SVG inline / emoji | 不需要 iconfont 全量引入 |

> **为什么不用 Vite/Webpack？**
> 当前 nginx 直接 serve 静态 HTML，引入构建工具需要增加 node/npm 环境、CI 流水线改动。
> 对于 ~10 个页面的中小型应用，CDN 引入 Vue 3 + 自研轻量组件完全可行，
> Vue 3 runtime (~120KB gzip) + Router (~30KB) 合计 < 150KB，用户首次访问后浏览器缓存。

---

## 3. 新架构设计

### 3.1 文件结构（重构后）

```
nginx-1.18.0/html/campusdeal/
├── index.html              # SPA 入口（唯一的 HTML 文件）
├── css/
│   └── app.css             # 全局样式（CSS Variables + BEM + 响应式）
├── js/
│   ├── vendor/             # 第三方库（CDN 缓存本地）
│   │   ├── vue.global.prod.js      # Vue 3.4
│   │   ├── vue-router.global.prod.js
│   │   └── axios.min.js
│   ├── app.js              # SPA 启动入口：createApp + Router + Store
│   ├── store.js            # 全局状态管理（reactive + localStorage 持久化）
│   ├── router.js           # 路由配置（各页面懒加载）
│   ├── api.js              # API 抽象层（统一 baseURL、拦截器、错误处理）
│   ├── utils.js            # 工具函数（formatPrice, formatTime, debounce 等）
│   ├── components/         # 公共组件
│   │   ├── app-header.js   # 顶部导航（标题 + 返回 + 搜索）
│   │   ├── foot-bar.js     # 底部导航栏（首页 / 发帖 / 我的）
│   │   ├── merchant-card.js# 商家卡片
│   │   ├── post-card.js    # 帖子卡片
│   │   ├── rate-star.js    # 星级评分（替代 el-rate）
│   │   ├── coupon-card.js  # 优惠券卡片
│   │   ├── loading-view.js # 加载状态 / 骨架屏
│   │   ├── error-view.js   # 错误状态 / 重试
│   │   ├── empty-view.js   # 空数据状态
│   │   └── confirm-dialog.js# 确认弹窗
│   └── pages/              # 页面组件
│       ├── home.js         # 首页（商家分类 + 热门帖子）
│       ├── shop-list.js    # 商家列表（GEO + 筛选 + 排序）
│       ├── shop-detail.js  # 商家详情 + 优惠券 + 秒杀
│       ├── post-detail.js  # 帖子详情 + 点赞 + 关注
│       ├── post-edit.js    # 发帖编辑器
│       ├── profile.js      # 个人主页（我的帖子 + 关注流）
│       ├── user-profile.js # 他人主页
│       └── login.js        # 登录页（手机号 + 验证码）
└── imgs/                   # 图片资源（不变）
```

### 3.2 SPA 入口 — `index.html`

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CampusDeal - 校园生活服务平台</title>
    <link rel="stylesheet" href="/css/app.css">
</head>
<body>
    <div id="app">
        <!-- 首屏快速渲染：加载中状态 -->
        <div class="app-loading">
            <div class="app-loading-spinner"></div>
            <p>CampusDeal 加载中...</p>
        </div>
    </div>

    <!-- Vue 3 + 生态 CDN -->
    <script src="/js/vendor/vue.global.prod.js"></script>
    <script src="/js/vendor/vue-router.global.prod.js"></script>
    <script src="/js/vendor/axios.min.js"></script>

    <!-- 应用入口（ES module，type="module" 可在现代浏览器中直接使用 import） -->
    <script type="module" src="/js/app.js"></script>
</body>
</html>
```

### 3.3 路由设计

```javascript
// router.js
const { createRouter, createWebHashHistory } = VueRouter;

export const routes = [
    { path: '/',              name: 'home',         component: () => import('./pages/home.js') },
    { path: '/shops/:typeId', name: 'shop-list',    component: () => import('./pages/shop-list.js'),
      props: route => ({ typeId: Number(route.params.typeId), typeName: route.query.name }) },
    { path: '/shop/:id',      name: 'shop-detail',  component: () => import('./pages/shop-detail.js'),
      props: route => ({ id: Number(route.params.id) }) },
    { path: '/post/:id',      name: 'post-detail',  component: () => import('./pages/post-detail.js'),
      props: route => ({ id: Number(route.params.id) }) },
    { path: '/post-edit',     name: 'post-edit',    component: () => import('./pages/post-edit.js'),
      meta: { requiresAuth: true } },
    { path: '/profile',       name: 'profile',      component: () => import('./pages/profile.js'),
      meta: { requiresAuth: true } },
    { path: '/user/:id',      name: 'user-profile', component: () => import('./pages/user-profile.js'),
      props: route => ({ id: Number(route.params.id) }) },
    { path: '/login',         name: 'login',        component: () => import('./pages/login.js') },
];

export function createAppRouter() {
    return createRouter({
        history: createWebHashHistory(),
        routes,
        scrollBehavior(to, from, savedPosition) {
            if (savedPosition) return savedPosition;
            return { top: 0 };
        }
    });
}
```

**路由说明**：
- 使用 Hash 模式（`#/`）：纯静态部署无 server-side 配置需求
- 页面组件 `() => import(...)` 动态导入：浏览器原生 ES module 按需加载
- `props` 传参：将路由参数映射为组件 props，解耦组件与 router
- `meta.requiresAuth`：发帖和个人页需要登录，路由守卫拦截

### 3.4 状态管理 — `store.js`

```javascript
// store.js —— 基于 Vue 3 reactive + localStorage 持久化
import { api } from './api.js';

const TOKEN_KEY = 'campusdeal-token';  // 统一 key，与 chat.html 一致
const USER_KEY = 'campusdeal-user';

export const store = Vue.reactive({
    // ── 用户态 ──
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null'),
    isLoggedIn: computed(() => !!store.token),

    // ── 缓存 ──
    merchantTypes: [],          // 商家分类（全站缓存）
    merchantCache: new Map(),   // 商家详情缓存 {id: merchant}

    // ── Actions ──
    async login(phone, code) {
        const res = await api.post('/user/login', { phone, code });
        if (res.success) {
            store.token = res.data;
            localStorage.setItem(TOKEN_KEY, res.data);
            await store.fetchUser();
        }
        return res;
    },

    async fetchUser() {
        if (!store.token) return null;
        try {
            const res = await api.get('/user/me');
            store.user = res.data;
            localStorage.setItem(USER_KEY, JSON.stringify(res.data));
            return res.data;
        } catch { return null; }
    },

    logout() {
        store.token = '';
        store.user = null;
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
    },

    // 商家类型（内存缓存，避免重复请求）
    async getMerchantTypes() {
        if (store.merchantTypes.length) return store.merchantTypes;
        const res = await api.get('/merchant-type/list');
        store.merchantTypes = res.data;
        return res.data;
    }
});

// 路由守卫
export function setupAuthGuard(router) {
    router.beforeEach(async (to, from, next) => {
        if (to.meta.requiresAuth && !store.isLoggedIn) {
            next({ name: 'login', query: { redirect: to.fullPath } });
        } else {
            next();
        }
    });
}
```

### 3.5 API 抽象层 — `api.js`

```javascript
// api.js —— 统一 HTTP 层
const BASE_URL = '';  // nginx 反向代理 /api → 后端，前端直连不需要前缀
// 注：现状 nginx proxy_pass 将 /api/* → 后端 /*，前端使用 axios baseURL='/api'
// 重构后改为直连 8081（开发）或通过 nginx proxy（生产），统一由 api.js 管理

const http = axios.create({
    baseURL: BASE_URL,
    timeout: 8000,
});

// 请求拦截：自动注入 token
http.interceptors.request.use(config => {
    if (store.token) {
        config.headers.Authorization = store.token;
    }
    return config;
});

// 响应拦截：统一错误处理
http.interceptors.response.use(
    response => {
        const body = response.data;
        if (!body.success) {
            return Promise.reject(new ApiError(body.errorMsg || '请求失败'));
        }
        return body;  // 返回 { success, data, errorMsg, total }
    },
    error => {
        if (error.response?.status === 401) {
            store.logout();
            router.push('/login');
        }
        return Promise.reject(new ApiError('网络异常，请稍后重试'));
    }
);

// 工具方法
export function httpGet(url, params) { return http.get(url, { params }).then(r => r.data); }
export function httpPost(url, data) { return http.post(url, data).then(r => r.data); }
export function httpPut(url, data) { return http.put(url, data).then(r => r.data); }
export function httpDelete(url) { return http.delete(url).then(r => r.data); }
```

---

## 4. 组件设计

### 4.1 组件树

```
App.vue
├── AppHeader          (全局顶部导航栏)
│   ├── 返回按钮       (路由非首页时显示)
│   ├── 页面标题       (根据当前路由动态展示)
│   └── 搜索/用户入口  (搜索页入口 + 用户头像)
│
├── <router-view>      (页面内容区，keep-alive 可选)
│   ├── HomePage
│   │   ├── TypeGrid   (商家分类宫格)
│   │   └── PostFlow   (热门帖子瀑布流)
│   │       └── PostCard × N
│   │
│   ├── ShopListPage
│   │   ├── SortBar    (分类筛选 + 排序：距离/人气/评分)
│   │   └── MerchantCard × N
│   │
│   ├── ShopDetailPage
│   │   ├── ShopInfo   (商家头图 + 评分 + 地址)
│   │   ├── CouponCard × N
│   │   │   └── SeckillButton (秒杀按钮 + 倒计时)
│   │   └── CommentList(评价列表)
│   │
│   ├── PostDetailPage
│   │   ├── Swiper     (图片轮播)
│   │   ├── PostContent(帖子正文)
│   │   ├── LikeBar    (点赞条)
│   │   └── CommentList(评论列表)
│   │
│   ├── PostEditPage
│   │   ├── ShopPicker (商家选择器)
│   │   ├── ImageUpload(图片上传)
│   │   └── RichEditor (内容编辑器)
│   │
│   ├── ProfilePage
│   │   ├── UserCard   (用户信息卡片)
│   │   └── PostCard × N (我的帖子 / 关注动态)
│   │
│   ├── UserProfilePage(同上，只读模式)
│   │
│   └── LoginPage
│       ├── PhoneInput (手机号输入)
│       ├── CodeInput  (验证码输入 + 倒计时)
│       └── LoginHint  (Redis 查看提示)
│
├── LoadingView        (全局加载状态，页面级展示)
├── ErrorView          (全局错误状态)
├── EmptyView          (空数据状态)
│
└── FootBar            (全局底部导航栏)
    ├── 首页 Tab
    ├── 发帖 Tab       (点击检查登录态)
    └── 我的 Tab
```

### 4.2 公共组件规范

```javascript
// 以 MerchantCard 为例 —— 每个组件定义为 Vue 3 defineComponent 选项对象

// components/merchant-card.js
export default {
    name: 'MerchantCard',
    props: {
        merchant: { type: Object, required: true }
    },
    computed: {
        firstImage() {
            return this.merchant.images?.split(',')[0] || '/imgs/default-shop.png';
        },
        displayDistance() {
            const d = this.merchant.distance;
            if (!d) return '';
            return d < 1000 ? `${d.toFixed(0)}m` : `${(d/1000).toFixed(1)}km`;
        },
        displayScore() {
            return (this.merchant.score / 10).toFixed(1);
        }
    },
    methods: {
        goDetail() {
            this.$router.push(`/shop/${this.merchant.id}`);
        }
    },
    template: /* html */ `
    <div class="merchant-card" @click="goDetail">
        <div class="merchant-card__image">
            <img :src="firstImage" :alt="merchant.name" loading="lazy">
        </div>
        <div class="merchant-card__info">
            <h3 class="merchant-card__title">{{ merchant.name }}</h3>
            <div class="merchant-card__meta">
                <RateStar :value="merchant.score / 10" :size="12" readonly />
                <span class="merchant-card__comments">{{ merchant.comments }}条评价</span>
            </div>
            <div class="merchant-card__footer">
                <span class="merchant-card__area">{{ merchant.area }}</span>
                <span v-if="displayDistance" class="merchant-card__distance">{{ displayDistance }}</span>
                <span class="merchant-card__price">¥{{ merchant.avgPrice }}/人</span>
            </div>
        </div>
    </div>`
};
```

### 4.3 RateStar 评分组件

```javascript
// components/rate-star.js —— 替代 Element UI el-rate (~40KB → ~2KB)
export default {
    name: 'RateStar',
    props: {
        modelValue: { type: Number, default: 0 },   // 当前值 0-5
        size: { type: Number, default: 16 },         // 星大小 px
        readonly: { type: Boolean, default: false }, // 是否只读
        count: { type: Number, default: 5 }          // 星数量
    },
    emits: ['update:modelValue'],
    computed: {
        stars() {
            const value = this.modelValue || 0;
            return Array.from({ length: this.count }, (_, i) => ({
                filled: i < Math.floor(value),
                half: !Number.isInteger(value) && i === Math.floor(value),
                index: i + 1
            }));
        }
    },
    methods: {
        clickStar(i) {
            if (!this.readonly) {
                this.$emit('update:modelValue', i);
            }
        }
    },
    template: /* html */ `
    <span class="rate-star" :style="{ fontSize: size + 'px' }" role="img" :aria-label="modelValue + '星'">
        <span v-for="s in stars" :key="s.index"
              class="rate-star__item"
              :class="{ 'rate-star__item--filled': s.filled, 'rate-star__item--half': s.half }"
              @click="clickStar(s.index)">
            ★
        </span>
    </span>`
};
```

---

## 5. CSS 设计系统

### 5.1 CSS Variables（设计令牌）

```css
/* app.css —— 全局样式 */
:root {
    /* 主色调 */
    --color-primary: #1976d2;
    --color-primary-light: #42a5f5;
    --color-primary-dark: #1565c0;
    
    /* 语义色 */
    --color-success: #2e7d32;
    --color-warning: #f57c00;
    --color-danger: #c62828;
    --color-info: #0288d1;
    
    /* 中性色 */
    --color-bg: #f5f6fa;
    --color-surface: #ffffff;
    --color-border: #e8e8e8;
    --color-text-primary: #212121;
    --color-text-secondary: #757575;
    --color-text-disabled: #9e9e9e;
    
    /* 间距 */
    --space-xs: 4px;
    --space-sm: 8px;
    --space-md: 12px;
    --space-lg: 16px;
    --space-xl: 24px;
    
    /* 圆角 */
    --radius-sm: 6px;
    --radius-md: 10px;
    --radius-lg: 16px;
    --radius-round: 50%;
    
    /* 阴影 */
    --shadow-card: 0 2px 8px rgba(0,0,0,0.08);
    --shadow-dialog: 0 8px 32px rgba(0,0,0,0.2);
    
    /* 字体 */
    --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    --font-size-xs: 11px;
    --font-size-sm: 13px;
    --font-size-md: 15px;
    --font-size-lg: 17px;
    --font-size-xl: 20px;
    
    /* 安全区域 */
    --safe-area-bottom: env(safe-area-inset-bottom, 0px);
}
```

### 5.2 BEM 命名规范

```css
/* ── Merchant Card ── */
.merchant-card {                /* Block */
    background: var(--color-surface);
    border-radius: var(--radius-md);
    overflow: hidden;
    box-shadow: var(--shadow-card);
}
.merchant-card__image {         /* Element */
    width: 100%;
    height: 140px;
    overflow: hidden;
}
.merchant-card__image img {
    width: 100%;
    height: 100%;
    object-fit: cover;
}
.merchant-card--promoted {      /* Modifier */
    border: 2px solid var(--color-warning);
}
```

### 5.3 响应式断点

```css
/* 移动端优先，向上覆盖 */
/* 默认：< 480px 手机竖屏 */
/* ≥ 768px 平板 / 小屏 PC */
@media (min-width: 768px) {
    .merchant-card__image { height: 180px; }
}
/* ≥ 1024px PC 端 */
@media (min-width: 1024px) {
    #app {
        max-width: 480px;        /* PC 端居中模拟手机宽度 */
        margin: 0 auto;
        border-left: 1px solid var(--color-border);
        border-right: 1px solid var(--color-border);
        min-height: 100vh;
    }
}
```

---

## 6. 页面详细设计

### 6.1 首页 — `HomePage`

**状态机**：
```
[加载中 LoadingView] → 有数据 → [分类宫格 + 帖子流]
                      → 无数据 → [EmptyView: "暂无内容"]
                      → 网络错误 → [ErrorView + 重试按钮]
```

**帖子流滚动加载**：
```javascript
// 使用 IntersectionObserver 替代 scroll 事件监听（性能更好）
setup() {
    const loadMore = ref(null);  // 哨兵元素
    onMounted(() => {
        const observer = new IntersectionObserver(entries => {
            if (entries[0].isIntersecting && !loading.value && !finished.value) {
                current.value++;
                fetchPosts();
            }
        }, { rootMargin: '100px' });  // 提前 100px 触发
        if (loadMore.value) observer.observe(loadMore.value);
        onUnmounted(() => observer.disconnect());
    });
}
```

**点赞优化**：
- 乐观更新：点击即改变 UI，无需等待 API 响应
- 失败回滚：API 失败后恢复原值 + Toast 提示

### 6.2 商家详情 — `ShopDetailPage`

**优惠券秒杀流程**：
```
[优惠券卡片] → 点击抢购
  → 未登录？ → 跳转 /login?redirect=/shop/{id}
  → 已登录 → 条件检查：
      → 未开始 → Toast "尚未开始"
      → 已结束 → Toast "已结束"
      → 库存不足 → Toast "已抢完"
      → 可抢购 → POST /coupon-order/seckill/{id}
          → 成功 → Toast "抢购成功" + 卡片状态更新
          → 失败 → Toast 错误信息
```

**商户缓存策略**：详情页进入时检查 `store.merchantCache`，命中则立即渲染，同时后台静默请求最新数据。

### 6.3 帖子详情 — `PostDetailPage`

**图片轮播重构**：
- 现有手动触摸实现 → 使用标准 CSS `scroll-snap-type: x mandatory`
- 底部指示器 dots 显示当前位置
- 支持双指缩放（`touch-action: pan-x pinch-zoom`）

**点赞功能**：
```javascript
// 乐观更新 + 本地去重（防止快速双击）
const likePending = ref(false);
async function toggleLike(postId) {
    if (likePending.value) return;
    likePending.value = true;
    const prev = { liked: post.liked, isLike: post.isLike };
    // 乐观更新
    post.isLike = !post.isLike;
    post.liked += post.isLike ? 1 : -1;
    try {
        await httpPut(`/post/like/${postId}`);
    } catch {
        // 回滚
        Object.assign(post, prev);
        Toast.error('操作失败');
    } finally {
        likePending.value = false;
    }
}
```

### 6.4 登录页 — `LoginPage`

**复用 chat.html 的登录逻辑**：与聊天页保持一致的手机号+验证码流程。
- 60s 倒计时防重复发送
- 本地开发提示 "请从 Redis 查看验证码"
- 登录成功后回调 `redirect` 参数（恢复登录前的浏览上下文）

---

## 7. 页面状态设计

### 7.1 全局状态枚举

```javascript
// 每个数据获取场景覆盖以下四种状态
const PageState = {
    LOADING: 'loading',   // 加载中 → 展示 LoadingView
    READY: 'ready',       // 就绪   → 展示正常内容
    EMPTY: 'empty',       // 空数据 → 展示 EmptyView
    ERROR: 'error'        // 错误   → 展示 ErrorView + 重试
};
```

### 7.2 状态组件实现

```javascript
// components/loading-view.js
export default {
    name: 'LoadingView',
    template: /* html */ `
    <div class="state-view state-view--loading">
        <div class="state-view__spinner"></div>
        <p class="state-view__text"><slot>加载中...</slot></p>
    </div>`
};

// components/error-view.js
export default {
    name: 'ErrorView',
    props: { message: { type: String, default: '加载失败' } },
    emits: ['retry'],
    template: /* html */ `
    <div class="state-view state-view--error">
        <div class="state-view__icon">⚠️</div>
        <p class="state-view__text">{{ message }}</p>
        <button class="state-view__btn" @click="$emit('retry')">点击重试</button>
    </div>`
};

// components/empty-view.js
export default {
    name: 'EmptyView',
    props: { message: { type: String, default: '暂无内容' } },
    template: /* html */ `
    <div class="state-view state-view--empty">
        <div class="state-view__icon">📭</div>
        <p class="state-view__text">{{ message }}</p>
    </div>`
};
```

---

## 8. 安全加固

### 8.1 XSS 防护

现有 `blog-detail.html` 用 `v-html="blog.content"` 直接渲染帖子正文，无任何 XSS 过滤。重构后：

```javascript
// utils.js —— HTML 转义 + 安全渲染
export function sanitizeHtml(raw) {
    const div = document.createElement('div');
    div.textContent = raw;
    return div.innerHTML;
}

// 允许的白名单标签（Markdown 风格）
export function safeHtml(raw) {
    let html = sanitizeHtml(raw);
    // 仅允许：<br> <strong> <em> <code> <pre> <a href>
    html = html.replace(/&lt;br&gt;/g, '<br>');
    html = html.replace(/&lt;strong&gt;([^&]+)&lt;\/strong&gt;/g, '<strong>$1</strong>');
    html = html.replace(/&lt;em&gt;([^&]+)&lt;\/em&gt;/g, '<em>$1</em>');
    // 不还原 <script> / <iframe> / onerror / onload 等危险标签
    return html;
}
```

所有用户生成内容（帖子正文、评论、商家名称）都经过 `safeHtml()` 处理后再渲染。

### 8.2 Token 统一

```
现状问题：
  主站：sessionStorage['token']    → common.js 静态捕获一次
  Chat： localStorage['campusdeal-token'] → 动态读取

重构后：
  统一使用 localStorage['campusdeal-token']
  API 拦截器每次请求动态读取 store.token（Vue reactive）
  登录态在两个入口间实时同步
```

---

## 9. 兼容性处理

### 9.1 nginx 路由配置

SPA 使用 Hash 路由（`#/path`），无需修改 nginx 配置。所有路径在 `index.html` 的同一个 `<div id="app">` 中渲染，不产生额外的 HTTP 请求。

### 9.2 旧路径重定向

为保证外部链接（如收藏夹、分享链接）不 404，nginx 添加重定向规则：

```nginx
# 旧 HTML 路径 → SPA 首页
location ~ ^/(shop-list|shop-detail|blog-detail|blog-edit|info|other-info|login)\.html {
    return 301 /;
}
```

### 9.3 浏览器兼容

| 特性 | 最低版本要求 | 降级方案 |
|------|-------------|---------|
| ES Module (`<script type="module">`) | Chrome 61, Safari 11, Edge 16 | `nomodule` fallback IIFE |
| CSS Variables | Chrome 49, Safari 9.1 | PostCSS 编译为静态值（可选） |
| IntersectionObserver | Chrome 51, Safari 12.1 | Polyfill 或回退到 scroll 事件 |
| Fetch API | Chrome 42, Safari 10.1 | Axios（内部用 XMLHttpRequest） |

---

## 10. 实施计划

### 10.1 阶段划分

| 阶段 | 内容 | 预估工作量 |
|------|------|-----------|
| **Phase 1：基础设施** | `index.html` SPA 入口、Router、Store、API 层、CSS 设计系统 | 1 天 |
| **Phase 2：公共组件** | AppHeader、FootBar、MerchantCard、PostCard、RateStar、Loading/Error/Empty 状态组件 | 1 天 |
| **Phase 3：核心页面** | 首页、商家列表、商家详情 | 1.5 天 |
| **Phase 4：社交页面** | 帖子详情、发帖编辑、个人主页、他人主页 | 1.5 天 |
| **Phase 5：登录与鉴权** | 登录页、路由守卫、Token 统一 | 0.5 天 |
| **Phase 6：收尾** | 旧文件归档、nginx 重定向、联调测试、PC 端微调 | 0.5 天 |

### 10.2 文件迁移计划

```
Phase 1 产出：
  js/vendor/  (3 个 vendor 文件)
  js/app.js, js/router.js, js/store.js, js/api.js, js/utils.js
  css/app.css

Phase 2 产出：
  js/components/*.js (9 个组件)

Phase 3-4 产出：
  js/pages/*.js (8 个页面)

Phase 6 完成后：
  旧 HTML/CSS/JS → 移入 archive/ 或直接删除
```

### 10.3 风险与缓解

| 风险 | 缓解 |
|------|------|
| Vue 3 CDN 模块加载慢 | vendor 文件部署在本地 nginx，不走 CDN，首屏 ~150KB gzip |
| ES Module 浏览器兼容 | 目标用户为大学生，主流设备 iOS Safari 15+ / Android Chrome 90+ 均支持 |
| 重构期间线上可用 | 先在新目录开发，nginx 按路径分流：`/v2/` 新版本，旧路径不变 |

---

## 11. 测试策略

### 11.1 功能测试清单

| 编号 | 场景 | 操作 | 预期结果 |
|------|------|------|---------|
| FT-01 | 首页加载 | 访问 `/` | 分类宫格 + 热门帖子展示，图片懒加载 |
| FT-02 | 分类跳转 | 点击分类图标 | 跳转 `/shops/{typeId}`，列表加载 |
| FT-03 | 列表滚动 | 商家列表滚动到底 | 自动加载下一页（IntersectionObserver） |
| FT-04 | 距离排序 | 点击"距离"排序 | 列表按距离重排（带 Geo 坐标参数） |
| FT-05 | 商家详情 | 点击商家卡片 | 跳转 `/shop/{id}`，展示详情+优惠券 |
| FT-06 | 秒杀抢购 | 已登录+秒杀进行中 → 点击抢购 | 扣减库存，返回订单 ID |
| FT-07 | 秒杀未登录 | 未登录 → 点击抢购 | 跳转 `/login?redirect=/shop/{id}` |
| FT-08 | 帖子点赞 | 点击点赞图标 | 乐观更新 + 图标变色 + 数量变化 |
| FT-09 | 帖子详情 | 点击帖子 | 图片轮播（CSS scroll-snap）+ 正文 + 关联商户 |
| FT-10 | 关注用户 | 帖子详情页点击"关注" | 按钮切换"已关注"，再次点击取消 |
| FT-11 | 发帖 | 已登录 → 底部"+" → 发帖页 | 选择商户 + 上传图片 + 发布 |
| FT-12 | 发帖未登录 | 未登录 → 底部"+" | 跳转登录页 |
| FT-13 | 登录 | 手机号+验证码 → 登录 | token 存 localStorage，回调 redirect |
| FT-14 | 退出 | 我的页 → 退出登录 | 清除 token，回到首页 |
| FT-15 | 网络错误 | 断网 → 任意 API 请求 | ErrorView + "点击重试"按钮 |
| FT-16 | 空数据 | 无帖子的用户个人页 | EmptyView "暂无内容" |
| FT-17 | 旧链接兼容 | 访问 `/shop-list.html?type=1` | 301 → `/` 首页 |

### 11.2 性能指标

| 指标 | 目标值 | 测量方式 |
|------|--------|---------|
| FCP (First Contentful Paint) | < 1.5s | Chrome DevTools Lighthouse |
| LCP (Largest Contentful Paint) | < 3s | Chrome DevTools Lighthouse |
| 页面切换延迟 | < 100ms | Vue Router 导航守卫计时 |
| JS 总体积 (gzip) | < 200KB | 文件大小统计 |
| CSS 总体积 (gzip) | < 15KB | 文件大小统计 |

---

## 12. 与后端接口的契约

重构不改后端接口，所有 API 路径与响应格式保持不变：

```
GET    /merchant-type/list       →  Result { data: MerchantType[] }
GET    /merchant/of/type         →  Result { data: Merchant[] }          params: typeId, current, x?, y?, sortBy?
GET    /merchant/{id}            →  Result { data: Merchant }
GET    /merchant/of/name         →  Result { data: Merchant[] }          params: name
POST   /merchant                 →  Result (save)
PUT    /merchant                 →  Result (update)

POST   /post                     →  Result { data: postId }
GET    /post/hot                 →  Result { data: Post[] }              params: current
GET    /post/{id}                →  Result { data: Post }
GET    /post/of/me               →  Result { data: Post[] }              params: current
GET    /post/of/user             →  Result { data: Post[] }              params: current, id
GET    /post/of/follow           →  Result { data: Post[] }              params: lastId, offset
PUT    /post/like/{id}           →  Result
GET    /post/likes/{id}          →  Result { data: User[] }

GET    /coupon/list/{shopId}     →  Result { data: Coupon[] }
POST   /coupon-order/seckill/{id}→  Result { data: orderId }

GET    /follow/or/not/{id}       →  Result { data: boolean }
PUT    /follow/{id}/{isFollow}   →  Result
GET    /follow/common/{id}       →  Result { data: User[] }

POST   /user/code                →  Result                                params: phone
POST   /user/login               →  Result { data: token }                body: {phone, code}
POST   /user/logout              →  Result                                header: authorization
GET    /user/me                  →  Result { data: User }
GET    /user/info/{id}           →  Result { data: User }

POST   /upload/post              →  Result { data: imageUrl }             multipart file
GET    /upload/post/delete       →  Result                                params: name
```

> 详细接口说明参见各业务模块设计文档（01-06）和 `application.yaml`。

---

## 附录 A：组件通信规范

```
props down, events up

父 → 子：通过 props 传递数据
子 → 父：通过 $emit 触发事件
跨组件：通过 store 共享状态（不做兄弟组件直接通信）

组件内部状态（如表单输入、展开/折叠）使用 data/computed
需要持久化或跨页面共享的状态（如 token、user、缓存）使用 store
```

## 附录 B：代码风格约定

- 所有组件使用 `defineComponent({ name: 'PascalCase', ... })` 格式
- 模板用 Template Literal `/* html */` 注释标记
- CSS 类名 BEM 命名：`block__element--modifier`
- 文件命名：组件 `kebab-case.js`，页面 `kebab-case.js`
- 每个文件只 export 一个 default
- 不在模板中使用复杂表达式，提取为 computed
- API 调用统一经过 `api.js` 的 httpGet/httpPost/httpPut
