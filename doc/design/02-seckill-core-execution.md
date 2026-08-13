# 设计文档 02：秒杀核心执行模块

> 所属 Phase：Phase 2（秒杀全链路升级）
> 模块定位：秒杀链路的核心——Redis + Lua 原子库存扣减 + 分布式锁 + 全局 ID 生成
> 依赖关系：依赖 Redis 基础设施（StringRedisTemplate、RedissonClient），不依赖 Kafka/Canal

---

## 1. 模块概述

### 1.1 职责

| 组件 | 职责 |
|---|---|
| **Lua 脚本执行器** | 加载并执行 `seckill.lua`，在 Redis 服务端原子完成库存检查、重复检查、库存扣减 |
| **FlashDealServiceImpl** | 秒杀主流程编排：调用布隆过滤 → Caffeine → Lua → 返回结果 |
| **RedisIdWorker**（已有） | 生成 64 位全局唯一订单 ID（时间戳 32 位 + 序列号 32 位） |
| **分布式锁**（Redisson） | 防止同一用户并发重复下单（已被 Lua 的 `SISMEMBER` 替代，保留用于非秒杀场景） |

### 1.2 模块边界

```
                        ┌──────────────────────────────────┐
                        │  本模块                           │
  (来自缓存模块) → FlashDealServiceImpl.executeFlashDeal()  │
                        │   ├─ Redis+Lua 原子执行           │
                        │   ├─ 结果解析 (0/1/2)             │
                        │   └─ 成功 → 返回 orderId          │
                        └──────────────────────────────────┘
                                      │ (秒杀成功)
                                      ▼
                              (传递到 Kafka 异步模块)
```

### 1.3 与其他模块的关系

```
本模块 ← 依赖 BloomFilterService（缓存模块）
本模块 ← 依赖 Cache<Long, Boolean>（缓存模块）
本模块 ← 依赖 StringRedisTemplate（Redis）
本模块 → 不依赖 Kafka/Canal 模块
本模块 → 秒杀结果传递给 Phase 3 的 Kafka Producer 模块
```

---

## 2. 对外接口（API 契约）

### 2.1 Lua 脚本接口

```lua
-- seckill.lua
-- KEYS: (none) — 所有参数通过 ARGV 传入
-- ARGV[1]: dealId (秒杀活动 ID)
-- ARGV[2]: userId (用户 ID)
--
-- 返回值：
--   0 = 秒杀成功
--   1 = 库存不足
--   2 = 重复下单（该用户已购买此活动）
--
-- 涉及的 Redis Key：
--   flashdeal:stock:{dealId}    — String，库存数量
--   flashdeal:order:{dealId}    — Set，已下单用户 ID 集合
```

### 2.2 FlashDealServiceImpl 接口

```java
public interface IFlashDealService extends IService<FlashDeal> {
    /**
     * 执行秒杀（三层过滤 + Lua 原子扣减）
     * @param dealId 秒杀活动 ID
     * @return Result.data = orderId (成功时)
     */
    Result executeFlashDeal(Long dealId);

    /**
     * 获取秒杀活动详情（用于预热缓存）
     */
    FlashDeal getActiveDeal(Long dealId);

    /**
     * 预热秒杀库存到 Redis（管理员创建活动时调用）
     */
    void preloadStock(Long dealId, Integer stock);
}
```

### 2.3 RedisIdWorker 接口（已有，保持不变）

```java
public class RedisIdWorker {
    /**
     * 生成全局唯一 ID
     * @param keyPrefix 业务前缀（如 "order"）
     * @return 64 位长整型 ID
     *
     * ID 结构：
     * ┌──────────────────┬────────────────────┐
     * │  时间戳 (32 bit)  │  序列号 (32 bit)    │
     * │  距基准时间的秒数  │  Redis INCR 每日自增 │
     * └──────────────────┴────────────────────┘
     */
    public long getNextId(String keyPrefix);
}
```

### 2.4 调用示例

```java
// Controller 层
@PostMapping("seckill/{id}")
public Result seckillVoucher(@PathVariable("id") Long dealId) {
    return flashDealService.executeFlashDeal(dealId);
}
```

---

## 3. 数据模型

