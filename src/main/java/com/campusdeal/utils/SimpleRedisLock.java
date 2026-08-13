package com.campusdeal.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String KEY_PREFIX = "lock: ";
    private static final String ID_PREFIX = UUID.randomUUID().toString( true)+"-";
    //释放锁的脚本
    private static final DefaultRedisScript<Long> UN_LOCK_SCRIPT;
    static {
        UN_LOCK_SCRIPT = new DefaultRedisScript<>();
        UN_LOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UN_LOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(Long time) {

        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, time, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//避免自动拆箱问题，可能会拆出空指针
    }

    @Override
    public void unLock() {
        stringRedisTemplate.execute(UN_LOCK_SCRIPT,
                                    Collections.singletonList(KEY_PREFIX + name),
                              ID_PREFIX + Thread.currentThread().getId());

    }
}
