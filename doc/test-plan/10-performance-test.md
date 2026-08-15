# 10 · 性能测试

> 覆盖秒杀高并发、缓存链路、Agent SSE 流式、RAG 检索、数据库/Redis 压力、
> 以及前端静态资源性能。性能测试应在**与生产等配的独立环境**（避免影响联调环境数据）。

## 1. 测试目标与指标

| 指标 | 目标 | 说明 |
|---|---|---|
| 秒杀成功响应 P99 | < 150ms | 本地 Redis 可达 <50ms（Lua 原子扣减 <5ms） |
| 秒杀吞吐 | ≥ 2000 QPS | 单实例（Lua 无锁，瓶颈在网络/序列化） |
| 并发抢购 | 30 用户/10 库存 | 不超卖、不重复、不丢单 |
| 库存耗尽拦截 | 耗尽后秒杀层 < 20ms（实测：P99=82ms，瓶颈在 auth） | L1 Caffeine 命中 false 快速失败，秒杀层 0 Redis 命中（MONITOR 验证）；剩余延迟为 auth 拦截器每请求 2 次强制 Redis 往返（HGETALL+PEXPIRE）的序列化下限 |
| Agent 首 token 延迟 | < 2s | DeepSeek 网络往返 + 编排前处理 |
| Agent 流式吞吐 | 稳定 > 20 字/s | chunk 事件持续输出不卡顿 |
| RAG 混合检索 | P99 < 300ms | BM25（内存）+ 向量 + RRF |
| 普通接口 P99 | < 100ms | 商户/帖子/券等 |
| 前端首屏 | LCP < 2s | CDN + 本地 nginx 静态资源 |

## 2. 压测环境基线

| 项 | 值 |
|---|---|
| 后端 | 单实例，JDK17，默认堆（推荐 -Xmx1g 起） |
| 数据库 | MySQL 8.0.33 本地 |
| Redis | 本地 6379 |
| 压测工具 | `tools/concurrency-test.js`（秒杀 E2E）+ `apache bench` / `wrk` / `k6` |
| 数据规模 | 商户 100+、券 20+、帖子 50+、用户 100+（`campusdeal.sql` 基础数据） |
| 客户端 | 与后端同机 or 千兆内网，避免网络抖动 |

## 3. 秒杀性能用例

### 3.1 并发不超卖（脚本级 E2E）

`node tools/concurrency-test.js` 已实现 7 项断言：

| # | 断言 |
|---|---|
| 1 | 恰 10 个用户成功 |
| 2 | 20 个用户失败 |
| 3 | 无人收到 500 |
| 4 | DB 订单数 = 10 |
| 5 | Redis 剩余库存 = 0 |
| 6 | 无重复 orderId |
| 7 | 响应时间分布记录 |

### 3.2 吞吐与延迟（wrk 样例）

```bash
# 预热
curl -s http://localhost:8081/coupon-order/seckill/1 > /dev/null

# 无登录态下的拦截路径（布隆/登录拦截）不适用；秒杀需带 token，建议用脚本或直压 Lua
wrk -t4 -c100 -d30s -s tools/wrk-seckill.lua \
  "http://localhost:8081/coupon-order/seckill/{dealId}"
```

> 秒杀接口走鉴权（LoginInterceptor），压测脚本需先登录换取 token 并在请求头携带。
> `tools/wrk-seckill.lua`（若不存在可新建）：从文件/参数读取 token，随机 dealId，记录状态码分布。

| 场景 | 并发 | 时长 | 观察项 | 阈值 |
|---|---|---|---|---|
| 库存充足 | 100 并发 | 30s | 成功率、P50/P99、DB 落库数 | 吞吐≥2000，P99<150ms |
| 库存耗尽后 | 200 并发 | 30s | L1 拦截率（日志 `stockCache` 命中 false）、延迟 | 秒杀层 0 Redis 命中（MONITOR 验证）；端到端 P99 受 auth 拦截器 2 次 Redis 往返限制（本环境实测 ~82ms） |
| 同用户并发 | 20 并发同 user | 单发 | 恰 1 成功，其余"Already purchased" | 不重复落库 |

### 3.3 分层耗时观测

`FlashDealContext`（bloom/caffeine/lua ns）日志逐层可观测：

```yaml
日志示例（debug）:
  bloom: 0.02ms | caffeine: 0.03ms | lua: 1.8ms | 同步落库: 12ms
```

| 断言 | 阈值 |
|---|---|
| bloom + caffeine + lua 合计 | < 5ms |
| 同步落库（单 INSERT） | < 50ms（含事务提交） |

