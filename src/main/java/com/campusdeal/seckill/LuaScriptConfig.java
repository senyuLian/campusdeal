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

    @Bean
    public DefaultRedisScript<Long> flashReservationScript() {
        return script("flash_reserve.lua");
    }

    @Bean
    public DefaultRedisScript<Long> flashReservationReleaseScript() {
        return script("flash_reserve_release.lua");
    }

    @Bean
    public DefaultRedisScript<Long> flashReservationCommitScript() {
        return script("flash_reserve_commit.lua");
    }

    private DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }
}
