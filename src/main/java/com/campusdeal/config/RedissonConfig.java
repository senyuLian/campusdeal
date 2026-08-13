package com.campusdeal.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class RedissonConfig {

    /**
     * 懒加载 RedissonClient：真正调用分布式锁时才建立连接。
     * 这样 Redis 短暂不可用时应用仍能启动（Lettuce/StringRedisTemplate 本就是惰性连接），
     * 避免启动期强依赖外部服务。
     */
    @Bean
    @Lazy
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379").setPassword("123456");
        return Redisson.create(config);
    }
}
