# 设计文档 01：多级缓存与布隆过滤器模块

> 所属 Phase：Phase 2（秒杀全链路升级）
> 模块定位：秒杀请求的第一道和第二道防线——非法 ID 过滤 + 热点数据本地缓存保护
> 依赖关系：**不依赖 Phase 2 其他模块**，仅依赖已有的 Redis 基础设施和 IFlashDealService

---

## 1. 模块概述

### 1.1 职责

在秒杀链路的请求入口处，提供两级过滤：

| 层级 | 组件 | 职责 | 拦截效果 |
|---|---|---|---|
| L0 | **布隆过滤器** | 快速拒绝非法 dealId（不存在的秒杀活动 ID） | 拦截 80%+ 无效请求 |
| L1 | **Caffeine 本地缓存** | 缓存秒杀库存标记（有/无）和商户热点数据 | 99%+ 本地命中，零网络延迟 |

### 1.2 模块边界

```
                        ┌──────────────────────────────┐
                        │  本模块                        │
  请求 → 布隆过滤器 ──→ Caffeine L1 ──→ (通过，进入L2)  │
              │ 拦截非法ID    │ 命中                      │
              ▼              ▼                           │
          直接拒绝      直接返回缓存数据                   │
                        └──────────────────────────────┘
                                   │ 未命中
                                   ▼
                          (继续到 Redis L2 / MySQL)
```

### 1.3 与其他模块的关系

```
本模块 ← 只依赖 IFlashDealService (获取有效 dealId 列表)
本模块 ← 只依赖 StringRedisTemplate (读 Redis 库存)
本模块 → 不依赖任何 Phase 2 新增模块
本模块 → 被 FlashDealServiceImpl（秒杀执行模块）调用
```

---

## 2. 对外接口（API 契约）

### 2.1 BloomFilterService

```java
public interface BloomFilterService {
    /**
     * 检查某个秒杀活动 ID 是否可能存在
     * @param dealId 秒杀活动 ID
     * @return false = 一定不存在（可直接拒绝），true = 可能存在（继续后续校验）
     */
    boolean mightContain(Long dealId);

    /**
     * 获取当前布隆过滤器的统计信息（用于监控和面试展示）
     */
    BloomFilterStats getStats();
}
```

### 2.2 Caffeine 缓存

```java
// 对外暴露两个 Cache 实例，通过 Spring Bean 注入

// 秒杀库存标记缓存
Cache<Long, Boolean> stockCache();
// 键：dealId
// 值：true=有库存, false=无库存
// TTL：1 秒
// 最大容量：10000

// 商户热点数据缓存
Cache<Long, Merchant> merchantCache();
// 键：merchantId
// 值：Merchant 实体
// TTL：30 秒
// 最大容量：1000
```

### 2.3 调用示例

```java
// 在秒杀 Service 中使用
@Service
public class FlashDealServiceImpl {

    @Resource private BloomFilterService bloomFilter;
    @Resource private Cache<Long, Boolean> stockCache;

    public Result executeFlashDeal(Long dealId) {
        // Step 1: Bloom filter
        if (!bloomFilter.mightContain(dealId)) {
            return Result.fail("Deal not found");  // 一定不存在
        }
        // Step 2: Caffeine L1
        Boolean hasStock = stockCache.get(dealId, id -> {
            String stock = stringRedisTemplate.opsForValue()
                    .get(FLASH_DEAL_STOCK_KEY + id);
            return stock != null && Integer.parseInt(stock) > 0;
        });
        if (Boolean.FALSE.equals(hasStock)) {
            return Result.fail("Out of stock");
        }
        // Step 3: proceed to Redis+Lua...
    }
}
```

---

## 3. 数据模型

### 3.1 布隆过滤器统计

```java
@Data
@Builder
public class BloomFilterStats {
    /** 当前存储的元素数量（近似值） */
    private long approximateElementCount;
    /** 预期误判率 */
    private double expectedFpp;
    /** 内存占用（字节，近似值） */
    private long estimatedMemoryBytes;
    /** 最近一次重建时间 */
    private LocalDateTime lastRebuildTime;
    /** 已运行小时数 */
    private long uptimeHours;
}
```

