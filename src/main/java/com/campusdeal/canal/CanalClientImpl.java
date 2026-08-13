package com.campusdeal.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry.Column;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.alibaba.otter.canal.protocol.Message;
import com.campusdeal.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.util.List;

/**
 * Canal 客户端实现：订阅 MySQL binlog，将变更转化为 Redis 缓存失效
 */
@Slf4j
@Component
public class CanalClientImpl implements CanalClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CanalConnectorFactory canalConnectorFactory;

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
            connector = canalConnectorFactory.create(
                    canalHost, canalPort, destination, "", "");
            try {
                connector.connect();
                connector.subscribe("campusdeal\\.tb_voucher,campusdeal\\.tb_shop,campusdeal\\.tb_seckill_voucher");
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

    /** 处理一批 binlog 事件（package-private 便于单元测试） */
    void handleEntries(List<Entry> entries) {
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

    /** 根据表名 + 变更行，删除对应 Redis 缓存 key（package-private 便于单元测试） */
    void invalidateCache(String table, RowData rowData) {
        // 获取变更后的行数据
        List<Column> columns = rowData.getAfterColumnsList();
        String id = columns.stream()
                .filter(c -> "id".equals(c.getName()))
                .findFirst()
                .map(Column::getValue)
                .orElse(null);
        if (id == null) return;

        switch (table) {
            case "tb_voucher":
            case "tb_seckill_voucher":
                // 秒杀券/库存变更 → 清除相关缓存
                stringRedisTemplate.delete(RedisConstants.CACHE_MERCHANT_KEY + id);
                stringRedisTemplate.delete(RedisConstants.FLASH_DEAL_STOCK_KEY + id);
                log.debug("Canal invalidated cache for voucher: {}", id);
                break;
            case "tb_shop":
                // 商户信息变更 → 清除商户缓存
                stringRedisTemplate.delete(RedisConstants.CACHE_MERCHANT_KEY + id);
                log.debug("Canal invalidated cache for merchant: {}", id);
                break;
            default:
                // 非关注表，忽略
                break;
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
