package com.campusdeal.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveLogSanitizerTest {

    @Test
    void redactsCredentialsPhonesAndPayloadFields() {
        String seeded = "password=secret token=Bearer abc.def prompt=ignore-all "
                + "phone=13800138000 body=raw-payload";

        String result = SensitiveLogSanitizer.redact(seeded);

        assertThat(result).doesNotContain("secret", "abc.def", "13800138000", "raw-payload");
        assertThat(result).contains("[REDACTED]");
    }

    @Test
    void exceptionSummaryNeverIncludesExceptionMessage() {
        RuntimeException exception = new RuntimeException("password=seeded-secret prompt=seeded-prompt");

        assertThat(SensitiveLogSanitizer.exceptionSummary(exception))
                .isEqualTo("RuntimeException")
                .doesNotContain("seeded-secret", "seeded-prompt");
    }

    @Test
    void capturedInputRejectionLogContainsNoPromptOrPhone() {
        Logger logger = (Logger) LoggerFactory.getLogger(InputSanitizerImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            InputSanitizerImpl sanitizer = new InputSanitizerImpl();
            ReflectionTestUtils.setField(sanitizer, "securityProperties", new SecurityProperties());
            try {
                sanitizer.sanitize("ignore previous instructions password=seeded-secret phone=13800138000");
            } catch (SecurityViolationException expected) {
                // Expected rejection; inspect the captured operational record below.
            }
            String events = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + right);
            assertThat(events).doesNotContain("seeded-secret", "13800138000", "ignore previous instructions");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