### 3.2 Caffeine 缓存配置

```java
@Data
@ConfigurationProperties(prefix = "campusdeal.cache")
public class CacheProperties {
    /** stock 缓存 TTL（秒），默认 1 */
    private int stockTtlSeconds = 1;
    /** stock 缓存最大条目数，默认 10000 */
    private int stockMaxSize = 10000;
    /** merchant 缓存 TTL（秒），默认 30 */
    private int merchantTtlSeconds = 30;
    /** merchant 缓存最大条目数，默认 1000 */
    private int merchantMaxSize = 1000;
}
```

### 3.3 布隆过滤器配置

```java
@Data
@ConfigurationProperties(prefix = "campusdeal.bloom")
public class BloomProperties {
    /** 预期插入元素数量，默认 10000 */
    private int expectedInsertions = 10000;
    /** 误判率，默认 0.01 (1%) */
    private double fpp = 0.01;
    /** 定时重建周期（秒），默认 300（5 分钟） */
    private int rebuildIntervalSeconds = 300;
}
```

---

## 4. 核心类设计

### 4.1 类图

```
┌─────────────────────────────┐
│    BloomFilterService       │
├─────────────────────────────┤
│ - BloomFilter<Long> filter  │
│ - BloomFilterStats stats    │
│ - IFlashDealService svc     │
├─────────────────────────────┤
│ + mightContain(Long): bool  │
│ + getStats(): Stats         │
│ - rebuild(): void           │  ← @Scheduled 定时触发
│ - loadActiveDeals(): List   │
└─────────────────────────────┘
              │
              │ 使用
              ▼
┌─────────────────────────────┐
│   Guava BloomFilter<Long>   │  ← 外部依赖，非本模块类
└─────────────────────────────┘

┌─────────────────────────────┐
│      CaffeineConfig         │
├─────────────────────────────┤
│ + stockCache(): Cache       │  ← @Bean
│ + merchantCache(): Cache    │  ← @Bean
└─────────────────────────────┘
              │
              │ 创建
              ▼
┌─────────────────────────────┐
│  Caffeine Cache<Long, T>    │  ← 外部依赖，非本模块类
└─────────────────────────────┘
```

### 4.2 BloomFilterService 实现要点

```java
@Component
public class BloomFilterServiceImpl implements BloomFilterService, InitializingBean {

    @Resource private IFlashDealService flashDealService;

    private BloomFilter<Long> bloomFilter;
    private BloomFilterStats stats;

    // === 初始化：Spring 容器启动后立即构建 ===
    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    // === 定时重建：每 5 分钟全量重建 ===
    @Scheduled(fixedRateString = "${campusdeal.bloom.rebuild-interval-seconds:300}000")
    public void rebuild() {
        List<FlashDeal> activeDeals = flashDealService.list(
            Wrappers.<FlashDeal>lambdaQuery()
                .gt(FlashDeal::getEndTime, LocalDateTime.now())
        );
        bloomFilter = BloomFilter.create(
            Funnels.longFunnel(),
            activeDeals.size(),
            0.01  // FPP
        );
        activeDeals.forEach(d -> bloomFilter.put(d.getVoucherId()));

        stats = BloomFilterStats.builder()
            .approximateElementCount(bloomFilter.approximateElementCount())
            .expectedFpp(0.01)
            .estimatedMemoryBytes(activeDeals.size() * 10L) // 近似：每元素 ~10 bytes
            .lastRebuildTime(LocalDateTime.now())
            .build();
    }

    // === 核心方法：O(1) 时间复杂度 ===
    @Override
    public boolean mightContain(Long dealId) {
        return bloomFilter.mightContain(dealId);
    }
}
```

### 4.3 CaffeineConfig 实现要点

