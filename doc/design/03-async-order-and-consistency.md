# 设计文档 03：异步订单与数据一致性模块

> 所属 Phase：Phase 2（秒杀全链路升级）
> 模块定位：秒杀成功后的异步处理——Kafka 削峰、幂等去重、订单落库、Outbox 补偿、Canal 缓存同步
> 依赖关系：**不依赖 Phase 2 的缓存/布隆/Lua 模块**，仅依赖 MySQL 和 Redis 基础设施

---

## 1. 模块概述

### 1.1 职责

秒杀成功（Lua 返回 0）后，本模块负责将订单从"Redis 内存态"持久化到 MySQL，并保证数据最终一致性。

| 组件 | 职责 |
|---|---|
| **Kafka Producer** | 秒杀成功后将订单消息发送到 Kafka，解耦秒杀与持久化 |
| **Kafka Consumer** | 从 Kafka 拉取消息，匀速写入 MySQL，实现削峰填谷 |
| **幂等服务** | Redis SETNX 快速去重 + MySQL UNIQUE KEY 兜底 |
| **Outbox 服务** | 本地消息表记录每条消息的处理状态 |
| **补偿调度器** | 定时扫描 Outbox 中 PENDING/FAILED 消息，重试处理 |
| **Canal 客户端** | 监听 MySQL binlog 变更，自动失效 Redis 缓存 |

### 1.2 模块边界

```
  (来自秒杀执行模块)
         │ orderId, userId, dealId
         ▼
  ┌───────────────────────────────────────────────────┐
  │  本模块                                            │
  │                                                    │
  │  FlashDealProducer ──→ Kafka Topic                 │
  │  FlashDealConsumer ←── Kafka Topic                 │
  │       │                                            │
  │       ├─ IdempotentService (Redis SETNX)           │
  │       ├─ CouponOrderMapper (MySQL INSERT)          │
  │       └─ OutboxService (本地消息表)                 │
  │                                                    │
  │  OutboxScheduler (定时补偿)                         │
  │                                                    │
  │  CanalClient (binlog → 缓存失效)                    │
  └───────────────────────────────────────────────────┘
```

### 1.3 与其他模块的关系

```
本模块 ← 依赖 StringRedisTemplate、CouponOrderMapper（已有）
本模块 ← 依赖 KafkaTemplate、Kafka 基础设施
本模块 ← 依赖 Canal Java Client
本模块 → 不依赖缓存/布隆/Lua 模块
本模块 → 不依赖 Agent 模块
本模块 → 订单数据被 Agent 的 QueryOrderTool 读取
```

---

## 2. 对外接口（API 契约）

### 2.1 Kafka 消息体

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlashDealOrderMessage {
    /** 订单 ID（RedisIdWorker 生成） */
    private Long orderId;
    /** 用户 ID */
    private Long userId;
    /** 秒杀活动 ID */
    private Long dealId;
    /** 生成时间戳（毫秒） */
    private Long timestamp;
}
```

### 2.2 Kafka Producer 接口

```java
public interface FlashDealProducer {
    /**
     * 发送秒杀订单消息到 Kafka
     * @param message 订单消息
     * @return SendResult（包含 offset、partition 等信息）
     * @throws KafkaException 发送失败（配置了 retries=3，3 次后仍失败才抛）
     */
    SendResult<String, FlashDealOrderMessage> send(FlashDealOrderMessage message);
}
```

### 2.3 幂等服务接口

```java
public interface IdempotentService {
    /**
     * 检查并标记消息为"处理中"
     * @param dedupKey 去重键（格式：order:dedup:{userId}:{dealId}）
     * @return true = 首次处理（可以继续），false = 已处理过（应该跳过）
     */
    boolean tryMark(String dedupKey);

    /**
     * 清除去重标记（用于补偿重试时重置）
     */
    void clearMark(String dedupKey);
}
```

### 2.4 Outbox 服务接口

```java
public interface OutboxService {
    /** 记录一条待处理/已处理的消息 */
    void record(String messageId, String payload, OutboxStatus status);

