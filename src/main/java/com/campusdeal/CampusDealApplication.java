package com.campusdeal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.campusdeal.mapper")
@EnableAspectJAutoProxy(exposeProxy = true)
@EnableScheduling  // Module 05: 知识索引每日增量重建（IndexBuilder.scheduledReindex）
public class CampusDealApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampusDealApplication.class, args);
    }
}
