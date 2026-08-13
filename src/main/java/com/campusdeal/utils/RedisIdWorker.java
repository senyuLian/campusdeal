package com.campusdeal.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1768836817L;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final long COUNT_BITS = 32;



    public long getNextId(String keyPrefix) {
        //1. 获取当前时间戳
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = second - BEGIN_TIMESTAMP;

        //2.1 获取当前日期的序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2 使用Redis的INCR原子操作实现递增，每次调用此方法都会使对应key的值加1，从而实现序列号递增
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //3. 拼接并返回
        count = timeStamp << COUNT_BITS | count;
        return count;
    }


    public static void main(String[] args) {
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC); //得到某个时间对应的秒数 ZoneOffset.UTC为对应的时区
        System.out.println( second);
    }

}
