package com.campusdeal.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalEntry.Column;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.CanalEntry.Header;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.alibaba.otter.canal.protocol.Message;
import com.campusdeal.config.ReliabilityMetrics;
import com.campusdeal.utils.RedisConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Canal 缓存失效单元测试（CN-01..06） + T7 自动重连（CN-R1..R3）
 *
 * <p>直接调用 handleEntries 处理 binlog Entry（protobuf 构造），Mock Redis；
 * 重连用例通过 Mock CanalConnectorFactory 验证连接失败后自动恢复。</p>
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

    private Entry buildDeleteEntry(String table, String voucherId) {
        Column idColumn = Column.newBuilder().setName("voucher_id").setValue(voucherId).setIsKey(true).build();
        RowData rowData = RowData.newBuilder().addBeforeColumns(idColumn).build();
        RowChange rowChange = RowChange.newBuilder()
                .setEventType(EventType.DELETE)
                .addRowDatas(rowData)
                .build();
        Header header = Header.newBuilder().setTableName(table).build();
        return Entry.newBuilder().setEntryType(EntryType.ROWDATA).setHeader(header)
                .setStoreValue(rowChange.toByteString()).build();
    }

    @Test
    @DisplayName("CN-01: tb_voucher 更新，失效券派生视图")
    void shouldInvalidateCacheOnVoucherChange() {
        List<Entry> entries = List.of(
                buildRowDataEntry("tb_voucher", "5", EventType.UPDATE));

        client.handleEntries(entries);

        verify(stringRedisTemplate).delete("coupon:list:shop:5");
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

    @Test
    @DisplayName("CN-05: after 列无 id → 跳过，不失效缓存")
    void shouldSkipWhenIdMissing() {
        RowData rowData = RowData.newBuilder()
                .addAfterColumns(Column.newBuilder().setName("name").setValue("x").build())
                .build();
        RowChange rowChange = RowChange.newBuilder()
                .setEventType(EventType.UPDATE)
                .addRowDatas(rowData)
                .build();
        Header header = Header.newBuilder().setTableName("tb_voucher").build();
        Entry entry = Entry.newBuilder()
                .setEntryType(EntryType.ROWDATA)
                .setHeader(header)
                .setStoreValue(rowChange.toByteString())
                .build();

        client.handleEntries(List.of(entry));

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("CN-07: DELETE 使用 before-image，且不删除秒杀库存权威 key")
    void shouldUseBeforeImageForDeleteWithoutTouchingStock() {
        client.handleEntries(List.of(buildDeleteEntry("tb_seckill_voucher", "9")));

        verify(stringRedisTemplate).delete("coupon:list:shop:9");
        verify(stringRedisTemplate, org.mockito.Mockito.never())
                .delete(RedisConstants.FLASH_DEAL_STOCK_KEY + "9");
    }

    @Test
    @DisplayName("CN-06: 心跳批（batchId=-1）视为空批；有数据批不视为心跳")
    void shouldTreatHeartbeatAsEmpty() {
        assertThat(client.isHeartbeatOrEmpty(new Message(-1L, Collections.emptyList()))).isTrue();
        assertThat(client.isHeartbeatOrEmpty(new Message(0L, Collections.emptyList()))).isTrue();
        assertThat(client.isHeartbeatOrEmpty(
                new Message(5L, List.of(buildRowDataEntry("tb_voucher", "5", EventType.UPDATE))))).isFalse();
    }

    @Test
    @DisplayName("CN-M1: 事件批次指标使用固定结果标签")
    void shouldRecordBoundedCanalTelemetry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(client, "reliabilityMetrics", new ReliabilityMetrics(registry));

        client.handleEntries(List.of(buildRowDataEntry("tb_voucher", "5", EventType.UPDATE)));

        assertThat(registry.get("campusdeal.canal.events")
                .tag("outcome", "batch").counter().count()).isEqualTo(1.0);
        assertThat(client.getStatus().getLastBatchSize()).isEqualTo(1L);
        assertThat(client.getStatus().getLastEventAt()).isPositive();
    }

    // ==================== T7：自动重连 ====================

    private void shortenBackoff() {
        ReflectionTestUtils.setField(client, "reconnectBaseDelayMs", 50L);
    }

    @Test
    @DisplayName("CN-R1: 连接失败后自动重连，无需手动重启")
    void shouldReconnectAfterConnectionFailure() throws Exception {
        shortenBackoff();
        CanalConnector badConn = mock(CanalConnector.class);
        doThrow(new RuntimeException("connect refused")).when(badConn).connect();

        CanalConnector goodConn = mock(CanalConnector.class);
        CountDownLatch connected = new CountDownLatch(1);
        doAnswer(inv -> { connected.countDown(); return null; }).when(goodConn).connect();
        // 心跳批：重连成功后主循环持续拉取但不处理（避免 strict-stub 告警）
        lenient().when(goodConn.getWithoutAck(1000))
                .thenReturn(new Message(-1L, Collections.emptyList()));

        AtomicInteger creates = new AtomicInteger();
        when(canalConnectorFactory.create(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> creates.getAndIncrement() == 0 ? badConn : goodConn);

        client.start();

        // 第一次连接失败 → 退避 50ms → 第二次连接成功
        assertThat(connected.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(creates.get()).isGreaterThanOrEqualTo(2);
        client.stop();
    }

    @Test
    @DisplayName("CN-R2: start 重复调用为幂等（不创建第二个线程）")
    void shouldBeIdempotentOnStart() {
        when(canalConnectorFactory.create(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(mock(CanalConnector.class));

        client.start();
        Thread first = client.getCanalThread();
        client.start();  // 第二次调用应直接返回

        // 等待运行中的线程完成至少一次连接尝试（证明 start()#2 未打断循环/未开第二个线程）
        verify(canalConnectorFactory, timeout(1000).atLeastOnce())
                .create(anyString(), anyInt(), anyString(), anyString(), anyString());
        assertThat(client.getCanalThread()).isSameAs(first);
        assertThat(client.getStatus().isRunning()).isTrue();
        client.stop();
    }

    @Test
    @DisplayName("CN-R3: stop 后可再次 start，创建新线程")
    void shouldRestartAfterStop() {
        // 不 stub factory：线程会以默认 null 连接器触发 NPE 重试，最终被 stop 终止
        client.start();
        Thread first = client.getCanalThread();
        client.stop();

        assertThat(client.getStatus().isRunning()).isFalse();

        client.start();
        Thread second = client.getCanalThread();

        assertThat(second).isNotSameAs(first);
        assertThat(client.getStatus().isRunning()).isTrue();
        client.stop();
    }
}
