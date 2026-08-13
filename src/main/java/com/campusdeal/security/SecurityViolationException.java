package com.campusdeal.security;

/**
 * 检测到恶意 / 不安全输入时抛出。
 */
public class SecurityViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SecurityViolationException(String message) {
        super(message);
    }
}