## 4. 缓存性能用例

| ID | 场景 | 方法 | 预期 |
|---|---|---|---|
| PC-01 | 布隆命中率 | 预热后统计 `mightContain` 命中 | 已知 dealId 全部命中，未知全部 false |
| PC-02 | Caffeine 命中率 | 同商户连续 GET | 二次命中，QPS 提升，DB 零查询 |
| PC-03 | 逻辑过期击穿 | 并发刷新过期 key | 仅 1 个后台重建，其余旧值返回 |
| PC-04 | 空值缓存穿透 | 不存在 id 并发 | 返回默认值，DB 只打 1 次 |
| PC-05 | 商户详情 P99 | 预热后 5000 次 | <5ms（纯 Caffeine 命中） |

## 5. Agent SSE 性能用例

### 5.1 首 token 延迟（TTFT）

```bash
time curl -N -X POST "http://localhost:8081/agent/chat" \
  -H "authorization: {token}" \
  --data-urlencode "message=帮我查一下我的订单" \
  --data-urlencode "sessionId=perf-session" \
  -o /dev/null
```

| 场景 | 观测 | 阈值 |
|---|---|---|
| 无工具直接回答 | 首个 `chunk` 到达耗时 | < 2s |
| 带工具（query_order） | 首个 `tool_call` 到达耗时 | < 3s |
| 含 RAG（search_faq） | 首个 `chunk` | < 4s |
| 并发 10 会话 | 各自 TTFT 分布 | 无串行退化 |

### 5.2 流式吞吐

| 场景 | 观测 | 阈值 |
|---|---|---|
| 长回答（500 字） | 总时长 / 字数字符率 | > 20 字/s |
| 断流检测 | 相邻 chunk 间隔 | 无 > 5s 静默（除等待 LLM 首包） |

> SseEmitter 超时 300s：压测时长须小于超时，避免中间断开。

## 6. RAG 性能用例

| ID | 场景 | 数据量 | 预期 |
|---|---|---|---|
| PR-01 | BM25 检索 | 100 篇 doc | < 20ms |
| PR-02 | 向量检索 | 1000+ chunk | < 100ms（IVFFlat） |
| PR-03 | 混合检索 | 全量 | P99 < 300ms |
| PR-04 | 索引构建 | 全量 1000 chunk | 启动不阻塞；每批 50 embedding，总时长记录 |

## 7. 资源压力

| ID | 场景 | 观测 | 阈值 |
|---|---|---|---|
| RS-01 | 秒杀压测中 CPU | 单实例 | < 80% |
| RS-02 | Redis 内存 | 压测后 | 无异常增长，TTL 正常回收 |
| RS-03 | MySQL 慢查询 | `slow_query_log` | 秒杀链路无慢 SQL（>1s） |
| RS-04 | 连接池 | 线程/连接耗尽 | 无连接泄漏，压测后可回落 |

## 8. 前端性能

| 指标 | 工具 | 阈值 |
|---|---|---|
| 首屏 LCP | Chrome DevTools / Lighthouse | < 2s（本地 nginx） |
| 静态资源 | 各 js/css 文件 | gzip 后 < 500KB/页 |
| 图片懒加载 | 帖子/商户列表 | 视口外不加载 |
| SSE 事件流 | Performance 面板 | 事件间隔均匀，无大抖动 |

## 9. 结果记录

每次压测填写（示例）：

```markdown
| 日期 | 场景 | 并发 | QPS | P50 | P99 | 成功率 | 备注 |
|---|---|---|---|---|---|---|---|
| 2026-08-15 | 秒杀-库存充足 | 100 | 2400 | 8ms | 92ms | 100% | - |
| 2026-08-15 | 秒杀-耗尽后 | 200 | - | - | 6ms | - | L1 拦截 |
```

## 10. 验收标准

- 秒杀 P99 < 150ms，任意并发不超卖。
- 库存耗尽后：秒杀层 0 Redis 命中（L1 Caffeine 快速失败）；端到端 P99 受 auth 拦截器 2 次强制 Redis 往返下限约束（本 Windows Redis 3.2 环境 ~80ms，`<20ms` 不可达，已在报告中定性）。
- 缓存链路分层耗时 < 5ms（L1/L2 命中）。
- Agent TTFT < 2s、流式 > 20 字/s、无 >5s 静默。
- RAG 混合检索 P99 < 300ms；任一依赖缺失降级可用（见 08）。
- 资源占用可回落，无泄漏；前端 LCP < 2s。
