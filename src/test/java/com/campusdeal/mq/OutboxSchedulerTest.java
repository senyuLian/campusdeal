package com.campusdeal.mq;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.campusdeal.entity.Outbox;
import com.campusdeal.mapper.OutboxMapper;
import com.campusdeal.service.OutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 补偿调度器单元测试（OB-01..03）
 *
 * <p>Mock OutboxMapper / OutboxService / FlashDealConsumer，不依赖 MySQL。</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxSchedulerTest {

    @Mock private OutboxMapper outboxMapper;
    @Mock private OutboxService outboxService;
    @Mock private FlashDealConsumer flashDealConsumer;

    @InjectMocks private OutboxScheduler scheduler;

    private Outbox pendingOutbox(Long id, int retryCount) {
        Outbox outbox = new Outbox();
        outbox.setId(id);
        outbox.setStatus("PENDING");
        outbox.setRetryCount(retryCount);
        outbox.setPayload("{\"orderId\":1,\"userId\":1001,\"dealId\":5,\"timestamp\":0}");
        outbox.setCreateTime(LocalDateTime.now().minusMinutes(5));  // 超过 1 分钟
        return outbox;
    }

    @Test
    @DisplayName("OB-01: 正常补偿，3 条 PENDING 全部转为 PROCESSED")
    void shouldCompensatePendingMessages() {
        when(outboxMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(pendingOutbox(1L, 0), pendingOutbox(2L, 0), pendingOutbox(3L, 0)));

        scheduler.compensate();

        verify(outboxService).updateStatus(1L, OutboxStatus.PROCESSED, 0);
        verify(outboxService).updateStatus(2L, OutboxStatus.PROCESSED, 0);
        verify(outboxService).updateStatus(3L, OutboxStatus.PROCESSED, 0);
    }

    @Test
    @DisplayName("OB-02: 超过最大重试次数，转为 FAILED")
    void shouldMarkFailedAfterMaxRetry() {
        when(outboxMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(pendingOutbox(1L, 5)));  // retry_count 已到 5
        doThrow(new RuntimeException("db down"))
                .when(flashDealConsumer).processMessage(any(FlashDealOrderMessage.class));

        scheduler.compensate();

        // retry_count 5 → 6 >= MAX_RETRY(5) → FAILED
        verify(outboxService).updateStatus(1L, OutboxStatus.FAILED, 6);
    }

    @Test
    @DisplayName("OB-03: 补偿时幂等冲突（订单已存在），转为 PROCESSED")
    void shouldMarkProcessedWhenIdempotentConflict() {
        when(outboxMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(pendingOutbox(1L, 0)));
        // processMessage 内部已吞掉 DuplicateKeyException（幂等保护），正常返回

        scheduler.compensate();

        verify(outboxService).updateStatus(1L, OutboxStatus.PROCESSED, 0);
    }

    @Test
    @DisplayName("OB-04: 无 PENDING 消息时直接返回")
    void shouldDoNothingWhenNoPending() {
        when(outboxMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        scheduler.compensate();

        verify(outboxService, org.mockito.Mockito.never())
                .updateStatus(any(), any(), any());
    }
}
