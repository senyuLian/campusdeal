# 测试报告 04 · 模块测试：多级缓存

> 日期：2026-08-15 ｜ 依据：`doc/test-plan/04-module-cache.md`
> 环境：MySQL :3306 ✅、Redis :6379 ✅、后端 :8081 ✅

## 1. 执行范围与结果

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `BloomFilterServiceTest` | BF-01..06（布隆过滤器，Mock） | ✅ 6/6 |
| `CaffeineCacheTest` | CF-01..06（Caffeine L1 缓存） | ✅ 6/6 |
| `CacheClientTest`（新增） | PT-01..04 穿透空值缓存 + LE-01..04 逻辑过期 | ✅ 8/8 |
| `BloomConfigInjectionIT`（新增） | T5 配置绑定生效（Spring 上下文） | ✅ 1/1 |
| `tools/cache-verify.js`（新增） | BF L0 拦截 / LE 逻辑过期包装 / CC 一致性 | ✅ 7/7 |

**合计：单元 21/21 + IT 1/1 + 运行时 7/7 通过。**

> 注：全量单测 136 个中 3 个失败为**既有 T1/T2**（`FlashDealProducerTest` ×2、`FlashDealServiceImplTest` ×1，秒杀/MQ 模块），与本次缓存改动无关，计划在模块 05 修复。

## 2. 测试中发现并修复的问题

### 2.1 修复 1（T5 · BloomProperties 配置未注入）

**现象**：yaml 的 `campusdeal.bloom.fpp / expected-insertions` 不生效，恒为默认值（`BloomProperties` 默认 0.01 / 10000）。

**根因**：`BloomFilterServiceImpl` 中 `private BloomProperties properties = new BloomProperties();` 为普通字段初始化，Spring 不注入 `@ConfigurationProperties` 绑定的配置 Bean。且 `rebuild()` 中 `expectedInsertions = Math.max(activeDeals.size(), 1)` 直接用活动数，即使注入配置值也未被使用。

**修复**（`src/main/java/com/campusdeal/cache/BloomFilterServiceImpl.java`）：
- `properties` 字段加 `@Resource` 注入；
- `expectedInsertions = max(activeDeals.size(), max(properties.getExpectedInsertions(), 1))`，配置值决定容量基线、活动数超配时不致误判率飙升。

**验证**：新增 `BloomConfigInjectionIT`，用 `@TestPropertySource` 覆盖 `fpp=0.002 / expected-insertions=50`，断言 `stats.expectedFpp == 0.002` 且内存估算 ≥ 500B。**通过**。

### 2.2 修复 2（T13 · CacheClient 穿透路径 JSON 反序列化失效）

**现象**：`queryWithPassThrough` 缓存命中时返回**字段全为 null 的空实体**（测试 PT-01 捕获：期望 name=「食堂」，实得 null）。

**根因**：`BeanUtil.toBean(rJson, type)` 不解析 JSON 字符串 —— Hutool 的 `BeanUtil.toBean(String, Class)` 将字符串当标量处理，不会把 JSON 反序列化为对象。该路径当前对 merchant 未启用（商户走逻辑过期），但任何使用穿透缓存的业务命中即返回空数据。

**修复**（`src/main/java/com/campusdeal/utils/CacheClient.java`）：`BeanUtil.toBean(rJson, type)` → `JSONUtil.toBean(rJson, type)`，正确反序列化。

**验证**：`CacheClientTest` PT-01..04 全绿。

## 3. 关键场景记录

| 场景 | 结果 |
|---|---|
| BF L0：`POST /coupon-order/seckill/999999` → **13ms** 内被布隆拦截（`Deal not found`，未触 Redis/DB） | ✅ |
| BF L0：真实 `dealId=11` 放行（进入后续层，返回 Already purchased） | ✅ |
| LE L2：`GET /merchant/1` 后 Redis `cache:merchant:1` 为逻辑过期包装（含 `expireTime`） | ✅ |
| LE L2：二次查询命中缓存，数据一致 | ✅ |
| CC-01：`PUT /merchant`（回写原值）→ `cache:merchant:1` 被删除 | ✅ |
| CC-02：更新后再次查询 → 缓存重建 | ✅ |
| 穿透空值缓存：PT-01 命中返回实体 / PT-02 miss+DB 有写缓存 / PT-03 miss+DB 无写空值 / PT-04 空值命中不穿 DB | ✅ |
| 击穿逻辑过期：LE-01 未过期直接返回 / LE-02 miss 回源重建 / LE-03 过期获锁异步重建返回旧值并释放锁 / LE-04 重建失败返回旧值 | ✅ |

## 4. 遗留观察

| 项 | 说明 |
|---|---|
| 全量单测 3 个失败（T1/T2） | `FlashDealProducerTest` ×2 + `FlashDealServiceImplTest` ×1，既有问题，模块 05 处理 |
| merchant 详情走逻辑过期，未接线穿透空值缓存 | `queryWithPassThrough` 为可用实现（已单测修复），当前商户场景使用逻辑过期路径，符合击穿防御取舍 |
| BF 定时重建周期（300s）未在测试中等候 | 由 `fixedRateString` 生效；BF-07 定时重建可归入性能/回归模块观测 |

## 5. 模块结论

**多级缓存层通过。** L0 布隆（13ms 拦截非法请求）、L1 Caffeine（TTL/容量/统计配置验证）、L2 逻辑过期（击穿防御 + 并发重建 + 失败降级）、穿透空值缓存（修复 JSON 反序列化缺陷）与缓存一致性（更新删缓存）全部验证通过；T5 配置绑定修复并由 IT 覆盖。

> 改动文件：`src/main/java/com/campusdeal/cache/BloomFilterServiceImpl.java`、`src/main/java/com/campusdeal/utils/CacheClient.java`；
> 新增测试：`src/test/java/com/campusdeal/cache/BloomConfigInjectionIT.java`、`src/test/java/com/campusdeal/utils/CacheClientTest.java`；新增脚本 `tools/cache-verify.js`。
