package com.campusdeal.service;

import com.campusdeal.entity.Outbox;
import com.campusdeal.mq.OutboxStatus;

import java.util.List;

/**
 * Outbox 本地消息表服务
 */
public interface OutboxService {

    /**
     * 记录一条待处理/已处理的消息
     *
     * @param messageId 消息 ID（Kafka key + offset）
     * @param payload   消息体 JSON
     * @param status    处理状态
     */
    void record(String messageId, String payload, OutboxStatus status);

    /**
     * 查询需要补偿的消息（PENDING 且超过 1 分钟）
     *
     * @param limit 查询条数上限
     * @return 待补偿消息列表
     */
    List<Outbox> findPending(int limit);

    /**
     * 更新消息状态
     *
     * @param id         Outbox 主键
     * @param status     新状态
     * @param retryCount 重试次数
     */
    void updateStatus(Long id, OutboxStatus status, Integer retryCount);

    /** Update lifecycle state and retain a bounded last failure reason. */
    default void updateStatus(Long id, OutboxStatus status, Integer retryCount, String errorMsg) {
        updateStatus(id, status, retryCount);
    }
}
