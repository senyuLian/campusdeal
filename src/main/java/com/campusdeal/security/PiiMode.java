package com.campusdeal.security;

/**
 * PII 脱敏模式。
 *
 * <ul>
 *   <li>MASK — 脱敏（138****1234），默认</li>
 *   <li>REMOVE — 删除（直接移除匹配内容）</li>
 *   <li>PASS — 不处理（调试用）</li>
 * </ul>
 */
public enum PiiMode {
    MASK,
    REMOVE,
    PASS
}
