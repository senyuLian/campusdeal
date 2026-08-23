package com.campusdeal.mq;

import com.campusdeal.entity.CouponOrder;
import com.campusdeal.mapper.CouponOrderMapper;
import com.campusdeal.service.IdempotentService;
import com.campusdeal.service.OutboxService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka Consumer 单元测试（批量消费，KB-01..06）
 *
 * <p>Mock IdempotentService / CouponOrderMapper / OutboxService / Acknowledgment，
 * 不依赖 Redis / MySQL / Kafka。</p>
 */
@ExtendWith(MockitoExtension.class)
class FlashDealConsumerTest {

    @Mock private IdempotentService idempotentService;
    @Mock private CouponOrderMapper couponOrderMapper;
    @Mock private OutboxService outboxService;
    @Mock private Acknowledgment ack;

    @InjectMocks private FlashDealConsumer consumer;

    private FlashDealOrderMessage message(long orderId) {
        return new FlashDealOrderMessage(orderId, 1001L, 5L, 0L);
    }

    private ConsumerRecord<String, FlashDealOrderMessage> rec(long orderId, int partition, long offset) {
        return new ConsumerRecord<>("flash-deal-orders", partition, offset, String.valueOf(1001L), message(orderId));
    }

    @Test
    @DisplayName("KB-01: 正常批量消费，批量落库 + ack，不写 Outbox")
    void shouldBatchInsertAndAck() {
        List<ConsumerRecord<String, FlashDealOrderMessage>> records = List.of(rec(1L, 0, 100), rec(2L, 1, 101));
        when(idempotentService.tryMarkBatch(anyList())).thenReturn(List.of(true, true));

        consumer.onMessage(records, ack);

        verify(ack).acknowledge();
        verify(couponOrderMapper).batchInsertIgnore(anyList());
        verify(outboxService, never()).record(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("KB-02: 全部重复，跳过且不写 DB，仍 ack")
    void shouldSkipAllDuplicates() {
        List<ConsumerRecord<String, FlashDealOrderMessage>> records = List.of(rec(1L, 0, 100));
        when(idempotentService.tryMarkBatch(anyList())).thenReturn(List.of(false));

        consumer.onMessage(records, ack);

        verify(ack).acknowledge();
        verify(couponOrderMapper, never()).batchInsertIgnore(anyList());
    }

    @Test
    @DisplayName("KB-03: 混合批次，仅插入首次处理的消息")
    void shouldInsertOnlyNewOnes() {
        List<ConsumerRecord<String, FlashDealOrderMessage>> records =
                List.of(rec(1L, 0, 100), rec(2L, 1, 101), rec(3L, 2, 102));
        when(idempotentService.tryMarkBatch(anyList())).thenReturn(List.of(true, false, true));

        consumer.onMessage(records, ack);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CouponOrder>> captor = ArgumentCaptor.forClass(List.class);
        verify(couponOrderMapper).batchInsertIgnore(captor.capture());
        assertEquals(2, captor.getValue().size());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("KB-04: 批量落库失败，重置标记 + 写 PENDING，不 ack")
    void shouldRecordPendingOnBatchFailure() {
        List<ConsumerRecord<String, FlashDealOrderMessage>> records = List.of(rec(1L, 0, 100), rec(2L, 1, 101));
        when(idempotentService.tryMarkBatch(anyList())).thenReturn(List.of(true, true));
        when(couponOrderMapper.batchInsertIgnore(anyList())).thenThrow(new RuntimeException("db down"));

        consumer.onMessage(records, ack);

        verify(ack, never()).acknowledge();  // 不 ack → Kafka 重投
        verify(outboxService, times(2)).record(anyString(), anyString(), eq(OutboxStatus.PENDING));
        verify(idempotentService, times(2)).clearMark(anyString());  // 重置供补偿重试
    }

    @Test
    @DisplayName("KB-07: Redis 去重不可用时降级为 DB-only，全部视为首次插入并 ack")
    void shouldFallbackToDbOnlyWhenRedisUnavailable() {
        List<ConsumerRecord<String, FlashDealOrderMessage>> records = List.of(rec(1L, 0, 100), rec(2L, 1, 101));
        when(idempotentService.tryMarkBatch(anyList()))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"));

        consumer.onMessage(records, ack);

        // 降级：不因 Redis 抖动中断消费，仍批量落库 + ack（INSERT IGNORE 在 DB 层去重）
        verify(ack).acknowledge();
        verify(couponOrderMapper).batchInsertIgnore(anyList());
        verify(outboxService, never()).record(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("KB-08: 补偿路径 clearMark 失败不逃逸，仍写 PENDING 且不 ack")
    void shouldNotPropagateWhenClearMarkFails() {
        List<ConsumerRecord<String, FlashDealOrderMessage>> records = List.of(rec(1L, 0, 100));
        when(idempotentService.tryMarkBatch(anyList())).thenReturn(List.of(true));
        when(couponOrderMapper.batchInsertIgnore(anyList())).thenThrow(new RuntimeException("db down"));
        doThrow(new RuntimeException("redis down")).when(idempotentService).clearMark(anyString());

        consumer.onMessage(records, ack);

        // clearMark 抛异常被吞掉，不影响写 PENDING 与「不 ack」的语义
        verify(ack, never()).acknowledge();
        verify(outboxService, times(1)).record(anyString(), anyString(), eq(OutboxStatus.PENDING));
    }

    @Test
    @DisplayName("KB-05: 补偿入口 processMessage 吞掉 DuplicateKeyException（幂等保护）")
    void shouldSwallowDuplicateKeyInCompensation() {
        when(idempotentService.tryMark(anyString())).thenReturn(true);
        when(couponOrderMapper.insert(any(CouponOrder.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        // 不应抛出异常（OB-03 的前提：补偿时订单已存在视为成功）
        consumer.processMessage(message(1L));

        verify(couponOrderMapper).insert(any(CouponOrder.class));
    }

    @Test
    @DisplayName("KB-06: 补偿入口 processMessage 遇到非重复异常向上抛出")
    void shouldPropagateOtherExceptionsInCompensation() {
        when(idempotentService.tryMark(anyString())).thenReturn(true);
        when(couponOrderMapper.insert(any(CouponOrder.class)))
                .thenThrow(new RuntimeException("db down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.processMessage(message(1L)))
                .isInstanceOf(RuntimeException.class);
    }
}
