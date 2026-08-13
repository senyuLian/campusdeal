package com.campusdeal.agent;

import com.campusdeal.security.GuardDecision;
import com.campusdeal.security.InputSanitizer;
import com.campusdeal.security.OutputVerifier;
import com.campusdeal.security.RateLimiter;
import com.campusdeal.security.SanitizedInput;
import com.campusdeal.security.SensitiveGuard;
import com.campusdeal.security.VerificationResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SG-01..06：Agent 状态图编排（ReAct 循环）测试。
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock
    DeepSeekChatClient llmClient;
    @Mock
    ToolRegistry toolRegistry;
    @Mock
    SessionManager sessionManager;
    @Mock
    InputSanitizer inputSanitizer;
    @Mock
    OutputVerifier outputVerifier;
    @Mock
    SensitiveGuard sensitiveGuard;
    @Mock
    RateLimiter rateLimiter;

    @InjectMocks
    AgentOrchestratorImpl orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        orchestrator.buildGraph();
        when(inputSanitizer.sanitize(anyString())).thenAnswer(inv -> SanitizedInput.builder()
                .cleanedText(inv.getArgument(0))
                .safetyScore(1.0)
                .containsPii(false)
                .build());
        when(outputVerifier.verify(anyString(), any())).thenReturn(VerificationResult.builder()
                .passed(true)
                .confidence(1.0)
                .correctedOutput("")
                .build());
        // actNode 门禁默认放行（仅工具调用测试用到）；chat() 限流默认放行（仅异步测试用到）
        lenient().when(sensitiveGuard.evaluate(anyString(), anyString(), any()))
                .thenReturn(GuardDecision.builder().action("ALLOWED").build());
        lenient().when(rateLimiter.tryAcquire(any())).thenReturn(true);
        when(toolRegistry.buildToolSpecifications()).thenReturn(List.of());
        when(sessionManager.getOrCreate(anyString(), any())).thenReturn(
                AgentSession.builder().sessionId("s1").userId(1L).messages(new ArrayList<>()).build());
    }

    private Map<String, Object> initialState() {
        return orchestrator.buildInitialState("s1", 1L, "你好");
    }

    private void stubStream(String answer) {
        doAnswer(inv -> {
            StreamingResponseHandler<AiMessage> handler = inv.getArgument(1);
            handler.onComplete(Response.from(AiMessage.from(answer), new TokenUsage(10, 20)));
            return null;
        }).when(llmClient).chatStream(anyList(), any());
    }

    @Test
    @DisplayName("SG-01 直接回答：模型无工具调用 → think→answer→verify")
    void sg01_directAnswer() throws Exception {
        when(llmClient.chatSync(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("你好！我是校园生活助手。"), new TokenUsage(10, 20)));
        stubStream("你好！我是校园生活助手。");

        AgentState result = orchestrator.executeGraph(initialState());

        assertEquals("你好！我是校园生活助手。", result.getFinalAnswer());
        assertEquals(1, result.getIteration());
        assertTrue(result.getPendingToolCalls().isEmpty());
        verify(llmClient).chatSync(anyList(), anyList());
    }

    @Test
    @DisplayName("SG-02 单轮工具调用：think→act→think→answer→verify")
    void sg02_singleToolCallFlow() throws Exception {
        when(llmClient.chatSync(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(
                        ToolExecutionRequest.builder().id("call1").name("query_order").arguments("{}").build()),
                        new TokenUsage(10, 20)))
                .thenReturn(Response.from(AiMessage.from("您共有 2 笔订单。"), new TokenUsage(5, 10)));
        when(toolRegistry.execute(eq("query_order"), anyString()))
                .thenReturn("{\"orders\":[{\"id\":1},{\"id\":2}]}");
        stubStream("您共有 2 笔订单。");

        AgentState result = orchestrator.executeGraph(initialState());

        assertEquals("您共有 2 笔订单。", result.getFinalAnswer());
        assertEquals(2, result.getIteration());
        assertEquals(1, result.getToolResults().size());
        verify(toolRegistry).execute("query_order", "{}");
    }

    @Test
    @DisplayName("SG-03 多轮工具调用：LLM 连续两次发起工具调用后给出答案")
    void sg03_multiRoundToolCalls() throws Exception {
        when(llmClient.chatSync(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(
                        ToolExecutionRequest.builder().id("c1").name("search_merchant").arguments("{\"keyword\":\"咖啡\"}").build()),
                        new TokenUsage(10, 20)))
                .thenReturn(Response.from(AiMessage.from(
                        ToolExecutionRequest.builder().id("c2").name("query_coupon").arguments("{}").build()),
                        new TokenUsage(5, 10)))
                .thenReturn(Response.from(AiMessage.from("为您推荐了咖啡商家和优惠券。"), new TokenUsage(5, 5)));
        when(toolRegistry.execute(anyString(), anyString())).thenReturn("{\"ok\":true}");
        stubStream("为您推荐了咖啡商家和优惠券。");

        AgentState result = orchestrator.executeGraph(initialState());

        assertEquals("为您推荐了咖啡商家和优惠券。", result.getFinalAnswer());
        assertEquals(3, result.getIteration());
        assertEquals(2, result.getToolResults().size());
        verify(toolRegistry, times(2)).execute(anyString(), anyString());
    }

    @Test
    @DisplayName("SG-04 迭代超限：LLM 始终返回工具调用 → 第 5 次 think 后走 finish→verify")
    void sg04_iterationLimit() throws Exception {
        when(llmClient.chatSync(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(
                        ToolExecutionRequest.builder().id("c").name("query_order").arguments("{}").build()),
                        new TokenUsage(1, 1)));
        when(toolRegistry.execute(anyString(), anyString())).thenReturn("{}");

        AgentState result = orchestrator.executeGraph(initialState());

        assertEquals(5, result.getIteration());
        assertEquals("抱歉，我在多次尝试后仍未完成您的请求，请换个说法再试一次。", result.getFinalAnswer());
        verify(llmClient, times(5)).chatSync(anyList(), anyList());
        verify(toolRegistry, times(4)).execute(anyString(), anyString());
    }

    @Test
    @DisplayName("SG-05 输出校验失败：verify 节点输出兜底文案")
    void sg05_verifyRejectsAnswer() throws Exception {
        when(outputVerifier.verify(anyString(), any())).thenReturn(VerificationResult.builder()
                .passed(false)
                .confidence(0.5)
                .correctedOutput("")
                .build());
        when(llmClient.chatSync(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("不好说"), new TokenUsage(1, 1)));
        stubStream("不好说");

        AgentState result = orchestrator.executeGraph(initialState());

        assertEquals("抱歉，我暂时无法给出可靠的回答，请稍后再试。", result.getFinalAnswer());
    }

    @Test
    @DisplayName("SG-06 完整对话：图执行后持久化会话并发送 done 事件")
    void sg06_completeChatSavesSession() throws Exception {
        when(llmClient.chatSync(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("好的"), new TokenUsage(1, 1)));
        stubStream("好的");
        SseEmitter emitter = mock(SseEmitter.class);

        Map<String, Object> init = orchestrator.buildInitialState("s1", 1L, "你好");
        orchestrator.completeChat("s1", init, emitter);

        verify(sessionManager).save(eq("s1"), any(AgentState.class));
        verify(emitter).complete();
    }

    @Test
    @DisplayName("SG-07 异步入口：chat 返回 SseEmitter 并在后台完成")
    void sg07_asyncChatReturnsEmitter() throws Exception {
        when(llmClient.chatSync(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("好的"), new TokenUsage(1, 1)));
        stubStream("好的");

        SseEmitter emitter = orchestrator.chat("你好", null);

        assertNotNull(emitter);
        verify(sessionManager, org.mockito.Mockito.timeout(3000)).save(anyString(), any(AgentState.class));
    }
}
