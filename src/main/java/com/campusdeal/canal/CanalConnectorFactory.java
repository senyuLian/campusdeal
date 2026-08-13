package com.campusdeal.canal;

import com.alibaba.otter.canal.client.CanalConnector;

/**
 * CanalConnector 工厂：隔离真实 Canal 客户端依赖，便于单元测试 Mock
 */
public interface CanalConnectorFactory {

    /**
     * 创建单机 Canal 连接
     */
    CanalConnector create(String host, int port, String destination,
                          String username, String password);
}