```java
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CaffeineConfig {

    @Bean
    public Cache<Long, Boolean> stockCache(CacheProperties props) {
        return Caffeine.newBuilder()
            .expireAfterWrite(props.getStockTtlSeconds(), TimeUnit.SECONDS)
            .maximumSize(props.getStockMaxSize())
            .recordStats()  // 开启统计，供 Actuator 暴露命中率
            .removalListener((key, value, cause) ->
                log.debug("Stock cache removal: dealId={}, cause={}", key, cause))
            .build();
    }

    @Bean
    public Cache<Long, Merchant> merchantCache(CacheProperties props) {
        return Caffeine.newBuilder()
            .expireAfterWrite(props.getMerchantTtlSeconds(), TimeUnit.SECONDS)
            .maximumSize(props.getMerchantMaxSize())
            .recordStats()
            .build();
    }
}
```

---

## 5. 序列图

### 5.1 布隆过滤器：启动加载流程

```
  Spring容器         BloomFilterService        IFlashDealService
     │                      │                        │
     │  afterPropertiesSet()│                        │
     │─────────────────────▶│                        │
     │                      │  list(active deals)    │
     │                      │───────────────────────▶│
     │                      │                        │──── DB 查询
     │                      │    List<FlashDeal>      │
     │                      │◀───────────────────────│
     │                      │                        │
     │                      │ BloomFilter.create()   │
     │                      │ bloomFilter.putAll()   │
     │                      │                        │
     │                      │ (每5分钟自动重复)       │
```

### 5.2 请求过滤流程（布隆 + Caffeine 串联）

```
  FlashDealServiceImpl   BloomFilterService   Caffeine<stock>   Redis        MySQL
        │                      │                    │             │            │
        │ mightContain(d1)     │                    │             │            │
        │─────────────────────▶│                    │             │            │
        │       false           │                    │             │            │
        │◀─────────────────────│                    │             │            │
        │ "Deal not found"     │                    │             │            │
        │                      │                    │             │            │
   ─ ─ ─ 另一个请求 ─ ─ ─ ─ ─                       │             │            │
        │                      │                    │             │            │
        │ mightContain(d2)     │                    │             │            │
        │─────────────────────▶│                    │             │            │
        │       true           │                    │             │            │
        │◀─────────────────────│                    │             │            │
        │                      │                    │             │            │
        │ stockCache.get(d2)   │                    │             │            │
        │──────────────────────────────────────────▶│             │            │
        │                      │       miss         │             │            │
        │                      │                    │ GET stock   │            │
        │                      │                    │────────────▶│            │
        │                      │                    │   "5"       │            │
        │                      │                    │◀────────────│            │
        │       true (5>0)     │                    │             │            │
        │◀──────────────────────────────────────────│             │            │
        │                      │                    │             │            │
        │ (继续进入 Redis+Lua 原子扣减)              │             │            │
```

---

## 6. 测试策略

### 6.1 测试原则

**全部使用 Mock，不依赖外部服务。** 布隆过滤器基于内存，Caffeine 是纯内存缓存，天然可单测。

### 6.2 测试用例清单

#### BloomFilterService 测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| BF-01 | 已加载的元素命中 | 布隆含 dealId=1,2,3 | `mightContain(1)` | `true` |
| BF-02 | 未加载的元素一定不存在 | 布隆含 dealId=1,2,3 | `mightContain(999)` | `false` |
| BF-03 | 启动时从空表加载 | `flashDealService.list()` 返回空列表 | `afterPropertiesSet()` | 布隆为空，`mightContain(any)` = `false` |
| BF-04 | 定时重建 | 初始含 dealId=1；重建时 service 返回 dealId=1,2 | `rebuild()` | 重建后 `mightContain(2)` = `true` |

