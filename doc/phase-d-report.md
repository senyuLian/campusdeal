# 阶段 D：全量回归联调报告

> 执行时间：2026-08-13
> 范围：方案 §4.2 接口矩阵 + §4.3 前端页面矩阵 + §4.4 端到端 S1–S5 + 秒杀并发无超卖。
> 结论：**全量回归通过，P0/P1 缺陷清零；联调中又发现并修复 2 个缺陷（P0-5 越权泄漏、Kafka 发送仍阻塞 3s）。**

## 一、测试结果总览

| 测试项 | 脚本 | 结果 |
|---|---|---|
| 后端 36 接口矩阵 + 鉴权冒烟 + S1/S2/S3/S5 | `tools/regression-phase-d.js` | ✅ 53/53 |
| 前端 13 页面 CDP 回归（含需登录守卫回跳） | `tools/cdp-pages.js` | ✅ 14/14，JS_ERRORS=0 |
| 秒杀并发无超卖（30 用户抢 10 库存） | `tools/concurrency-test.js` | ✅ 7/7 |
| S4 Agent SSE（降级可用） | `tools/agent-sse-test.js` | ✅ 8/8 |
| P0-5 越权泄漏回归 | `tools/auth-leak-test.js` | ✅ 2/2 |

## 二、端到端场景结果（§4.4）

- **S1 登录→签到→角标**：发码 → Redis 读码 → 登录 → 签到成功 → 连续天数 ≥1 → 重复签到幂等（天数不变）。✅
- **S2 秒杀→订单落库→幂等**：
  - 秒杀成功，**耗时 47ms**（修复 Kafka 阻塞后，原 3.2s→47ms）。
  - Redis `flashdeal:stock:11` 10→9 扣减正确。
  - `tb_voucher_order` 订单增量 +1（落库验证通过）。
  - 重复下单 → `Already purchased`，不新增订单。
  - 并发压测：库存 10，30 用户并发 → **恰好 10 成功、20 Out of stock、0 异常、DB 增量 = 10，无超卖**，耗时 49ms。
- **S3 缓存一致性**：`GET /merchant/1` 写入 `cache:merchant:1` → `PUT /merchant` 更新后缓存失效（DEL）。✅
- **S4 Agent SSE**：无 token=401；有 token → 200 + `text/event-stream`，事件流 `thinking → error`（空 Key 降级），以 error 收尾不挂起；`/agent/history/{sessionId}` 可回读。✅
- **S5 鉴权/越权**：`POST/PUT /merchant` 未登录=401（P0-4）；`/agent/**`、`/user/**`、`/post` 等受保护接口未登录=401。✅

## 三、联调中发现并修复的新缺陷

### P0-5：异步 SSE 请求导致 ThreadLocal 越权泄漏（安全缺陷，已修复）

- **现象**：匿名 `POST /agent/chat` 间歇性返回 200（应 401），复现概率约 2/5。
- **根因**：`RefreshTokenInterceptor.preHandle` 在 token 为空时直接 `return true`，未清 `UserHolder`（ThreadLocal）。`/agent/chat` 是异步 SSE 请求，其 `afterCompletion`（`removeUser()`）延迟到异步完成后才执行；容器线程在此之前被归还线程池复用，携带上一个登录用户的残留 `UserHolder`，使后续匿名请求绕过 `LoginInterceptor`。
- **修复**：`RefreshTokenInterceptor.preHandle` 开头增加 `UserHolder.removeUser()`（`utils/RefreshTokenInterceptor.java`）。
- **验证**：`tools/auth-leak-test.js` —— 连续 10 次带 token 的 SSE 请求污染线程池后，40 次并发匿名 `GET /user/me` 全部 401，泄漏 0。修复前随机泄漏，修复后 8/8 匿名请求均 401。

### 秒杀 Kafka 发送仍阻塞 3s（已修复，并入 P0-1 收尾）

- **现象**：P0-1 修复后秒杀从 60s 降到 3.2s，但仍未「不阻塞主流程」——`kafkaTemplate.send()` 会同步等待 metadata，Kafka 未运行时阻塞 `max.block.ms`（3000ms）。
- **修复**：`FlashDealProducerImpl.send()` 将 `kafkaTemplate.send()` 下沉到后台 daemon 线程池（`flash-deal-kafka-send`），主流程立即返回。
- **验证**：秒杀耗时 3155ms → **47ms**。

## 四、遗留 / 转入阶段 E

| # | 事项 | 归属 |
|---|---|---|
| P2-6 | 订单号 17 位雪花 ID 超 JS Number 精度（2^53），前端 JSON.parse 舍尾位 | 阶段 E |
| P1-8 | 秒杀两层去重（`flashdeal:order:{dealId}` + `order:dedup:{userId}:{dealId}`）需同步清理 | 已纳入回归脚本处理 |
| P2-1 | nearby 全表内存算距离，量大需改 GEO | 阶段 E（可选） |
| P2-3 | 秒杀主流程无集成测试（仅脚本级验证） | 阶段 E |
| 备注 | `updateById` 只传 `id` 会生成空 SET 报 SQL 语法错（前端无写商户入口，影响面 0） | 记录 |

## 五、回归脚本索引（可一键复跑）

| 脚本 | 用途 |
|---|---|
| `node tools/regression-phase-d.js` | 接口矩阵 + S1/S2/S3/S5 |
| `node tools/concurrency-test.js` | 秒杀并发无超卖 |
| `node tools/agent-sse-test.js` | S4 Agent SSE |
| `node tools/auth-leak-test.js` | P0-5 越权泄漏回归 |
| `node tools/cdp-pages.js` | 前端 13 页面 CDP |
