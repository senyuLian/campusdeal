# 测试报告 01 · 接口测试

> 日期：2026-08-15 ｜ 依据：`doc/test-plan/01-interface-test.md`
> 环境：后端 :8081 运行中，MySQL/Redis 可用，Kafka/Canal 未启动（降级）

## 1. 执行范围

| 套件 | 文件 | 结果 |
|---|---|---|
| 接口冒烟 + 鉴权基线（40 项） | `tools/api-smoke.js` | ✅ 40/40 |
| 五维契约（18 项） | `tools/api-contract-test.js`（本次新增） | ✅ 18/18 |
| 辅助工具 | `tools/get-token.js`（本次新增） | ✅ 可用 |

## 2. 覆盖内容

- **鉴权矩阵**：公开接口 200、需登录接口无 token=401、有 token=200。
- **Result 结构契约**：`success`/`errorMsg`/`data` 字段、成功时 `errorMsg=null`（Jackson non_null）。
- **五维用例**：参数合法/缺失/非法、未登录、数据边界。
- **关键契约**：orderId 字符串下发、登出 token 失效、签到幂等、缓存穿透空值缓存。

## 3. 测试中发现并修复的问题

### 3.1 修复 1（代码）：WebExceptionAdvice 参数错误无明确提示

**现象**：`PUT /follow/1/5`（isFollow 非布尔）返回通用 `"服务器异常"`；空 body 的写接口、缺参数同样吞成"服务器异常"。

**根因**：`WebExceptionAdvice` 仅有一个 `RuntimeException` 兜底 handler。

**修复**（`src/main/java/com/campusdeal/config/WebExceptionAdvice.java`）：
新增 4 个细化 handler —— `MethodArgumentTypeMismatchException→"参数类型错误"`、`HttpMessageNotReadableException→"请求参数不合法"`、`MissingServletRequestParameterException→"缺少必要参数: xxx"`、`MultipartException→"请求格式错误"`。

**验证**：

```json
PUT /follow/1/5            → {"success":false,"errorMsg":"参数类型错误"}
POST /merchant (空body)     → {"success":false,"errorMsg":"请求参数不合法"}
GET /merchant/of/type (缺参) → {"success":false,"errorMsg":"缺少必要参数: typeId"}
```

### 3.2 修复 2（测试脚本）：api-smoke.js P0-4 断言误报

**现象**：`POST/PUT /merchant`（无 body）被断言为"公开=缺陷"（返回 200）。

**根因**：空 body 在 `@RequestBody` 解析阶段先抛 `HttpMessageNotReadableException`（被兜底成 200），**走不到 Controller 内的登录校验**，属测试方法缺陷而非代码缺陷。

**修复**：断言改为携带合法 JSON body 后再验证 401。

**复核结论**：P0-4 **实际已修复** —— 有 body 无 token 时：

```json
POST /merchant → 401 {"success":false,"errorMsg":"请先登录"}
PUT  /merchant → 401 {"success":false,"errorMsg":"请先登录"}
```

### 3.3 修复 3（测试脚本）：秒杀 happy-path 幂等

**现象**：重复运行 `api-smoke.js` 时秒杀项报 `"Already purchased"`。

**根因**：同用户同 deal 的 Lua Set 幂等拦截，第二次运行自然失败 —— 属正确行为。

**修复**：`api-smoke.js` 与 `api-contract-test.js` 均改为幂等断言（成功 **或** 已购买均视为通过；契约测试遍历闪购券直至找到未购买活动）。

## 4. 关键契约验证记录

| 契约 | 结果 | 证据 |
|---|---|---|
| P0-4 写接口登录保护 | ✅ | 有 body 无 token → 401「请先登录」 |
| P0-1 Kafka 不阻塞主链路 | ✅ | 秒杀响应 98ms（Kafka 停机，异步发送仅 warn 日志） |
| orderId 字符串 | ✅ | `"77257811590905857"` / `"77258198137962498"`（JSON 字符串） |
| U3 登出 token 失效 | ✅ | 登出后旧 token 访问 `/user/me` → 401 |
| U8/U9 签到幂等 | ✅ | 重复签到天数不变（1→1） |
| M1 空值缓存穿透 | ✅ | 不存在 id → fail「店铺不存在」，未打 500 |
| M4/M5 降级路径 | ✅ | 无坐标/不存在类型 → success 空数据 |
| A2 空会话 | ✅ | 200 + 空会话结构（不 500） |

## 5. 遗留观察（非阻塞）

| 项 | 说明 | 处理 |
|---|---|---|
| 测试期间消耗 deal 10/12 各 1 单 | 幂等键已写入，后续秒杀回归需清理（05 文档 §2.3） | 模块 05 前清理 |
| P0-4 依赖 Controller 内自校验而非拦截器 | 属已知设计（/merchant/** 在公开路径），有回归脚本兜底 | 保留 |

## 6. 模块结论

**接口层通过**。鉴权矩阵、Result 契约、边界与降级路径均符合预期；P0-1/P0-4 两个历史 P0 缺陷复核通过；修复了全局异常提示与 2 个测试脚本缺陷。

> 改动文件：`WebExceptionAdvice.java`（+细化 handler）、`tools/api-smoke.js`、`tools/api-contract-test.js`（新增）、`tools/get-token.js`（新增）。
