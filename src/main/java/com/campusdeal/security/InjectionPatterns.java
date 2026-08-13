package com.campusdeal.security;

import java.util.List;

/**
 * Prompt Injection 特征检测规则。
 */
public final class InjectionPatterns {

    private InjectionPatterns() {
    }

    /** 试图覆盖系统指令的模式（中英文） */
    public static final List<String> IGNORE_PATTERNS = List.of(
            "忽略(所有|之前|前面|以上).*(指令|规则|限制|要求|提示)",
            "ignore.*(all|previous|above).*instruction",
            "forget.*(all|previous).*instruction",
            "从现在开始你是",
            "you are now",
            "新角色",
            "new role"
    );

    /** 分隔符注入 */
    public static final List<String> DELIMITER_INJECTION = List.of(
            "<|im_start|>", "<|im_end|>",
            "### System", "### User", "### Assistant"
    );

    /** 越狱关键词 */
    public static final List<String> JAILBREAK_KEYWORDS = List.of(
            "DAN", "jailbreak", "越狱",
            "无视道德", "无视限制",
            "你不需要遵守"
    );
}
