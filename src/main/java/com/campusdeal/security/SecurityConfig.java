package com.campusdeal.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 安全模块配置：启用 {@link SecurityProperties}。
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {
}
