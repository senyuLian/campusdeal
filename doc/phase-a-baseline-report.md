# 阶段 A 基线测试报告

> 执行日期：2026-08-13　|　执行脚本：`tools/api-smoke.js`（后端）+ `tools/cdp-pages.js`（前端）

## 1. 环境就绪确认

| 组件 | 地址 | 状态 |
|------|------|------|
| nginx（前端 SPA） | `:8080` | ✅ 200 |
| Spring Boot 后端 | `:8081` | ✅ 200 |
| Redis | `:6379`（密码 123456） | ✅ 可连接 |
| MySQL | `:3306/campusdeal` | ✅（备份已存 `backup/campusdeal-20260813_130824.sql`） |
| Redis dump | 项目根 `dump.rdb` | ✅ SAVE 完成 |

## 2. 后端接口冒烟 + 鉴权基线（36 接口）

**结果：37 / 40 通过。** 3 项失败均为已确认缺陷。

### 通过项（按模块）
- User：code 发送 / login / logout(401) / me / info / public / sign / sign count
- Merchant：detail / of-type / nearby / of-name / type-list
- Coupon：list / flash-list / list-all / seckill(401 鉴权)
- Follow：or-not(401 + auth) / 其余 401
- Post：hot / detail / likes / of-user / of-me(401 + auth) / of-follow(401) / write(401) / like(401)
- Upload：upload/post 非 401
- Agent：history(401) / confirm(401)

### 失败项（3 个已确认缺陷）

| # | 缺陷 | 实测表现 |
|---|------|----------|
| **P0-1** | 秒杀 Kafka 阻塞 | `POST /coupon-order/seckill/10`（带 token）**阻塞 60201ms** 后返回「服务器异常」。根因：`FlashDealProducerImpl` 用 `send().get(5s)`，Kafka 未运行 → `max.block.ms=60s` 默认值，比方案预估 5s 更糟 |
| **P0-4** | `/merchant/**` 写接口公开 | `POST /merchant`、`PUT /merchant` 无 token 返回 **200**（应 401）。根因：`MvcConfig` exclude 了 `/merchant/**`，读+写全放行 |
| **P0-3** | 秒杀订单不落库 | 秒杀失败后 Redis `flashdeal:stock:10`=98、`flashdeal:order:10`={1013}（已扣减/标记已购），但 `tb_voucher_order` 无用户 1013 订单 → 缓存与 DB 数据不一致 |

## 3. 前端页面矩阵基线（13 路由 CDP）

**结果：14 / 14 通过，JS_ERRORS = 0。**

| 路由 | 页面 | 关键元素断言 |
|------|------|-------------|
| `/` | home | `.search-bar` ✅ |
| `/shops/:typeId` | shop-list | `.shop-list-page` ✅ |
| `/shop/:id` | shop-detail | `.shop-detail__info` ✅ |
| `/search` | search | `.search-result__head` ✅ |
| `/flash` | flash | `.flash-page__title` ✅ |
| `/shops/nearby` | nearby | `.nearby-page` ✅ |
| `/coupons` | coupons | `.coupons-page__tabs` ✅ |
| `/post/:id` | post-detail | `.post-detail__author` ✅ |
| `/user/:id` | user-profile | `.profile__header` ✅ |
| `/login` | login | `.login__input` ✅ |
| `/post-edit` | post-edit | 匿名→login ✅ |
| `/profile` | profile | 匿名→login ✅ |
| `/chat` | chat | 匿名→login ✅ |
| 需登录页回跳 | — | `#/login?redirect=/profile` ✅ |

## 4. 新增发现（原方案未列）

| # | 发现 | 说明 | 建议级别 |
|---|------|------|----------|
| **P1-7** | 秒杀库存无启动预载 | `FlashDealServiceImpl.preloadStock()` 为**死代码**，从未被调用。Redis 重启后 `flashdeal:stock:*` 为空 → 所有秒杀直接「Out of stock」。需在启动/券发布时预载 | P1 |

## 5. 结论

- 前端 13 路由全部可渲染、鉴权回跳正确、无 JS 报错 → **前端基线通过**。
- 后端鉴权矩阵基本正确，但存在 **3 个 P0 缺陷**（秒杀 60s 阻塞 + 订单不落库 + 商户写接口公开）与 **1 个 P1 缺陷**（库存无预载）。
- 基线已固化，进入**阶段 B：P0 修复**。
