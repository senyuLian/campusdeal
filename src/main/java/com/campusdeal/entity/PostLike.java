package com.campusdeal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable like relation. The database unique key is the idempotency boundary. */
@Data
@TableName("tb_post_like")
public class PostLike {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long postId;
    private Long userId;
    private LocalDateTime createTime;
}
