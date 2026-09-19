package com.campusdeal.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 安全模块配置（{@code campusdeal.security.*}）。
 */
@Data
@ConfigurationProperties(prefix = "campusdeal.security")
public class SecurityProperties {

    /** 需要用户二次确认的工具名列表（如退款） */
    private List<String> confirmTools = List.of("applyRefund");

    /** 直接拒绝的工具名列表 */
    private List<String> deniedTools = List.of();

    /** PII 脱敏模式 */
    private PiiMode piiMode = PiiMode.MASK;

    /** Prompt injection 检测灵敏度 */
    private double injectionSensitivity = 0.8;

    /** 输出幻觉检测开关 */
    private boolean hallucinationCheck = true;

    /** 是否在每次回复末尾附加安全提示 */
    private boolean appendSafetyNote = false;

    /** 限流：每分钟每用户最大请求数 */
    private int rateLimitPerMinute = 10;

    /** 验证码发送：每个手机号每分钟最大次数 */
    private int verificationSendPerMinute = 3;

    /** 验证码校验失败：每个手机号每分钟最大次数 */
    private int verificationAttemptPerMinute = 10;
}
