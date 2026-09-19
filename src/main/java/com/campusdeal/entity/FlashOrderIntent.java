package com.campusdeal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable acceptance record for a flash-deal order. */
@Data
@TableName("flash_order_intent")
public class FlashOrderIntent {
    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private String status;
    private String failureReason;
    private LocalDateTime acceptedAt;
    private LocalDateTime updateTime;
}
