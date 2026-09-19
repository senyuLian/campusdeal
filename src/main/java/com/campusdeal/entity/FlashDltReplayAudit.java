package com.campusdeal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable audit trail for an operator initiated DLT replay. */
@Data
@TableName("flash_dlt_replay_audit")
public class FlashDltReplayAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Long operatorUserId;
    private String payloadSha256;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
