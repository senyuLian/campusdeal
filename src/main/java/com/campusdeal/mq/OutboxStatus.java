package com.campusdeal.mq;

/**
 * Outbox 本地消息表状态
 */
public enum OutboxStatus {
    /** 待处理 */
    PENDING,
    /** 已处理 */
    PROCESSED,
    /** 处理失败（超过最大重试次数） */
    FAILED
}
