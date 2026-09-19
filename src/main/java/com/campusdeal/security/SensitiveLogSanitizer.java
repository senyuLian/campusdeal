package com.campusdeal.security;

import java.util.regex.Pattern;

/**
 * Small, dependency-free redaction helper for operational logs.  Callers are
 * expected to log event type/correlation data rather than request bodies; this
 * helper is the final guard for exception text and accidental key/value dumps.
 */
public final class SensitiveLogSanitizer {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");
    private static final Pattern AUTH = Pattern.compile("(?i)(bearer\\s+|authorization\\s*[:=]\\s*)([A-Za-z0-9._~+/-]+)");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(password|passwd|token|secret|api[-_]?key|code|prompt|payload|body|phone)\\s*[:=]\\s*([^,;\\s}]+)");
    private static final int MAX_LENGTH = 256;

    private SensitiveLogSanitizer() {
    }

    /** Never include an exception message or stack trace in a sensitive log. */
    public static String exceptionSummary(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    /** Redact common credential/PII fields and cap the resulting log value. */
    public static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = AUTH.matcher(value).replaceAll("$1[REDACTED]");
        redacted = SECRET_FIELD.matcher(redacted).replaceAll("$1=[REDACTED]");
        redacted = PHONE.matcher(redacted).replaceAll("[PHONE]");
        return redacted.length() <= MAX_LENGTH
                ? redacted : redacted.substring(0, MAX_LENGTH) + "…";
    }
}