### 3.1 Lua 返回值枚举

```java
public enum FlashDealResult {
    SUCCESS(0, "秒杀成功"),
    OUT_OF_STOCK(1, "库存不足"),
    DUPLICATE_ORDER(2, "不能重复下单");

    private final int code;
    private final String message;

    public static FlashDealResult fromCode(long code) {
        for (FlashDealResult r : values()) {
            if (r.code == code) return r;
        }
        throw new IllegalArgumentException("Unknown result code: " + code);
    }
}
```

### 3.2 秒杀执行上下文

```java
@Data
@Builder
public class FlashDealContext {
    /** 秒杀活动 ID */
    private Long dealId;
    /** 用户 ID */
    private Long userId;
    /** 生成的订单 ID（Lua 成功后才生成） */
    private Long orderId;
    /** 执行开始时间（用于延迟监控） */
    private long startNanos;
    /** 各层耗时（用于压测分析） */
    private long bloomCostNs;
    private long caffeineCostNs;
    private long luaCostNs;
}
```

### 3.3 秒杀脚本配置

```java
@Configuration
public class LuaScriptConfig {

    @Bean
    public DefaultRedisScript<Long> flashDealScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
```

---

## 4. 核心类设计

### 4.1 类图

```
┌──────────────────────────────────┐
│     FlashDealServiceImpl         │
├──────────────────────────────────┤
│ - BloomFilterService bloomFilter │  ← 来自缓存模块
│ - Cache<Long,Boolean> stockCache │  ← 来自缓存模块
│ - StringRedisTemplate redis      │
│ - RedisIdWorker idWorker         │
│ - DefaultRedisScript<Long> script│
├──────────────────────────────────┤
│ + executeFlashDeal(id): Result   │  ← 核心方法
│ - executeLuaScript(context): int │
│ - handleResult(code): Result     │
└──────────────────────────────────┘
              │
              │ 调用
              ▼
┌──────────────────────────────────┐
│     DefaultRedisScript<Long>     │
│     (Spring Data Redis)          │
└──────────────────────────────────┘
              │
              │ 执行
              ▼
┌──────────────────────────────────┐
│     seckill.lua                  │
│     (Redis Server 端执行)         │
└──────────────────────────────────┘
```

### 4.2 FlashDealServiceImpl 实现要点

```java
@Service
public class FlashDealServiceImpl extends ServiceImpl<FlashDealMapper, FlashDeal>
        implements IFlashDealService {

    @Resource private BloomFilterService bloomFilter;
    @Resource private Cache<Long, Boolean> stockCache;
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private RedisIdWorker redisIdWorker;
    @Resource private DefaultRedisScript<Long> flashDealScript;

    @Override
    public Result executeFlashDeal(Long dealId) {
        FlashDealContext ctx = FlashDealContext.builder()
                .dealId(dealId)
                .userId(UserHolder.getUser().getId())
                .startNanos(System.nanoTime())
                .build();

        // === Layer 0: Bloom filter ===
        long t0 = System.nanoTime();
        if (!bloomFilter.mightContain(dealId)) {
            return Result.fail("Deal not found");
        }
        ctx.setBloomCostNs(System.nanoTime() - t0);

        // === Layer 1: Caffeine L1 ===
        long t1 = System.nanoTime();
        Boolean hasStock = stockCache.get(dealId, id ->
            stringRedisTemplate.opsForValue()
                .get(FLASH_DEAL_STOCK_KEY + id) != null
        );
        if (Boolean.FALSE.equals(hasStock)) {
            return Result.fail("Out of stock");
        }
        ctx.setCaffeineCostNs(System.nanoTime() - t1);

        // === Layer 2: Redis + Lua ===
        long t2 = System.nanoTime();
        int result = executeLuaScript(ctx);
        ctx.setLuaCostNs(System.nanoTime() - t2);

        return handleResult(result, ctx);
    }

    private int executeLuaScript(FlashDealContext ctx) {
        Long result = stringRedisTemplate.execute(
                flashDealScript,
                Collections.emptyList(),   // KEYS: 不使用
                ctx.getDealId().toString(),
                ctx.getUserId().toString()
        );
        return result != null ? result.intValue() : 1; // null = 脚本错误，视为无库存
    }

    private Result handleResult(int code, FlashDealContext ctx) {
        FlashDealResult fr = FlashDealResult.fromCode(code);
        switch (fr) {
            case SUCCESS:
                ctx.setOrderId(redisIdWorker.getNextId("order"));
                log.info("Flash deal success: dealId={}, userId={}, orderId={}, " +
                         "bloom={}ns, caffeine={}ns, lua={}ns",
                         ctx.getDealId(), ctx.getUserId(), ctx.getOrderId(),
                         ctx.getBloomCostNs(), ctx.getCaffeineCostNs(), ctx.getLuaCostNs());
                return Result.ok(ctx.getOrderId());

            case OUT_OF_STOCK:
                stockCache.put(ctx.getDealId(), false);  // 更新本地缓存
                return Result.fail("Out of stock");

            case DUPLICATE_ORDER:
                return Result.fail("Already purchased");

            default:
                return Result.fail("System error");
        }
    }

    @Override
    public void preloadStock(Long dealId, Integer stock) {
        stringRedisTemplate.opsForValue()
                .set(FLASH_DEAL_STOCK_KEY + dealId, stock.toString());
    }
}
```

