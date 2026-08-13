package com.campusdeal.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalEntry.Column;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.CanalEntry.Header;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.campusdeal.utils.RedisConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Canal 缓存失效单元测试（CN-01..02）
 *
 * <p>直接调用 handleEntries 处理 binlog Entry（protobuf 构造），Mock Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
class CanalClientTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private CanalConnectorFactory canalConnectorFactory;

    @InjectMocks private CanalClientImpl client;

    /** 构造一条 ROWDATA 类型的 binlog Entry */
    private Entry buildRowDataEntry(String table, String id, EventType eventType) {
        Column idColumn = Column.newBuilder().setName("id").setValue(id).setIsKey(true).build();
        RowData rowData = RowData.newBuilder().addAfterColumns(idColumn).build();
        RowChange rowChange = RowChange.newBuilder()
                .setEventType(eventType)
                .addRowDatas(rowData)
                .build();
        Header header = Header.newBuilder().setTableName(table).build();
        return Entry.newBuilder()
                .setEntryType(EntryType.ROWDATA)
                .setHeader(header)
                .setStoreValue(rowChange.toByteString())
                .build();
    }

    @Test
    @DisplayName("CN-01: tb_voucher 更新，失效商户缓存与秒杀库存缓存")
    void shouldInvalidateCacheOnVoucherChange() {
        List<Entry> entries = List.of(
                buildRowDataEntry("tb_voucher", "5", EventType.UPDATE));

        client.handleEntries(entries);

        verify(stringRedisTemplate).delete(RedisConstants.CACHE_MERCHANT_KEY + "5");
        verify(stringRedisTemplate).delete(RedisConstants.FLASH_DEAL_STOCK_KEY + "5");
    }

    @Test
    @DisplayName("CN-02: 非关注表（tb_user）变更，Redis 无操作")
    void shouldIgnoreUnrelatedTable() {
        List<Entry> entries = List.of(
                buildRowDataEntry("tb_user", "1", EventType.UPDATE));

        client.handleEntries(entries);

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("CN-03: tb_shop 更新，失效商户缓存")
    void shouldInvalidateCacheOnShopChange() {
        List<Entry> entries = List.of(
                buildRowDataEntry("tb_shop", "9", EventType.UPDATE));

        client.handleEntries(entries);

        verify(stringRedisTemplate).delete(RedisConstants.CACHE_MERCHANT_KEY + "9");
    }

    @Test
    @DisplayName("CN-04: 非 ROWDATA 事件（TRANSACTION）被跳过")
    void shouldSkipNonRowDataEntry() {
        Header header = CanalEntry.Header.newBuilder().setTableName("tb_voucher").build();
        Entry txnEntry = Entry.newBuilder()
                .setEntryType(EntryType.TRANSACTIONBEGIN)
                .setHeader(header)
                .build();

        client.handleEntries(List.of(txnEntry));

        verifyNoInteractions(stringRedisTemplate);
    }
}
