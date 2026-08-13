package com.campusdeal.security;

/**
 * 输出校验：幻觉检测 + 事实一致性 + 敏感信息过滤。
 */
public interface OutputVerifier {

    /**
     * 验证 LLM 输出。
     *
     * @param llmOutput LLM 生成的文本
     * @param context   上下文（用户问题 + 工具结果）
     * @return 验证结果
     */
    VerificationResult verify(String llmOutput, VerificationContext context);
}
