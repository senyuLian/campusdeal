package com.campusdeal.security;

import com.campusdeal.agent.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SG-01..06：敏感操作门禁测试（Mock ToolRegistry）。
 */
@ExtendWith(MockitoExtension.class)
class SensitiveGuardTest {

    @Mock
    private SecurityProperties securityProperties;
    @Mock
    private ToolRegistry toolRegistry;

    @InjectMocks
    private SensitiveGuardImpl guard;

    @BeforeEach
    void setUp() {
        // SG-05 不调用 evaluate，故用 lenient
        lenient().when(securityProperties.getDeniedTools()).thenReturn(List.of());
        lenient().when(securityProperties.getConfirmTools()).thenReturn(List.of("applyRefund"));
    }

    @Test
    @DisplayName("SG-01 普通查询工具直接放行")
    void sg01_allowQueryTools() {
        GuardDecision decision = guard.evaluate("queryOrders", "{}", 1001L);

        assertEquals("ALLOWED", decision.getAction());
    }

    @Test
    @DisplayName("SG-02 退款工具需要二次确认")
    void sg02_requireConfirmationForRefund() {
        GuardDecision decision = guard.evaluate("applyRefund", "{\"orderId\":1}", 1001L);

        assertEquals("CONFIRM", decision.getAction());
        assertNotNull(decision.getConfirmationId());
        assertFalse(decision.getConfirmationId().isBlank());
        assertTrue(decision.getMessage().contains("退款"));
    }

    @Test
    @DisplayName("SG-03 用户确认后执行原工具")
    void sg03_executeAfterConfirmation() {
        GuardDecision decision = guard.evaluate("applyRefund", "{\"orderId\":1}", 1001L);
        when(toolRegistry.execute(anyString(), anyString()))
                .thenReturn("{\"status\":\"refunded\"}");

        GuardResult result = guard.handleConfirmation(decision.getConfirmationId(), true);

        assertTrue(result.isExecuted());
        assertTrue(result.getResult().contains("refunded"));
        verify(toolRegistry).execute(anyString(), anyString());
    }

    @Test
    @DisplayName("SG-04 用户取消：不执行工具")
    void sg04_cancelSkipsExecution() {
        GuardDecision decision = guard.evaluate("applyRefund", "{\"orderId\":1}", 1001L);

        GuardResult result = guard.handleConfirmation(decision.getConfirmationId(), false);

        assertFalse(result.isExecuted());
        assertTrue(result.getMessage().contains("取消"));
    }

    @Test
    @DisplayName("SG-05 确认 ID 不存在：已过期")
    void sg05_expiredConfirmation() {
        GuardResult result = guard.handleConfirmation("nonexistent-id", true);

        assertFalse(result.isExecuted());
        assertTrue(result.getMessage().contains("过期"));
    }

    @Test
    @DisplayName("SG-06 黑名单工具直接拒绝")
    void sg06_denyBlacklistTool() {
        when(securityProperties.getDeniedTools()).thenReturn(List.of("deleteAccount"));

        GuardDecision decision = guard.evaluate("deleteAccount", "{}", 1001L);

        assertEquals("DENIED", decision.getAction());
    }
}
