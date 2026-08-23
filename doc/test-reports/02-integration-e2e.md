# 测试报告 02 · 前后端联调与 E2E

> 日期：2026-08-15 ｜ 依据：`doc/test-plan/02-integration-e2e.md`
> 环境：nginx :8080（托管前端 + /api 反代）✅、后端 :8081 ✅

## 1. 执行范围与结果

| 套件 | 文件 | 结果 |
|---|---|---|
| E2E 全量回归（接口矩阵 + S1/S2/S3/S5） | `tools/regression-phase-d.js` | ✅ 53/53 |
| Agent SSE 协议（S4） | `tools/agent-sse-test.js` | ✅ 8/8 |
| 前端 13 路由渲染 | `tools/cdp-pages.js` | ✅ 14/14，JS_ERRORS=0 |
| 前端首页/子页内容断言 | `tools/cdp-verify-doc09.js` | ✅ 22/22（修复脚本后） |
| P0-5 越权泄漏回归 | `tools/auth-leak-test.js` | ✅ 2/2 |

## 2. 关键场景记录

### S1 登录→签到
- 签到成功、连续天数 ≥1、重复签到幂等（天数不变）。✅

### S2 秒杀→落库→幂等（deal=11，独立回归券）
- 秒杀响应 **15ms**（P0-1 修复后 Kafka 停机不阻塞）。✅
- 库存 10→9 正确扣减。✅
- `tb_voucher_order` 该券订单 +1（before=6 → after=7）。✅
- 重复下单 → `"Already purchased"`，不新增订单。✅

### S3 缓存一致性
- 查询 `/merchant/1` → 缓存写入；PUT 更新（回写原值无副作用）→ `cache:merchant:1` 被删除。✅

### S4 Agent SSE（含 DeepSeek 真实调用）
- 事件流：`thinking → tool_call → tool_result → tool_call → tool_result → chunk* → done`。
- 无 token=401；Content-Type=text/event-stream；以 done 收尾不挂起；history 可回读。✅

### S5 鉴权/越权
- 未登录秒杀=401；P0-4（POST/PUT /merchant 无 token=401）✅。
- **P0-5 ThreadLocal 泄漏**：40 并发匿名请求全部 401，泄漏=0。✅

### 前端联调
- `/api` 反代稳定（连续 6 次 200 无 502）。
- 13 路由渲染全绿、JS 无异常；需登录页匿名访问跳 `/login?redirect=...`。

## 3. 测试中发现并修复的问题

### 3.1 修复 1（nginx 配置）：upstream 含未运行的 8082 → 约一半请求 502

**现象**：`/api` 反代间歇性失败（旧 error.log 大量 `connect() failed ... 127.0.0.1:8082`）。

**根因**：`conf/nginx.conf` 的 `upstream backend` 配了两个 server（8081 + 8082，weight 1:1 轮询），而 8082 无服务实例，导致约 50% 请求失败。

**修复**（`nginx-1.18.0/conf/nginx.conf`）：upstream 仅保留 8081。

**验证**：连续 6 次 `/api/merchant-type/list` 全部 200。

### 3.2 修复 2（测试脚本）：cdp-verify-doc09.js 断言过期

**现象**：`金刚区标签` 与 `分类宫格 ≥5+更多` 两断言失败。

**根因**：前端已按 `home.js` 注释「美食已上移到金刚区（第一排），宫格内不再重复展示」改版 —— 金刚区=抢购/附近/卡券/签到/**美食**（非旧版"动态"），宫格=4 分类 + 更多（5 项，非 6）。脚本断言仍按旧版。

**修复**：断言更新为新版布局（金刚区含"美食"、宫格 `>=5`）。

**验证**：22/22。

## 4. 遗留观察

| 项 | 说明 |
|---|---|
| 前端 `_old/archive` 目录存在旧版资源 | 非功能问题，不影响运行 |
| `/blogs/...` 图片 404 | 帖子引用的历史图片未同步到 nginx 静态目录，仅影响图片展示 |

## 5. 模块结论

**联调/E2E 层通过**。四大 E2E 场景 + SSE 协议 + 前端 13 路由全绿；nginx upstream 配置缺陷已修复；P0-5 泄漏回归通过。

> 改动文件：`nginx-1.18.0/conf/nginx.conf`、`tools/cdp-verify-doc09.js`。
