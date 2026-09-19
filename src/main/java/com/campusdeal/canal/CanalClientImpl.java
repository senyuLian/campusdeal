package com.campusdeal.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry.Column;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.alibaba.otter.canal.protocol.Message;
import com.campusdeal.utils.RedisConstants;
import com.campusdeal.config.ReliabilityMetrics;
import com.campusdeal.security.SensitiveLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.SmartLifecycle;

import jakarta.annotation.Resource;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Canal 客户端实现：订阅 MySQL binlog，将变更转化为 Redis 缓存失效
 *
 * <p>T7 修复：连接异常后不再「线程退出 + running 恒为 true 导致无法重启」，
 * 改为外层 while(running) 重连循环：失败后指数退避（默认 1s 起，上限 30s）自动重连；
 * stop() 置 running=false + interrupt + disconnect 让阻塞的 getWithoutAck 尽快返回。
 * 重连期间缓存失效中断，由 30s 逻辑过期兜底（降级可用）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "campusdeal.canal", name = "enabled", havingValue = "true")
public class CanalClientImpl implements CanalClient, SmartLifecycle {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CanalConnectorFactory canalConnectorFactory;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ReliabilityMetrics reliabilityMetrics;

    @Value("${campusdeal.canal.host:localhost}")
    private String canalHost = "localhost";
    @Value("${campusdeal.canal.port:11111}")
    private int canalPort = 11111;
    @Value("${campusdeal.canal.destination:example}")
    private String destination = "example";
    /** 重连基础退避（毫秒），第 N 次失败等待 N * base（封顶 30s） */
    @Value("${campusdeal.canal.reconnect-base-delay-ms:1000}")
    private long reconnectBaseDelayMs = 1000;

    private volatile boolean running = false;
    private volatile Thread canalThread;
    private volatile CanalConnector connector;
    private final AtomicLong reconnectCount = new AtomicLong();
    private final AtomicLong lastBatchSize = new AtomicLong();
    private final AtomicLong lastEventAt = new AtomicLong();

    @Override
    public void start() {
        synchronized (this) {
            if (running) return;
            running = true;
        }
        Thread t = new Thread(this::runLoop, "canal-client");
        canalThread = t;
        t.setDaemon(true);
        t.start();
    }

