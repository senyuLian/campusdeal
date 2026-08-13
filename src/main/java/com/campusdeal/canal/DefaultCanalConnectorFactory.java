package com.campusdeal.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * 基于 Canal Java Client 的连接工厂默认实现
 */
@Component
public class DefaultCanalConnectorFactory implements CanalConnectorFactory {

    @Override
    public CanalConnector create(String host, int port, String destination,
                                 String username, String password) {
        return CanalConnectors.newSingleConnector(
                new InetSocketAddress(host, port), destination, username, password);
    }
}