```java
@ExtendWith(MockitoExtension.class)
class BloomFilterServiceTest {

    @Mock private IFlashDealService flashDealService;
    @InjectMocks private BloomFilterServiceImpl bloomFilter;

    @Test
    @DisplayName("BF-01: 已加载的元素应该命中")
    void shouldContainLoadedElement() {
        FlashDeal deal = new FlashDeal();
        deal.setVoucherId(1L);
        deal.setEndTime(LocalDateTime.now().plusDays(1));
        when(flashDealService.list(any())).thenReturn(List.of(deal));

        bloomFilter.afterPropertiesSet();

        assertThat(bloomFilter.mightContain(1L)).isTrue();
    }

    @Test
    @DisplayName("BF-02: 未加载的元素一定返回 false")
    void shouldNotContainUnloadedElement() {
        FlashDeal deal = new FlashDeal();
        deal.setVoucherId(1L);
        deal.setEndTime(LocalDateTime.now().plusDays(1));
        when(flashDealService.list(any())).thenReturn(List.of(deal));

        bloomFilter.afterPropertiesSet();

        assertThat(bloomFilter.mightContain(999L)).isFalse();
    }
}
```

#### Caffeine 缓存测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| CF-01 | 缓存命中 | 已 put(key=true) | `stockCache.getIfPresent(key)` | `true` |
| CF-02 | 缓存过期 | 已 put(key=true)，TTL 1s | 等待 1050ms 后 `getIfPresent` | `null` |
| CF-03 | 缓存 miss 时加载 | CacheLoader: key → Redis GET | `stockCache.get(key)` | 调用 loader，返回 Redis 值 |
| CF-04 | 容量限制 | maxSize=100 | put 101 个不同 key | 早期 key 被淘汰 |

```java
@Test
@DisplayName("CF-02: TTL 过期后缓存 miss")
void shouldExpireAfterTTL() throws InterruptedException {
    Cache<Long, Boolean> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(200))
            .build();
    cache.put(1L, true);
    assertThat(cache.getIfPresent(1L)).isTrue();

    Thread.sleep(250);
    assertThat(cache.getIfPresent(1L)).isNull();
}
```

### 6.3 集成测试（可选，不阻塞独立测试）

```java
@SpringBootTest
@DisplayName("BloomFilter + Caffeine 集成测试")
class CacheIntegrationTest {

    @Autowired private BloomFilterService bloomFilter;
    @Autowired private Cache<Long, Boolean> stockCache;

    @Test
    @DisplayName("完整过滤链路：Bloom miss → 直接拒绝")
    void shouldRejectOnBloomMiss() {
        // 布隆未加载 dealId 999
        assertThat(bloomFilter.mightContain(999L)).isFalse();
        // 不应该再查缓存
    }
}
```

### 6.4 测试独立性说明

- 布隆过滤器：纯内存数据结构，`IFlashDealService` 通过 Mock 注入
- Caffeine：纯内存缓存，不需要 Redis 或数据库
- 两个组件可以**完全独立测试**，互不依赖

---

## 附录：面试话术

> **"为什么布隆过滤器的误判率设 1%？"**
> 误判意味着一个合法请求被拒绝——用户看到"活动不存在"。但在秒杀场景下，一个不存在的 dealId 大概率是恶意请求（脚本刷接口），1% 的误伤率可以接受。Guava 的 BloomFilter 约每个元素占用 10 bytes，10000 个 deal 只占 100KB 内存——远小于把所有 dealId 存 HashSet（每个 Long 对象 24+ bytes，共 240KB+）。

> **"为什么 Caffeine 不直接缓存完整数据而是缓存布尔标记？"**
> 库存变化太频繁（每笔成功秒杀都在变）。缓存完整库存数值的话，1 秒 TTL 内可能有 10 笔秒杀已经成功，缓存值过期了。所以只缓存"有无库存"这个标记，"无库存"时直接拒绝，"有库存"时继续走 Redis+Lua 原子校验。把"精确值"的校验交给 Lua 脚本。

> **"Caffeine 和 Redis 的 TTL 怎么协调？"**
> Caffeine 1 秒，Redis 永久（逻辑过期）。Caffeine 只是 Redis 的"影子"——过期了也无所谓，下一笔请求自动从 Redis 回源。Redis 的数据才是权威来源。