    /** 主循环：连接 → 消费 → 失败退避重连，直到 stop() 或被新线程取代 */
    private void runLoop() {
        int retryCount = 0;
        while (running && canalThread == Thread.currentThread()) {
            CanalConnector conn = null;
            try {
                conn = canalConnectorFactory.create(
                        canalHost, canalPort, destination, "", "");
                connector = conn;
                conn.connect();
                conn.subscribe("campusdeal\\.tb_voucher,campusdeal\\.tb_shop,campusdeal\\.tb_seckill_voucher");
                retryCount = 0;
                log.info("Canal client connected to {}:{}, destination={}",
                        canalHost, canalPort, destination);
                metric("connected");

                while (running) {
                    Message message = conn.getWithoutAck(1000);
                    if (isHeartbeatOrEmpty(message)) {
                        continue;
                    }
                    handleEntries(message.getEntries());
                    conn.ack(message.getId());
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    log.info("Canal client stopped by interrupt");
                    break;
                }
                if (!running || canalThread != Thread.currentThread()) {
                    break;
                }
                retryCount++;
                reconnectCount.incrementAndGet();
                metric("reconnect");
                long backoff = Math.min(reconnectBaseDelayMs * retryCount, 30_000L);
                log.warn("Canal client connection lost (retry #{}), reconnect in {}ms: {}",
                        retryCount, backoff, SensitiveLogSanitizer.exceptionSummary(e));
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignore) {
                        // 连接已断，disconnect 失败可忽略
                    }
                }
                if (connector == conn) {
                    connector = null;
                }
            }
        }
        log.info("Canal client thread exiting");
    }

    /** 心跳批（batchId=-1）或空批 → 不处理（package-private 便于单测） */
    boolean isHeartbeatOrEmpty(Message message) {
        return message == null || message.getId() == -1
                || message.getEntries() == null || message.getEntries().isEmpty();
    }

    /** 当前 canal 线程（package-private 便于单测断言线程身份/幂等） */
    Thread getCanalThread() {
        return canalThread;
    }

    /** 处理一批 binlog 事件（package-private 便于单元测试） */
    void handleEntries(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return;
        lastBatchSize.set(entries.size());
        lastEventAt.set(System.currentTimeMillis());
        metric("batch");
        for (Entry entry : entries) {
            if (entry.getEntryType() != EntryType.ROWDATA) continue;

            RowChange rowChange;
            try {
                rowChange = RowChange.parseFrom(entry.getStoreValue());
            } catch (Exception e) {
                // Do not acknowledge a batch whose row image cannot be
                // interpreted; Canal will redeliver it after reconnect.
                throw new IllegalStateException("Unable to parse Canal row event", e);
            }

            String table = entry.getHeader().getTableName();
            for (RowData rowData : rowChange.getRowDatasList()) {
                invalidateCache(table, rowChange.getEventType(), rowData);
            }
        }
    }

    /** 根据表名 + 变更行，删除对应 Redis 缓存 key（package-private 便于单元测试） */
    void invalidateCache(String table, RowData rowData) {
        // Backwards-compatible helper used by existing tests.
        invalidateCache(table, com.alibaba.otter.canal.protocol.CanalEntry.EventType.UPDATE, rowData);
    }

    void invalidateCache(String table, com.alibaba.otter.canal.protocol.CanalEntry.EventType eventType,
                         RowData rowData) {
        List<Column> columns = eventType == com.alibaba.otter.canal.protocol.CanalEntry.EventType.DELETE
                ? rowData.getBeforeColumnsList() : rowData.getAfterColumnsList();
        if (columns == null || columns.isEmpty()) {
            columns = rowData.getBeforeColumnsList();
        }
        String keyColumn = "tb_seckill_voucher".equals(table) ? "voucher_id" : "id";
        String id = columns.stream()
                .filter(c -> keyColumn.equals(c.getName()) || ("id".equals(c.getName()) && c.getIsKey()))
                .findFirst().map(Column::getValue).orElse(null);
        if (id == null) return;

            switch (table) {
            case "tb_voucher":
                // Voucher changes affect the merchant's derived coupon view.
                String shopId = columnValue(columns, "shop_id");
                stringRedisTemplate.delete("coupon:list:shop:" + (shopId == null ? id : shopId));
                log.debug("Canal invalidated voucher-derived views: {}", id);
                metric("invalidated");
                break;
            case "tb_seckill_voucher":
                // The durable acceptance path owns stock. Never delete its
                // authoritative live key as a generic cache reaction.
                stringRedisTemplate.delete("coupon:list:shop:" + id);
                log.debug("Canal invalidated flash-deal derived views: {}", id);
                metric("invalidated");
                break;
            case "tb_shop":
                stringRedisTemplate.delete(RedisConstants.CACHE_MERCHANT_KEY + id);
                log.debug("Canal invalidated merchant: {}", id);
                metric("invalidated");
                break;
            default:
                break;
        }
    }

    private String columnValue(List<Column> columns, String name) {
        return columns.stream().filter(c -> name.equals(c.getName()))
                .findFirst().map(Column::getValue).orElse(null);
    }

    @Override
    public void stop() {
        running = false;
        if (canalThread != null) canalThread.interrupt();
        // 断开当前连接，让阻塞的 getWithoutAck(1000) 尽快返回
        CanalConnector conn = connector;
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Exception ignore) {
                // 连接已断
            }
        }
    }

    @Override
    public CanalStatus getStatus() {
        return CanalStatus.builder()
                .running(running)
                .host(canalHost)
                .port(canalPort)
                .reconnectCount(reconnectCount.get())
                .lastBatchSize(lastBatchSize.get())
                .lastEventAt(lastEventAt.get())
                .build();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void metric(String outcome) {
        if (reliabilityMetrics != null) {
            reliabilityMetrics.increment("campusdeal.canal.events", outcome);
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }
}
