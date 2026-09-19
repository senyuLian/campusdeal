package com.campusdeal.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campusdeal.entity.Outbox;
import com.campusdeal.mapper.OutboxMapper;
import com.campusdeal.mq.OutboxStatus;
import com.campusdeal.service.OutboxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional(noRollbackFor = DuplicateKeyException.class)
    public void record(String messageId, String payload, OutboxStatus status) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        Outbox existing = lambdaQuery().eq(Outbox::getMessageId, messageId).one();
        if (existing != null) return;
        Outbox outbox = new Outbox();
        outbox.setMessageId(messageId);
        outbox.setTopic(topic);
        outbox.setPayload(payload);
        outbox.setStatus(status.name());
        outbox.setRetryCount(0);
        outbox.setCreateTime(LocalDateTime.now());
        outbox.setUpdateTime(LocalDateTime.now());
        try {
            save(outbox);
        } catch (DuplicateKeyException duplicate) {
            // Another consumer won the unique message-id race. The durable
            // row is already present and must not be duplicated.
        }
    }

    @Override
    public List<Outbox> findPending(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return lambdaQuery()
                .eq(Outbox::getStatus, OutboxStatus.PENDING.name())
                .lt(Outbox::getCreateTime, LocalDateTime.now().minusMinutes(1))
                .last("LIMIT " + safeLimit)
                .list();
    }

    @Override
    public void updateStatus(Long id, OutboxStatus status, Integer retryCount) {
        updateStatus(id, status, retryCount, null);
    }

    @Override
    public void updateStatus(Long id, OutboxStatus status, Integer retryCount, String errorMsg) {
        Outbox update = new Outbox();
        update.setId(id);
        update.setStatus(status.name());
        update.setRetryCount(retryCount);
        update.setErrorMsg(errorMsg == null || errorMsg.isBlank() ? null
                : errorMsg.substring(0, Math.min(errorMsg.length(), 500)));
        update.setUpdateTime(LocalDateTime.now());
        updateById(update);
    }
}
