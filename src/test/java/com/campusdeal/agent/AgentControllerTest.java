package com.campusdeal.agent;

import com.campusdeal.dto.Result;
import com.campusdeal.security.ConfirmRequest;
import com.campusdeal.security.GuardResult;
import com.campusdeal.security.SensitiveGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-01..03：Agent SSE 控制器测试（Mock 编排器 + 敏感门禁）。
 */
@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock
    AgentOrchestrator orchestrator;
    @Mock
    SensitiveGuard sensitiveGuard;

    @InjectMocks
    AgentController controller;

    @Test
    @DisplayName("AC-01 chat：委托编排器并原样返回 SseEmitter")
    void ac01_chatDelegates() {
        SseEmitter emitter = mock(SseEmitter.class);
        when(orchestrator.chat("你好", "s1")).thenReturn(emitter);

        SseEmitter result = controller.chat("你好", "s1");

        assertSame(emitter, result);
        verify(orchestrator).chat("你好", "s1");
    }

    @Test
    @DisplayName("AC-02 history：委托编排器查询会话历史")
    void ac02_historyDelegates() {
        Result expected = Result.ok();
        when(orchestrator.getHistory("s1")).thenReturn(expected);

        Result result = controller.history("s1");

        assertSame(expected, result);
        verify(orchestrator).getHistory("s1");
    }

    @Test
    @DisplayName("AC-03 confirm：批准后委托门禁执行并返回 ok")
    void ac03_confirmDelegates() {
        ConfirmRequest request = new ConfirmRequest("cfm-1", true);
        when(sensitiveGuard.handleConfirmation("cfm-1", true))
                .thenReturn(GuardResult.builder().executed(true).result("{\"status\":\"refunded\"}").message("操作成功").build());

        Result result = controller.confirm(request);

        assertTrue(result.getSuccess());
        verify(sensitiveGuard).handleConfirmation("cfm-1", true);
    }
}