### 4.3 分布式锁（保留用于非秒杀场景）

```java
// 注意：秒杀场景中，Lua 脚本的 SISMEMBER 已经实现了一人一单的互斥
// 分布式锁保留用于需要跨服务互斥的场景（如优惠券核销）

@Component
public class RedissonLockHelper {

    @Resource private RedissonClient redissonClient;

    /**
     * 尝试获取锁并执行
     * @param lockKey 锁的 key
     * @param waitTime 最大等待时间
     * @param leaseTime 锁持有时间（-1 = Watchdog 自动续期）
     * @param action 需要互斥执行的代码块
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                  Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS)) {
                return action.get();
            }
            throw new BusinessException("Failed to acquire lock: " + lockKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Lock interrupted: " + lockKey);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

---

## 5. 序列图

### 5.1 完整秒杀执行流程

```
  CouponOrderController   FlashDealServiceImpl   BloomFilter   Caffeine      Redis
        │                        │                   │             │            │
        │ executeFlashDeal(101)  │                   │             │            │
        │───────────────────────▶│                   │             │            │
        │                        │ mightContain(101) │             │            │
        │                        │──────────────────▶│             │            │
        │                        │      true         │             │            │
        │                        │◀──────────────────│             │            │
        │                        │                   │             │            │
        │                        │ stockCache.get(101)            │            │
        │                        │───────────────────────────────▶│            │
        │                        │          true (has stock)      │            │
        │                        │◀───────────────────────────────│            │
        │                        │                   │             │            │
        │                        │ execute(script, 101, userId)   │            │
        │                        │───────────────────────────────────────────▶│
        │                        │                   │             │  Lua:     │
        │                        │                   │             │  GET stock│
        │                        │                   │             │  SISMEMBER│
        │                        │                   │             │  INCRBY   │
        │                        │                   │             │  SADD     │
        │                        │         0 (SUCCESS)             │            │
        │                        │◀───────────────────────────────────────────│
        │                        │                   │             │            │
        │                        │ getNextId("order")              │            │
        │                        │────────────────────────────────────────────▶│
        │                        │          orderId=20240801000001│            │
        │                        │◀───────────────────────────────────────────│
        │                        │                   │             │            │
        │       Result.ok(orderId)                                      │
        │◀───────────────────────│                   │             │            │
```

### 5.2 Lua 脚本内部执行细节

```
   Redis Server (单线程)
   ┌────────────────────────────────────────────────┐
   │                                                │
   │ 1. local stock = GET "flashdeal:stock:101"     │
   │    → "100"                                     │
   │                                                │
   │ 2. if stock <= 0 → return 1                    │
   │    (100 > 0, 继续)                              │
   │                                                │
   │ 3. SISMEMBER "flashdeal:order:101" "1001"      │
   │    → 0 (用户未购买)                              │
   │                                                │
   │ 4. INCRBY "flashdeal:stock:101" -1             │
   │    → 99                                       │
   │                                                │
   │ 5. SADD "flashdeal:order:101" "1001"           │
   │    → 1                                        │
   │                                                │
   │ 6. return 0                                    │
   │                                                │
   │ ⚠️ 步骤 2-5 在单线程中原子执行，不会被其他请求打断 │
   └────────────────────────────────────────────────┘
