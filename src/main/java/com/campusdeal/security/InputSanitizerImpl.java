package com.campusdeal.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输入清洗实现：四层防护。
 *
 * <ol>
 *   <li>长度检查（2000 字符截断）</li>
 *   <li>SQL / 命令注入检测</li>
 *   <li>Prompt Injection 检测（规则匹配，μs 级）</li>
 *   <li>PII 脱敏（手机 / 身份证 / 邮箱 / 银行卡）</li>
 * </ol>
 *
 * <p>最终 safetyScore 低于阈值则拒绝。规则匹配零额外 LLM 调用，覆盖常见注入模式，
 * 残余风险由 OutputVerifier 兜底（即使注入成功，编造的数据也会被检测）。</p>
 */
@Slf4j
@Component
public class InputSanitizerImpl implements InputSanitizer {

    @Resource
    private SecurityProperties securityProperties;

    private static final int MAX_INPUT_LENGTH = 2000;
    private static final double MIN_SAFETY_SCORE = 0.3;

    @Override
    public SanitizedInput sanitize(String rawInput) {
        List<String> warnings = new ArrayList<>();
        double safetyScore = 1.0;

        // === 1. 长度与空输入检查 ===
        if (rawInput == null || rawInput.isBlank()) {
            throw new SecurityViolationException("输入内容不能为空");
        }
        String cleaned = rawInput;
        if (cleaned.length() > MAX_INPUT_LENGTH) {
            cleaned = cleaned.substring(0, MAX_INPUT_LENGTH);
            warnings.add("输入已截断至 " + MAX_INPUT_LENGTH + " 字符");
        }

        // === 2. SQL / 命令注入检测（基础） ===
        if (containsSqlInjection(cleaned)) {
            safetyScore -= 0.5;
            warnings.add("检测到可能的 SQL 注入");
        }

        // === 3. Prompt Injection 检测 ===
        double injectionScore = detectPromptInjection(cleaned);
        safetyScore -= injectionScore;
        if (injectionScore > 0.5) {
            warnings.add("检测到提示注入特征 (score=" + injectionScore + ")");
        }

        // === 4. PII 脱敏 ===
        Map<String, String> piiReplacements = new HashMap<>();
        boolean containsPii = false;
        PiiMode mode = securityProperties.getPiiMode();
        if (mode == PiiMode.MASK) {
            cleaned = maskPattern(cleaned, PiiPatterns.PHONE, "phone", piiReplacements);
            cleaned = maskPattern(cleaned, PiiPatterns.ID_CARD, "idCard", piiReplacements);
            cleaned = maskPattern(cleaned, PiiPatterns.EMAIL, "email", piiReplacements);
            cleaned = maskPattern(cleaned, PiiPatterns.BANK_CARD, "bankCard", piiReplacements);
            containsPii = !piiReplacements.isEmpty();
            if (containsPii) {
                warnings.add("PII 已脱敏: " + piiReplacements.keySet());
            }
        }

        // === 5. 安全检查：低于阈值 → 拒绝 ===
        if (safetyScore < MIN_SAFETY_SCORE) {
            log.warn("Input rejected: safetyScore={}, warnings={}", safetyScore, warnings);
            throw new SecurityViolationException(
                    "输入被判定为不安全，已拦截 (score=" + safetyScore + ")");
        }

        return SanitizedInput.builder()
                .cleanedText(cleaned)
                .containsPii(containsPii)
                .piiReplacements(piiReplacements)
                .safetyScore(safetyScore)
                .warnings(warnings)
                .build();
    }

    /**
     * Prompt Injection 检测（规则匹配，累计命中分数）。
     */
    private double detectPromptInjection(String input) {
        double score = 0;
        String lower = input.toLowerCase();
        String compact = input.replaceAll("\\s+", "").toLowerCase();

        for (String pattern : InjectionPatterns.IGNORE_PATTERNS) {
            if (lower.matches(".*" + pattern + ".*")
                    || compact.contains(pattern.replaceAll("\\s+", "").toLowerCase())) {
                score += 0.6;
            }
        }
        for (String delimiter : InjectionPatterns.DELIMITER_INJECTION) {
            if (input.contains(delimiter)) {
                score += 0.4;
            }
        }
        for (String keyword : InjectionPatterns.JAILBREAK_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase())) {
                score += 0.5;
            }
        }
        return Math.min(score, 1.0);
    }

    /**
     * PII 脱敏：匹配 → 替换为脱敏形式。
     */
    private String maskPattern(String text, Pattern pattern,
                               String type, Map<String, String> replacements) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            String masked = mask(original);
            replacements.put(type, masked);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String mask(String original) {
        if (original.length() <= 4) {
            return "****";
        }
        return original.charAt(0) + "****" + original.charAt(original.length() - 1);
    }

    private boolean containsSqlInjection(String input) {
        String upper = input.toUpperCase();
        return upper.contains("DROP ") || upper.contains("DELETE FROM")
                || upper.contains("UNION SELECT") || upper.contains("1=1");
    }
}
