package com.campusdeal.mq;

import com.campusdeal.entity.CouponOrder;
import com.campusdeal.mapper.CouponOrderMapper;
import com.campusdeal.service.IdempotentService;
import com.campusdeal.service.OutboxService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kafka Consumer 单元测试（KC-01..04）
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
    @Mock private ConsumerRecord<String, FlashDealOrderMessage> record;

    @InjectMocks private FlashDealConsumer consumer;

    private FlashDealOrderMessage message() {
        return new FlashDealOrderMessage(1L, 1001L, 5L, 0L);
    }

    @Test
    @DisplayName("KC-01: 正常消费，落库 + Outbox PROCESSED + ack")
    void shouldProcessMessageSuccessfully() {
        when(record.value()).thenReturn(message());
        when(record.key()).thenReturn("1001");
        when(record.offset()).thenReturn(100L);
        when(idempotentService.tryMark(anyString())).thenReturn(true);
        when(couponOrderMapper.insert(any(CouponOrder.class))).thenReturn(1);

        consumer.onMessage(record, ack);

        verify(ack).acknowledge();
        verify(couponOrderMapper).insert(any(CouponOrder.class));
        verify(outboxService).record(anyString(), anyString(), eq(OutboxStatus.PROCESSED));
    }

    @Test
    @DisplayName("KC-02: 重复消息应跳过，不写 DB")
    void shouldSkipDuplicateMessage() {
        when(record.value()).thenReturn(message());
        when(idempotentService.tryMark(anyString())).thenReturn(false);

        consumer.onMessage(record, ack);

        verify(ack).acknowledge();
        verifyNoInteractions(couponOrderMapper);  // 不写数据库
    }

    @Test
    @DisplayName("KC-03: DB 插入失败，不 ack，Outbox=PENDING，重置幂等标记")
    void shouldRecordPendingOnFailure() {
        when(record.value()).thenReturn(message());
        when(record.key()).thenReturn("1001");
        when(record.offset()).thenReturn(100L);
        when(idempotentService.tryMark(anyString())).thenReturn(true);
        when(couponOrderMapper.insert(any(CouponOrder.class)))
                .thenThrow(new RuntimeException("db down"));

        consumer.onMessage(record, ack);

        verify(ack, never()).acknowledge();  // 不 ack → Kafka 重投
        verify(outboxService).record(anyString(), anyString(), eq(OutboxStatus.PENDING));
        verify(idempotentService).clearMark("order:dedup:1001:5");  // 重置供补偿重试
    }

    @Test
    @DisplayName("KC-04: DB DuplicateKey，幂等兜底，ack 且不记 PENDING")
    void shouldAckOnDuplicateKey() {
        when(record.value()).thenReturn(message());
        when(record.key()).thenReturn("1001");
        when(record.offset()).thenReturn(100L);
        when(idempotentService.tryMark(anyString())).thenReturn(true);
        when(couponOrderMapper.insert(any(CouponOrder.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        consumer.onMessage(record, ack);

        verify(ack).acknowledge();
        verify(outboxService, never()).record(anyString(), anyString(), eq(OutboxStatus.PENDING));
        verify(idempotentService, never()).clearMark(anyString());
    }

    @Test
    @DisplayName("KC-05: 补偿入口 processMessage 吞掉 DuplicateKeyException（幂等保护）")
    void shouldSwallowDuplicateKeyInCompensation() {
        when(idempotentService.tryMark(anyString())).thenReturn(true);
        when(couponOrderMapper.insert(any(CouponOrder.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        // 不应抛出异常（OB-03 的前提：补偿时订单已存在视为成功）
        consumer.processMessage(message());

        verify(couponOrderMapper).insert(any(CouponOrder.class));
    }

    @Test
    @DisplayName("KC-06: 补偿入口 processMessage 遇到非重复异常向上抛出")
    void shouldPropagateOtherExceptionsInCompensation() {
        when(idempotentService.tryMark(anyString())).thenReturn(true);
        when(couponOrderMapper.insert(any(CouponOrder.class)))
                .thenThrow(new RuntimeException("db down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.processMessage(message()))
                .isInstanceOf(RuntimeException.class);
    }
}
