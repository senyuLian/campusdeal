package com.campusdeal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Redis 连接工厂配置（T16 结论：保持 Lettuce 单条共享连接）。
 *
 * <p>模块 10 压测对比（Windows 本机 Redis 3.2，~0.4ms/op）：</p>
 * <ul>
 *   <li>共享连接（shareNativeConnection=true，Lettuce 异步批量发送）：秒杀 30 并发 P99=67ms，达标；</li>
 *   <li>显式连接池（shareNativeConnection=false，每操作从池借/还）：秒杀 30 并发 P99 反而恶化到 231ms——
 *       并发下 commons-pool2 借/还 + Lettuce 连接状态重置的同步开销，比共享连接的单线程批量发送更高。</li>
 * </ul>
 * <p>故这里保持 Lettuce 默认共享连接（不强制借用池连接）。连接池参数（max-active=50 等）仍保留在
 * application.yaml，供高并发生产环境（多核 Linux Redis，op 延迟 &lt;0.1ms）按需开启。</p>
 */
@Slf4j
@Configuration
public class RedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(RedisProperties properties) {
        RedisProperties.Lettuce lettuce = properties.getLettuce();
        log.info("[RedisConfig] Lettuce factory: host={}:{} db={}, shareNativeConnection=true",
                properties.getHost(), properties.getPort(), properties.getDatabase());
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(properties.getHost());
        config.setPort(properties.getPort());
        if (properties.getPassword() != null) {
            config.setPassword(RedisPassword.of(properties.getPassword()));
        }
        config.setDatabase(properties.getDatabase());

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        // T16 结论：保持默认共享连接——本环境（Windows 本机 Redis）下共享连接并发延迟
        // 优于显式连接池（见类注释对比数据）。不要改回 setShareNativeConnection(false)。
        factory.setShareNativeConnection(true);
        return factory;
    }
}
