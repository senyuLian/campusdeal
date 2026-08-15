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

        GuardResult result = guard.handleConfirmation(decision.getConfirmationId(), true, 1001L);

        assertTrue(result.isExecuted());
        assertTrue(result.getResult().contains("refunded"));
        verify(toolRegistry).execute(anyString(), anyString());
    }

    @Test
    @DisplayName("SG-04 用户取消：不执行工具")
    void sg04_cancelSkipsExecution() {
        GuardDecision decision = guard.evaluate("applyRefund", "{\"orderId\":1}", 1001L);

        GuardResult result = guard.handleConfirmation(decision.getConfirmationId(), false, 1001L);

        assertFalse(result.isExecuted());
        assertTrue(result.getMessage().contains("取消"));
    }

    @Test
    @DisplayName("SG-05 确认 ID 不存在：已过期")
    void sg05_expiredConfirmation() {
        GuardResult result = guard.handleConfirmation("nonexistent-id", true, 1001L);

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

    @Test
    @DisplayName("SG-07 T3 回归：工具注册名 apply_refund 命中配置 applyRefund（camel/snake 归一化）")
    void sg07_snakeCaseToolNameMatchesCamelCaseConfig() {
        GuardDecision decision = guard.evaluate("apply_refund", "{\"orderId\":1}", 1001L);

        assertEquals("CONFIRM", decision.getAction());
        assertNotNull(decision.getConfirmationId());
        assertTrue(decision.getMessage().contains("退款"));
    }

    @Test
    @DisplayName("SG-08 T3 回归：确认后按 snake_case 工具名执行原工具")
    void sg08_confirmExecutesSnakeCaseTool() {
        GuardDecision decision = guard.evaluate("apply_refund", "{\"orderId\":1}", 1001L);
        when(toolRegistry.execute("apply_refund", "{\"orderId\":1}"))
                .thenReturn("{\"success\":true}");

        GuardResult result = guard.handleConfirmation(decision.getConfirmationId(), true, 1001L);

        assertTrue(result.isExecuted());
        assertTrue(result.getResult().contains("success"));
        verify(toolRegistry).execute("apply_refund", "{\"orderId\":1}");
    }

    @Test
    @DisplayName("SG-10 T8：非创建者用户确认 → 无权操作")
    void sg10_confirmByDifferentUserDenied() {
        GuardDecision decision = guard.evaluate("apply_refund", "{\"orderId\":1}", 1001L);

        GuardResult result = guard.handleConfirmation(decision.getConfirmationId(), true, 9999L);

        assertFalse(result.isExecuted());
        assertTrue(result.getMessage().contains("无权"));
    }

    @Test
    @DisplayName("SG-11 T8：确认上下文超时后不可再批准")
    void sg11_expiredConfirmationRejected() throws Exception {
        guard.confirmationTimeoutMs = 30;  // 测试缩短超时
        GuardDecision decision = guard.evaluate("apply_refund", "{\"orderId\":1}", 1001L);

        Thread.sleep(60);
        GuardResult result = guard.handleConfirmation(decision.getConfirmationId(), true, 1001L);

        assertFalse(result.isExecuted());
        assertTrue(result.getMessage().contains("过期"));

        // 调度清理可移除残留项
        guard.evictExpired();
    }

    @Test
    @DisplayName("SG-09 T3 回归：黑名单同样支持 camel/snake 归一化")
    void sg09_denyListNormalized() {
        when(securityProperties.getDeniedTools()).thenReturn(List.of("delete_account"));

        GuardDecision decision = guard.evaluate("deleteAccount", "{}", 1001L);

        assertEquals("DENIED", decision.getAction());
    }
}
