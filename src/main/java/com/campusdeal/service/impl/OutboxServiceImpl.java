package com.campusdeal.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campusdeal.entity.Outbox;
import com.campusdeal.mapper.OutboxMapper;
import com.campusdeal.mq.OutboxStatus;
import com.campusdeal.service.OutboxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 服务实现
 */
@Service
public class OutboxServiceImpl extends ServiceImpl<OutboxMapper, Outbox>
        implements OutboxService {

    @Value("${campusdeal.kafka.topic.flash-deal-orders:flash-deal-orders}")
    private String topic;

    @Override
    public void record(String messageId, String payload, OutboxStatus status) {
        Outbox outbox = new Outbox();
        outbox.setMessageId(messageId);
        outbox.setTopic(topic);
        outbox.setPayload(payload);
        outbox.setStatus(status.name());
        outbox.setRetryCount(0);
        save(outbox);
    }

    @Override
    public List<Outbox> findPending(int limit) {
        return lambdaQuery()
                .eq(Outbox::getStatus, OutboxStatus.PENDING.name())
                .lt(Outbox::getCreateTime, LocalDateTime.now().minusMinutes(1))
                .last("LIMIT " + limit)
                .list();
    }

    @Override
    public void updateStatus(Long id, OutboxStatus status, Integer retryCount) {
        Outbox update = new Outbox();
        update.setId(id);
        update.setStatus(status.name());
        update.setRetryCount(retryCount);
        updateById(update);
    }
}
