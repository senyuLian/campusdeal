package com.campusdeal.seckill;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Lua 脚本配置：加载 classpath 下的 seckill.lua
 */
@Configuration
public class LuaScriptConfig {

    @Bean
    public DefaultRedisScript<Long> flashDealScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