    /** 查询需要补偿的消息 */
    List<Outbox> findPending(int limit);

    /** 更新消息状态 */
    void updateStatus(Long id, OutboxStatus status, Integer retryCount);
}
```

### 2.5 Canal 客户端接口

```java
public interface CanalClient {
    /** 启动 Canal 连接（在独立线程中运行） */
    void start();

    /** 停止 Canal 连接 */
    void stop();

    /** 获取当前连接状态 */
    CanalStatus getStatus();
}
```

---

## 3. 数据模型

### 3.1 Outbox 表结构

```sql
-- 本地消息表
CREATE TABLE outbox (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id      VARCHAR(128) NOT NULL COMMENT 'Kafka message key + offset',
    topic           VARCHAR(64)  NOT NULL COMMENT 'Kafka topic',
    payload         TEXT         NOT NULL COMMENT '消息体 JSON',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        COMMENT 'PENDING:待处理, PROCESSED:已处理, FAILED:失败',
    retry_count     INT          NOT NULL DEFAULT 0,
    error_msg       VARCHAR(500) COMMENT '最近一次错误信息',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_status_time (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.2 Outbox 实体

```java
@Data
@TableName("outbox")
public class Outbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private String topic;
    private String payload;
    private String status;       // PENDING / PROCESSED / FAILED
    private Integer retryCount;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

### 3.3 去重 Key 设计

```
格式：order:dedup:{userId}:{dealId}
TTL：1 小时（秒杀窗口过后自动清理）
存储：Redis String，值固定为 "1"

示例：order:dedup:1001:5 → "1"
```

---

## 4. 核心类设计

### 4.1 类图

```
┌─────────────────────────┐    ┌─────────────────────────┐
│  FlashDealProducer       │    │  FlashDealConsumer       │
├─────────────────────────┤    ├─────────────────────────┤
│ - KafkaTemplate<> tmpl   │    │ - IdempotentService ids  │
│ - String topic           │    │ - CouponOrderMapper mpr  │
├─────────────────────────┤    │ - OutboxService outbox   │
│ + send(msg): SendResult  │    ├─────────────────────────┤
└──────────┬──────────────┘    │ + onMessage(record,ack)  │
           │                    └──────────┬──────────────┘
           │ 发送                           │ 消费
           ▼                               ▼
    ┌─────────────┐              ┌─────────────────────┐
    │ Kafka Topic │              │  消费处理流水线       │
    │ flash-deal- │              │ 1.Redis 去重         │
    │ orders      │              │ 2.MySQL INSERT       │
    └─────────────┘              │ 3.Outbox 记录         │
                                 └─────────────────────┘

┌─────────────────────────┐    ┌─────────────────────────┐
│  OutboxScheduler         │    │  CanalClient             │
├─────────────────────────┤    ├─────────────────────────┤
│ - OutboxMapper mapper    │    │ - CanalConnector conn    │
│ - FlashDealConsumer cons │    │ - StringRedisTemplate rds│
├─────────────────────────┤    ├─────────────────────────┤
│ + compensate() : void    │    │ + start() : void         │
│ (每 30s 执行一次)        │    │ + stop() : void          │
└─────────────────────────┘    └─────────────────────────┘
```

### 4.2 Kafka Producer 实现

```java
@Component
@Slf4j
public class FlashDealProducerImpl implements FlashDealProducer {

    @Resource private KafkaTemplate<String, FlashDealOrderMessage> kafkaTemplate;
    @Value("${campusdeal.kafka.topic.flash-deal-orders}")
    private String topic;

    @Override
    public SendResult<String, FlashDealOrderMessage> send(FlashDealOrderMessage message) {
        // key = userId 保证同一用户的消息有序
        ProducerRecord<String, FlashDealOrderMessage> record =
                new ProducerRecord<>(topic, message.getUserId().toString(), message);
        record.headers().add("messageId", (topic + "-" + message.getOrderId()).getBytes());

        try {
            SendResult<String, FlashDealOrderMessage> result =
                    kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
            log.debug("Kafka send success: orderId={}, offset={}, partition={}",
                    message.getOrderId(), result.getRecordMetadata().offset(),
                    result.getRecordMetadata().partition());
            return result;
        } catch (Exception e) {
            log.error("Kafka send failed: orderId={}", message.getOrderId(), e);
            throw new KafkaException("Failed to send flash deal order message", e);
        }
    }
}
```

### 4.3 Kafka Consumer 实现

```java
@Component
@Slf4j
public class FlashDealConsumer {

    @Resource private IdempotentService idempotentService;
    @Resource private CouponOrderMapper couponOrderMapper;
    @Resource private OutboxService outboxService;

    @KafkaListener(
        topics = "${campusdeal.kafka.topic.flash-deal-orders}",
        groupId = "flash-deal-group",
        concurrency = "3"  // 3 个消费者线程
    )
    public void onMessage(ConsumerRecord<String, FlashDealOrderMessage> record,
                          Acknowledgment ack) {
        FlashDealOrderMessage msg = record.value();
        String dedupKey = buildDedupKey(msg);  // order:dedup:{userId}:{dealId}

        // === Step 1: 幂等检查 ===
        if (!idempotentService.tryMark(dedupKey)) {
            log.info("Duplicate message skipped: orderId={}", msg.getOrderId());
            ack.acknowledge();
            return;
        }

        try {
            // === Step 2: 订单落库 ===
            CouponOrder order = buildOrder(msg);
            couponOrderMapper.insert(order);

            // === Step 3: Outbox 记录 ===
            String messageId = record.key() + "-" + record.offset();
            outboxService.record(messageId, JSONUtil.toJsonStr(msg), OutboxStatus.PROCESSED);

            ack.acknowledge();
            log.info("Order persisted: orderId={}, userId={}, dealId={}",
                    msg.getOrderId(), msg.getUserId(), msg.getDealId());

        } catch (DuplicateKeyException e) {
            // MySQL UNIQUE KEY 冲突 = 幂等兜底
            log.warn("Duplicate key on insert, already processed: orderId={}", msg.getOrderId());
            ack.acknowledge();

        } catch (Exception e) {
            // 记录失败 → 不 ack → Kafka 重试
            // 同时写 Outbox PENDING 记录供补偿
            String messageId = record.key() + "-" + record.offset();
            outboxService.record(messageId, JSONUtil.toJsonStr(msg), OutboxStatus.PENDING);
            log.error("Failed to process order: orderId={}", msg.getOrderId(), e);
            // 不调用 ack.acknowledge()，消息会被重新消费
        }
    }

    private String buildDedupKey(FlashDealOrderMessage msg) {
        return String.format("order:dedup:%d:%d", msg.getUserId(), msg.getDealId());
    }

    private CouponOrder buildOrder(FlashDealOrderMessage msg) {
        CouponOrder order = new CouponOrder();
        order.setId(msg.getOrderId());
        order.setUserId(msg.getUserId());
        order.setVoucherId(msg.getDealId());
        order.setStatus(1);  // 已支付
        order.setCreateTime(LocalDateTime.now());
        return order;
    }
}
```

### 4.4 幂等服务实现

```java
@Component
public class IdempotentServiceImpl implements IdempotentService {

    @Resource private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean tryMark(String dedupKey) {
        // SETNX + TTL 原子操作
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", 1, TimeUnit.HOURS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void clearMark(String dedupKey) {
        stringRedisTemplate.delete(dedupKey);
    }
}
```

### 4.5 Outbox 调度器实现

```java
@Component
@Slf4j
public class OutboxScheduler {

    @Resource private OutboxMapper outboxMapper;
    @Resource private FlashDealConsumer flashDealConsumer;  // 复用消费者逻辑

    private static final int MAX_RETRY = 5;
    private static final int BATCH_SIZE = 100;

    @Scheduled(fixedDelay = 30_000)  // 每 30 秒
    public void compensate() {
        List<Outbox> pending = outboxMapper.selectList(
            Wrappers.<Outbox>lambdaQuery()
                .eq(Outbox::getStatus, "PENDING")
                .lt(Outbox::getCreateTime,
                    LocalDateTime.now().minusMinutes(1))  // 1 分钟前的消息才补偿
                .last("LIMIT " + BATCH_SIZE)
        );

        if (pending.isEmpty()) return;

        log.info("Outbox compensation: found {} pending messages", pending.size());

        for (Outbox msg : pending) {
            try {
                FlashDealOrderMessage orderMsg =
                        JSONUtil.toBean(msg.getPayload(), FlashDealOrderMessage.class);
                // 重试处理（幂等服务保证不会重复落库）
                flashDealConsumer.processMessage(orderMsg);
                outboxService.updateStatus(msg.getId(), OutboxStatus.PROCESSED, msg.getRetryCount());
            } catch (Exception e) {
                int newRetryCount = msg.getRetryCount() + 1;
                if (newRetryCount >= MAX_RETRY) {
                    outboxService.updateStatus(msg.getId(), OutboxStatus.FAILED, newRetryCount);
                    log.error("Outbox message FAILED after {} retries: messageId={}",
                            MAX_RETRY, msg.getMessageId(), e);
                } else {
                    outboxService.updateStatus(msg.getId(), OutboxStatus.PENDING, newRetryCount);
                }
            }
        }
    }
}
```

### 4.6 Canal 客户端实现

```java
@Component
@Slf4j
public class CanalClientImpl implements CanalClient {

    @Resource private StringRedisTemplate stringRedisTemplate;

    @Value("${campusdeal.canal.host:localhost}")
    private String canalHost;
    @Value("${campusdeal.canal.port:11111}")
    private int canalPort;
    @Value("${campusdeal.canal.destination:example}")
    private String destination;

    private volatile boolean running = false;
    private Thread canalThread;
    private CanalConnector connector;

    @Override
    public void start() {
        if (running) return;
        running = true;

        canalThread = new Thread(() -> {
            connector = CanalConnectors.newSingleConnector(
                    new InetSocketAddress(canalHost, canalPort),
                    destination, "", "");
            try {
                connector.connect();
                connector.subscribe("campusdeal\\.tb_voucher,campusdeal\\.tb_shop");  // 订阅表
                log.info("Canal client connected to {}:{}, destination={}",
                        canalHost, canalPort, destination);

                while (running) {
                    Message message = connector.getWithoutAck(1000);
                    long batchId = message.getId();
                    if (batchId == -1 || message.getEntries().isEmpty()) {
                        continue;
                    }
                    handleEntries(message.getEntries());
                    connector.ack(batchId);
                }
            } catch (Exception e) {
                log.error("Canal client error", e);
            } finally {
                if (connector != null) connector.disconnect();
            }
        }, "canal-client");
        canalThread.setDaemon(true);
        canalThread.start();
    }

    private void handleEntries(List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry.getEntryType() != EntryType.ROWDATA) continue;

            RowChange rowChange;
            try {
                rowChange = RowChange.parseFrom(entry.getStoreValue());
            } catch (Exception e) {
                continue;
            }

            String table = entry.getHeader().getTableName();
            for (RowData rowData : rowChange.getRowDatasList()) {
                invalidateCache(table, rowData);
            }
        }
    }

    private void invalidateCache(String table, RowData rowData) {
        // 获取变更后的行数据
        List<Column> columns = rowData.getAfterColumnsList();
        String id = columns.stream()
                .filter(c -> "id".equals(c.getName()))
                .findFirst()
                .map(Column::getValue)
                .orElse(null);
        if (id == null) return;

        if ("tb_voucher".equals(table)) {
            // 秒杀券变更 → 清除相关缓存
            stringRedisTemplate.delete(RedisConstants.CACHE_MERCHANT_KEY + id);
            stringRedisTemplate.delete(RedisConstants.FLASH_DEAL_STOCK_KEY + id);
            log.debug("Canal invalidated cache for voucher: {}", id);
        } else if ("tb_shop".equals(table)) {
            stringRedisTemplate.delete(RedisConstants.CACHE_MERCHANT_KEY + id);
            log.debug("Canal invalidated cache for merchant: {}", id);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (canalThread != null) canalThread.interrupt();
    }

    @Override
    public CanalStatus getStatus() {
        return CanalStatus.builder()
                .running(running)
                .host(canalHost)
                .port(canalPort)
                .build();
    }
}
```

---

## 5. 序列图

### 5.1 秒杀成功 → 异步落库完整流程

```
  FlashDealService  Producer  Kafka    Consumer  Idempotent Redis  MySQL
       │               │        │         │           │        │     │
       │ send(msg)      │        │         │           │        │     │
       │───────────────▶│        │         │           │        │     │
       │               │ produce │         │           │        │     │
       │               │────────▶│         │           │        │     │
       │     SendResult │◀───────│         │           │        │     │
       │◀───────────────│        │         │           │        │     │
       │               │        │         │           │        │     │
       │  Result.ok(orderId)    │         │           │        │     │
       │               │        │         │           │        │     │
   ─ ─ ─ ─ 异步边界 ─ ─ ─      │         │           │        │     │
       │               │        │  poll() │           │        │     │
       │               │        │◀────────│           │        │     │
       │               │        │  msg    │           │        │     │
       │               │        │────────▶│           │        │     │
       │               │        │         │ tryMark() │        │     │
       │               │        │         │──────────────────▶│     │
       │               │        │         │    true            │     │
       │               │        │         │◀──────────────────│     │
       │               │        │         │           │        │     │
       │               │        │         │ INSERT    │        │     │
       │               │        │         │──────────────────────────▶│
       │               │        │         │    success             │   │
       │               │        │         │◀──────────────────────────│
       │               │        │         │           │        │     │
       │               │        │         │ Outbox.record()     │     │
       │               │        │         │──────────────────────────▶│
       │               │        │         │           │        │     │
       │               │        │   ack    │           │        │     │
       │               │        │◀────────│           │        │     │
```

### 5.2 Outbox 补偿流程（失败场景）

```
  OutboxScheduler         MySQL                Consumer
       │                     │                     │
       │ select PENDING       │                     │
       │ (created > 1min ago) │                     │
       │─────────────────────▶│                     │
       │    List<Outbox>      │                     │
       │◀─────────────────────│                     │
       │                     │                     │
       │  for each msg:      │                     │
       │  processMessage()   │                     │
       │─────────────────────────────────────────▶│
       │                     │                     │── 幂等检查 (Redis)
       │                     │                     │── 订单落库
       │                     │                     │── Outbox 更新
       │    success / error  │                     │
       │◀─────────────────────────────────────────│
       │                     │                     │
       │  update status      │                     │
       │  (PROCESSED/FAILED) │                     │
       │─────────────────────▶│                     │
```

### 5.3 Canal 缓存同步流程

```
  MySQL          Canal Server      CanalClient         Redis
    │                 │                 │                 │
    │ INSERT/UPDATE   │                 │                 │
    │────────────────▶│                 │                 │
    │  binlog event   │                 │                 │
    │                 │  subscribe      │                 │
    │                 │◀────────────────│                 │
    │                 │  RowChange msg  │                 │
    │                 │────────────────▶│                 │
    │                 │                 │                 │
    │                 │                 │ parse table+id  │
    │                 │                 │                 │
    │                 │                 │ DELETE cache    │
    │                 │                 │────────────────▶│
    │                 │                 │                 │
    │                 │           ack   │                 │
    │                 │◀────────────────│                 │
```

---

## 6. 测试策略

### 6.1 测试清单

#### Kafka Producer 测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| KP-01 | 正常发送 | Kafka Mock | send(message) | 返回 SendResult，含 offset |
| KP-02 | 发送超时 | Kafka Mock 模拟超时 | send(message) | 抛出 KafkaException |
| KP-03 | 消息体序列化 | — | send(message) | JSON 序列化正确，字段完整 |

#### Kafka Consumer 测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| KC-01 | 正常消费 | 幂等返回 true，DB 插入成功 | onMessage | ack 被调用，状态 PROCESSED |
| KC-02 | 重复消息 | 幂等返回 false | onMessage | ack 直接调用，不写 DB |
| KC-03 | DB 插入失败 | 幂等返回 true，DB 抛异常 | onMessage | ack 未调用，Outbox=PENDING |
| KC-04 | DB DuplicateKey | 幂等返回 true，DB DuplicateKeyException | onMessage | ack 被调用，日志 warn |

#### 幂等服务测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| ID-01 | 首次标记 | Redis key 不存在 | tryMark(key) | true |
| ID-02 | 重复标记 | Redis key 已存在 | tryMark(key) | false |
| ID-03 | TTL 过期后 | key 存在但已过期 | tryMark(key) | true |

#### Outbox 补偿测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| OB-01 | 正常补偿 | 3 条 PENDING（超过1分钟） | compensate() | 3 条都变为 PROCESSED |
| OB-02 | 超过最大重试 | 1 条 PENDING，retry_count=5 | compensate() | 变为 FAILED |
| OB-03 | 补偿时幂等冲突 | PENDING 消息对应的订单已存在 | compensate() | 变为 PROCESSED（幂等保护） |

#### Canal 测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| CN-01 | voucher 表更新 | binlog: UPDATE tb_voucher SET stock=0 | handleEntries | Redis 对应 key 被删除 |
| CN-02 | 非关注表变更 | binlog: UPDATE tb_user | handleEntries | Redis 无操作 |

### 6.2 测试代码示例

```java
@ExtendWith(MockitoExtension.class)
class FlashDealConsumerTest {

    @Mock private IdempotentService idempotentService;
    @Mock private CouponOrderMapper couponOrderMapper;
    @Mock private OutboxService outboxService;
    @Mock private Acknowledgment ack;
    @Mock private ConsumerRecord<String, FlashDealOrderMessage> record;

    @InjectMocks private FlashDealConsumer consumer;

    @Test
    @DisplayName("KC-02: 重复消息应跳过")
    void shouldSkipDuplicateMessage() {
        FlashDealOrderMessage msg = new FlashDealOrderMessage(1L, 1001L, 5L, 0L);
        when(record.value()).thenReturn(msg);
        when(idempotentService.tryMark(anyString())).thenReturn(false);

        consumer.onMessage(record, ack);

        verify(ack).acknowledge();
        verifyNoInteractions(couponOrderMapper);  // 不写数据库
    }

    @Test
    @DisplayName("KC-01: 正常消费流程")
    void shouldProcessMessageSuccessfully() {
        FlashDealOrderMessage msg = new FlashDealOrderMessage(1L, 1001L, 5L, 0L);
        when(record.value()).thenReturn(msg);
        when(record.key()).thenReturn("1001");
        when(record.offset()).thenReturn(100L);
        when(idempotentService.tryMark(anyString())).thenReturn(true);
        when(couponOrderMapper.insert(any())).thenReturn(1);

        consumer.onMessage(record, ack);

        verify(ack).acknowledge();
        verify(couponOrderMapper).insert(any(CouponOrder.class));
        verify(outboxService).record(anyString(), anyString(), eq(OutboxStatus.PROCESSED));
    }
}
```

### 6.3 测试独立性说明

```
测试隔离设计：

Producer   ← Mock KafkaTemplate        → 不依赖 Kafka 服务
Consumer   ← Mock IdempotentService    → 不依赖 Redis
           ← Mock CouponOrderMapper    → 不依赖 MySQL
           ← Mock OutboxService        → 不依赖 MySQL
           ← Mock Acknowledgment       → 不依赖 Kafka

幂等服务   ← Mock StringRedisTemplate  → 不依赖 Redis

Outbox 补偿 ← Mock OutboxMapper        → 不依赖 MySQL
           ← Mock Consumer             → 不依赖其他模块

Canal 客户端 ← 需要集成测试（或 Mock CanalConnector）
```

> **Canal 的特殊说明**：Canal 客户端强依赖外部 Canal Server，单元测试通过 Mock `CanalConnector` 实现。完整的 binlog 订阅→缓存失效链路通过集成测试验证（需 Docker Canal）。
