package com.campusdeal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表（Outbox）：记录每条 Kafka 消息的处理状态，用于失败补偿
 *
 * <pre>
 * status: PENDING=待处理, PROCESSED=已处理, FAILED=失败(超过最大重试)
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("outbox")
public class Outbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Kafka message key + offset */
    private String messageId;

    /** Kafka topic */
    private String topic;

    /** 消息体 JSON */
    private String payload;

    /** PENDING / PROCESSED / FAILED */
    private String status;

    private Integer retryCount;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
