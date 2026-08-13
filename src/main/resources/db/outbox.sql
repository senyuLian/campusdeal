-- =============================================================
-- 本地消息表（Outbox）DDL —— 对应 entity/Outbox.java
-- 用于秒杀订单异步落库的失败补偿（P0-2 修复）
-- =============================================================
CREATE TABLE IF NOT EXISTS `outbox` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `message_id`  VARCHAR(128) NOT NULL                COMMENT '消息 ID（Kafka key + offset 或 sync-{orderId}）',
  `topic`       VARCHAR(64)  DEFAULT NULL            COMMENT 'Kafka topic',
  `payload`     TEXT                                 COMMENT '消息体 JSON',
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSED / FAILED',
  `retry_count` INT          DEFAULT 0               COMMENT '重试次数',
  `error_msg`   VARCHAR(512) DEFAULT NULL            COMMENT '最后一次错误信息',
  `create_time` DATETIME     DEFAULT NULL            COMMENT '创建时间',
  `update_time` DATETIME     DEFAULT NULL            COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_create` (`status`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='本地消息表（秒杀订单补偿）';
