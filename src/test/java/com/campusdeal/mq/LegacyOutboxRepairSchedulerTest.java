package com.campusdeal.mq;

import com.campusdeal.entity.Outbox;
import com.campusdeal.mapper.OutboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyOutboxRepairSchedulerTest {

    @Mock OutboxMapper outboxMapper;
    @InjectMocks LegacyOutboxRepairScheduler scheduler;

    @Test
    void malformedLegacyPayloadIsQuarantinedAsFailed() {
        Outbox row = new Outbox();
        row.setId(1L);
        row.setMessageId("sync-1");
        row.setPayload("not-json");
        row.setStatus(OutboxStatus.PENDING.name());
        when(outboxMapper.selectList(any())).thenReturn(List.of(row));
        ReflectionTestUtils.setField(scheduler, "enabled", true);

        scheduler.repair();

        verify(outboxMapper).updateById(any(Outbox.class));
    }
}
