# 阶段 C：P1 修复报告

> 执行时间：2026-08-13
> 范围：基线测试（阶段 A）与 P0 修复（阶段 B）之后，对 P1 级缺陷进行修复并验证。
> 结论：**7 项 P1 全部修复 / 确认满足，后端重新编译启动成功，验证通过。**

## P1 清单与修复结果

| # | 问题 | 修复方案 | 验证结果 |
|---|------|----------|----------|
| P1-1 | 商家信息更新后缓存未失效 | `MerchantServiceImpl.updateShop` 已 `DELETE cache:merchant:{id}`（原有实现满足） | ✅ 代码确认（updateShop 第 173 行） |
| P1-2 | 商家缓存一致性 | 同上：更新即删除缓存，逻辑过期 + 互斥锁重建兜底 | ✅ 代码确认 |
| P1-3 | 前端 401 后内存态未同步 | `api.js` 响应拦截 401 时广播 `campusdeal:unauthorized` 事件；`store.js` 监听后清空 `state.token`/`state.user` | ✅ 代码确认（前端静态改动，Phase D E2E 覆盖） |
| P1-4 | 日志级别过噪 | `application.yaml`：`logging.level.com.campusdeal: info` | ✅ 启动日志仅 INFO/WARN |
| P1-5 | DeepSeek API Key 明文入库 | `api-key: ${CAMPUSDEAL_DEEPSEEK_API_KEY:}`，移除明文 `sk-...` | ✅ 空 key 后端正常启动 |
| P1-6 | 图片删除用 GET（爬虫/预取误删） | `UploadController` 新增 `@PostMapping("/delete")`，前端 `post-edit.js` 改 `Api.post('/upload/delete?name=...')`；保留 GET 兼容 | ✅ `curl -X POST .../upload/delete` 返回 `{"success":true}` |
| P1-7 | Redis 重启后秒杀库存丢失（全 Out of stock） | `FlashDealServiceImpl` 实现 `InitializingBean.afterPropertiesSet()` → `preloadAllActiveStock()`：`剩余 = 活动库存 - 已落库订单数`，`setIfAbsent` 预热 | ✅ 重启后 `flashdeal:stock:10` 自动恢复为 `96`（99 - 3 已售） |

## 验证明细

### P1-7 库存预热（关键项）
- 预热前手动清空 `flashdeal:stock:10`（确认 Redis 中为 null）。
- 重启后端，启动日志输出：`Flash deal stock preloaded: 3 active deals`。
- 复查 Redis：`GET flashdeal:stock:10` → `96`，与 DB 计算一致（`stock=99`，`tb_voucher_order` 已售 `3`）。
- 结论：Redis 重启导致秒杀全线 Out of stock 的缺陷已修复。

### P1-6 删除改 POST
- `POST /upload/delete?name=__nonexist_probe__.png` → `{"success":true}`
- `GET /upload/post/delete?name=...`（旧接口）→ `{"success":true}`（兼容保留）

### P1-5 密钥移除
- 移除明文 key 后，后端在 `CAMPUSDEAL_DEEPSEEK_API_KEY` 为空的情况下正常启动：
  `Started CampusDealApplication in 4.329 seconds`，无 ERROR。

### P1-1 / P1-2 缓存一致性
- `updateShop` 更新 DB 后执行 `stringRedisTemplate.delete(CACHE_MERCHANT_KEY + id)`，符合「更新即失效」约定。

### P1-3 前端 401 同步
- `api.js`：401 分支清除 `campusdeal-token` / `campusdeal-user` 并 `dispatchEvent(new CustomEvent('campusdeal:unauthorized'))`。
- `store.js`：监听事件清空 `state.token` / `state.user`，保证 `isLoggedIn` 与路由守卫判断正确。
- 静态改动，完整链路（token 过期 → 请求 401 → 自动跳登录）由 Phase D E2E 回归覆盖。

### P1-4 日志级别
- `logging.level.com.campusdeal: info`，启动日志无 DEBUG 噪声。

## 编译与启动
- `mvn compile`：通过（JDK 17）。
- 重启后端：端口 8081 正常监听，启动耗时 4.3s。

## 遗留（转入阶段 D/E）
- **P2-6**：订单号 17 位雪花 ID 超过 JS `Number` 精度（2^53），前端 `JSON.parse` 会舍入尾位；后端返回与 DB 实际值差几位属正常，需在 E 阶段将订单号以字符串下发或前端用 BigInt 展示。
- **P1-8**：秒杀存在两层去重（Lua `flashdeal:order:{dealId}` 与消费者 `order:dedup:{userId}:{dealId}`），回归脚本需同时清理两层键。
- 其余 P2 项按计划在 Phase D（全量回归）与 Phase E（性能/收尾）处理。
