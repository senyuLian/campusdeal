package com.campusdeal.security;

/**
 * 用户输入清洗：PII 脱敏 + Prompt Injection 检测 + 长度/内容检查。
 *
 * <p>检测到恶意输入时抛出 {@link SecurityViolationException}。</p>
 */
public interface InputSanitizer {

    /**
     * 清洗用户输入。
     *
     * @param rawInput 原始用户输入
     * @return 清洗后的安全输入
     * @throws SecurityViolationException 检测到恶意输入时抛出
     */
    SanitizedInput sanitize(String rawInput);
}