```

---

## 6. 测试策略

### 6.1 测试原则

**全部使用 EmbedRedis（嵌入式 Redis Mock）或 Mock StringRedisTemplate，不依赖真实 Redis。**

### 6.2 测试用例清单

#### Lua 脚本测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| LU-01 | 正常秒杀 | stock=100, user 未购买 | 执行 Lua | 返回 0 |
| LU-02 | 库存不足 | stock=0 | 执行 Lua | 返回 1 |
| LU-03 | 重复下单 | stock=100, user 已在 order set 中 | 执行 Lua | 返回 2 |
| LU-04 | 最后一件库存 | stock=1, user 未购买 | 执行 Lua | 返回 0，stock 变为 0 |

#### FlashDealServiceImpl 测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| FD-01 | 布隆拒绝 | bloomFilter.mightContain → false | executeFlashDeal | 返回 "Deal not found"，不查缓存 |
| FD-02 | L1 缓存命中无库存 | stockCache.get → false | executeFlashDeal | 返回 "Out of stock"，不调 Lua |
| FD-03 | Lua 返回成功 | bloom→true, cache→true, lua→0 | executeFlashDeal | 返回 orderId |
| FD-04 | Lua 返回库存不足 | bloom→true, cache→true, lua→1 | executeFlashDeal | 返回 "Out of stock"，更新 Caffeine |
| FD-05 | Lua 返回重复 | bloom→true, cache→true, lua→2 | executeFlashDeal | 返回 "Already purchased" |

#### RedisIdWorker 测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| ID-01 | 连续生成 ID 递增 | Mock Redis INCR 返回 0,1,2 | 连续调用 3 次 | ID 单调递增 |
| ID-02 | 不同前缀独立计数 | — | 先后调用 "order" 和 "refund" | 两个前缀的 ID 序列独立 |

### 6.3 测试代码示例

```java
@ExtendWith(MockitoExtension.class)
class FlashDealServiceImplTest {

    @Mock private BloomFilterService bloomFilter;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private RedisIdWorker redisIdWorker;

    @InjectMocks private FlashDealServiceImpl service;

    // Mock Caffeine cache — using real Caffeine (内存，不需要 mock)
    private Cache<Long, Boolean> stockCache;

    @BeforeEach
    void setUp() {
        stockCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(1))
                .maximumSize(100)
                .build();
        // 注入到 service（如果使用 field injection，需用 ReflectionTestUtils）
        ReflectionTestUtils.setField(service, "stockCache", stockCache);

        // Mock UserHolder
        UserDTO mockUser = new UserDTO();
        mockUser.setId(1001L);
        UserHolder.saveUser(mockUser);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    @DisplayName("FD-01: 布隆过滤拒绝，不查缓存也不调 Lua")
    void shouldRejectOnBloomMiss() {
        when(bloomFilter.mightContain(999L)).thenReturn(false);

        Result result = service.executeFlashDeal(999L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("not found");
        verifyNoInteractions(stringRedisTemplate);  // 不调 Redis
    }

    @Test
    @DisplayName("FD-02: L1 缓存命中无库存，不调 Lua")
    void shouldRejectWhenCaffeineIndicatesNoStock() {
        when(bloomFilter.mightContain(101L)).thenReturn(true);
        stockCache.put(101L, false);  // 无库存

        Result result = service.executeFlashDeal(101L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("stock");
        verifyNoInteractions(stringRedisTemplate);  // 不调 Redis
    }
}
```

### 6.4 测试独立性说明

- Lua 脚本逻辑：通过**集成测试**验证（需要真实 Redis 或 Testcontainers Redis），单独一个测试类
- FlashDealServiceImpl 逻辑：全部 Mock，纯单元测试
- RedisIdWorker：Mock `StringRedisTemplate.opsForValue().increment()`，纯单元测试
- 三个组件的测试**互不依赖**，可以并行执行
