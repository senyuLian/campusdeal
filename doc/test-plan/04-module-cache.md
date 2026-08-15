# 04 · 模块测试：多级缓存

> 覆盖布隆过滤器（L0）、Caffeine 本地缓存（L1）、Redis 逻辑过期/空值缓存（L2）、
> 缓存三大问题（穿透/击穿/雪崩）与缓存一致性。

## 1. 组件与职责

| 组件 | 类 | 职责 |
|---|---|---|
| 布隆过滤器 | `cache/BloomFilterServiceImpl` | Guava `BloomFilter<Long>`，拦截非法 dealId（L0） |
| 库存标记缓存 | `CaffeineConfig.stockCache` `Cache<Long,Boolean>` | dealId→有无库存，TTL=1s，max=10000 |
| 商户热点缓存 | `CaffeineConfig.merchantCache` `Cache<Long,Merchant>` | merchantId→实体，TTL=30s，max=1000 |
| 通用缓存工具 | `utils/CacheClient` | 空值缓存（穿透）、逻辑过期（击穿）、互斥锁重建 |
| 商户缓存 | Redis `cache:merchant:{id}` | 逻辑过期 `RedisData{data,expireTime}` 包装 |

## 2. 关键 Redis Key

| Key | 类型 | TTL | 用途 |
|---|---|---|---|
| `cache:merchant:{id}` | String | 30min（逻辑过期） | 商户详情 |
| `flashdeal:stock:{dealId}` | String | 活动期 | 秒杀库存（L1 loader 判 key 存在） |
| `lock:merchant:{id}` | String | 10s | 缓存重建互斥锁（CacheClient） |

## 3. 布隆过滤器用例

### 3.1 原理与数据来源

- 数据源：`FlashDealMapper` 查 `end_time > now` 的未过期 FlashDeal，插入 `voucherId`。
- 配置：`expectedInsertions`（默认 10000）、`fpp=0.01`（1%）、每 300s 定时全量重建。
- 语义：`mightContain=false` → 一定不存在（拒绝）；`true` → 可能存在（放行到下一层）。
- 误判只造成"合法请求多走一层"，不漏判；`mightContain(null)` 返回 false。

### 3.2 用例

| ID | 用例 | 前置 | 步骤 | 预期 |
|---|---|---|---|---|
| BF-01 | 已知活动命中 | 预热含 dealId=11 | `mightContain(11)` | true |
| BF-02 | 一定不存在 | 任意大 id | `mightContain(999999)` | false（绝大多数） |
| BF-03 | 空表未加载 | 无活动数据 | `mightContain(x)` | false（安全失败） |
| BF-04 | 空输入 | - | `mightContain(null)` | false |
| BF-05 | 重建识别新增 | 新增活动后等重建 | 重建后再查 | true |
| BF-06 | 启动 DB 不可用 | 断 DB 启动 | 应用启动成功 | 布隆为空，拦截所有秒杀，定时重建可恢复 |
| BF-07 | 定时重建 IT | 运行 300s | 观察日志 | 重建执行，stats 更新 |

### 3.3 已知问题

- **`BloomProperties` 未注入**（`BloomFilterServiceImpl` 内普通字段无 `@Autowired`）：
  yaml 的 `fpp/expected-insertions` 不生效，恒为默认值；仅重建周期（`fixedRateString`）生效。
  → 测试前需修复（T5），或用默认值口径验证。

### 3.4 现有测试

`cache/BloomFilterServiceTest`（BF-01..06，Mock FlashDealMapper）+ 无定时重建 IT。

## 4. Caffeine L1 用例

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| CF-01 | 命中 | 1s TTL 内两次 `stockCache.get` | 第二次不触发 loader |
| CF-02 | TTL 过期 | 等 >1s | 重新触发 loader |
| CF-03 | miss 回填 | 首次 get | loader 执行，回填 |
| CF-04 | 容量淘汰 | 插入 >10000 键 | 最旧淘汰（LRU） |
| CF-05 | 库存耗尽标记 | Lua 返回缺货后 | `stockCache.get` 命中 false |
| CF-06 | merchantCache 工厂 | 校验 Bean 配置 | TTL=30s、max=1000、recordStats |

> ⚠️ `stockCache` loader 判断的是 `flashdeal:stock:{id}` **key 是否存在**而非库存值>0。
> 语义差异需在测试中明确：库存被扣到 0 后 Redis key 仍在（值为 "0"）→ loader 返回 true，
> 精确判断由 Lua 承担。

## 5. CacheClient 缓存工具用例

### 5.1 空值缓存（穿透防御）

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| PT-01 | 命中缓存 | 有 `cache:merchant:{id}` | 直接返回 |
| PT-02 | 未命中且 DB 有 | 首次查 | 写 Redis，返回 |
| PT-03 | 未命中且 DB 无 | 查不存在 id | 写空值 `""` TTL=2min，返回 null |
| PT-04 | 空值命中 | 再次查不存在 id | 快速返回 null，不穿透 DB |

### 5.2 逻辑过期（击穿防御）

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| LE-01 | 未过期命中 | 返回缓存实体 | 不触发重建 |
| LE-02 | 未预热回源 | 缓存缺失 | 回源 DB 并重建逻辑过期缓存 |
| LE-03 | 已过期 | 并发读 | 仅一个线程 `tryLock` 成功并后台重建，其余返回旧值 |
| LE-04 | 重建失败 | DB 异常 | 返回旧值（缓存服务降级） |

### 5.3 雪崩

| ID | 用例 | 预期 |
|---|---|---|
| AV-01 | 大量 key 同时过期 | 逻辑过期下用户仍可读到旧值；重建分散到后台线程池（10 线程） |

## 6. 缓存一致性用例

| ID | 场景 | 步骤 | 预期 |
|---|---|---|---|
| CC-01 | 商户更新删缓存 | PUT /merchant 后 | `cache:merchant:{id}` 被 DEL |
| CC-02 | 双删兜底 | 更新后立即查询 | 首次回源 DB 重建新值 |
| CC-03 | Canal 失效（可选） | 直接改 DB → Canal 监听到 | 缓存被失效（见 06 文档） |
| CC-04 | 逻辑过期兜底 | Canal 未运行时改 DB | 最长 30s 内缓存自动失效重建 |

## 7. 验收标准

- 非法/不存在请求在 L0/L2 被拦截，DB 无压力。
- 热点商户读不穿透 DB（命中 L2/Caffeine）。
- 并发读热点 key 无重复查库（击穿防御）。
- 商户/库存更新后缓存最长 TTL 内一致。
- `CaffeineConfig`/`BloomFilterService` 配置按预期生效（修 T5 后）。
