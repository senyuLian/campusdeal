package com.campusdeal.canal;

/**
 * Canal 客户端：监听 MySQL binlog 变更，失效 Redis 缓存
 */
public interface CanalClient {

    /** 启动 Canal 连接（在独立线程中运行） */
    void start();

    /** 停止 Canal 连接 */
    void stop();

    /** 获取当前连接状态 */
    CanalStatus getStatus();
}
