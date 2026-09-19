package com.campusdeal.agent;

import cn.hutool.json.JSONUtil;
import com.campusdeal.dto.Result;
import com.campusdeal.dto.UserDTO;
import com.campusdeal.security.GuardDecision;
import com.campusdeal.security.InputSanitizer;
import com.campusdeal.security.OutputVerifier;
import com.campusdeal.security.RateLimiter;
import com.campusdeal.security.SanitizedInput;
import com.campusdeal.security.SecurityViolationException;
import com.campusdeal.security.SensitiveGuard;
import com.campusdeal.security.SensitiveLogSanitizer;
import com.campusdeal.security.VerificationContext;
import com.campusdeal.security.VerificationResult;
import com.campusdeal.config.ReliabilityMetrics;
import com.campusdeal.exception.UnauthorizedException;
import com.campusdeal.utils.UserHolder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 编排器：LangGraph4j 状态图实现的 ReAct 循环。
 *
 * <p>图流程：sanitize → think →（有工具调用则 act → think，迭代超限则 finish）→ answer → verify → END。</p>
 *
 * <pre>
 *   START ──► sanitize ──► think ──(conditional)──► act / answer / finish
 *                              ▲                         │
 *                              └──────── act ─────────────┘
 *   answer / finish ──► verify ──► END
 * </pre>
 */
@Slf4j
@Service
public class AgentOrchestratorImpl implements AgentOrchestrator {

    /** 流式最终回答的最长等待时间（秒），防止 mock 或异常导致图永久挂起 */
    private static final int STREAM_WAIT_SECONDS = 30;

    @Resource
    private DeepSeekChatClient llmClient;
    @Resource
    private ToolRegistry toolRegistry;
    @Resource
    private SessionManager sessionManager;
    @Resource
    private InputSanitizer inputSanitizer;
    @Resource
    private OutputVerifier outputVerifier;
    @Resource
    private SensitiveGuard sensitiveGuard;
    @Resource
    private RateLimiter rateLimiter;

    @Value("${campusdeal.agent.max-iterations:5}")
    private int maxIterations = 5;

    @Value("${campusdeal.agent.turn-timeout-ms:35000}")
    private long turnTimeoutMs = 35_000L;

    @Value("${campusdeal.agent.enabled:true}")
    private boolean agentEnabled = true;

    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("agentExecutor")
    private ExecutorService agentExecutor;

    @Autowired(required = false)
    private ReliabilityMetrics reliabilityMetrics;

    /** Bounded fallback for isolated/manual construction outside Spring. */
    private static final ExecutorService FALLBACK_EXECUTOR = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread thread = new Thread(runnable, "agent-turn-fallback");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private StateGraph<AgentState> graph;

    @PostConstruct
    void buildGraph() throws Exception {
        StateGraph<AgentState> g = new StateGraph<>(AgentState::new);
        g.addNode("sanitize", AsyncNodeAction.node_async(this::sanitizeNode));
        g.addNode("think", AsyncNodeAction.node_async(this::thinkNode));
        g.addNode("act", AsyncNodeAction.node_async(this::actNode));
        g.addNode("answer", AsyncNodeAction.node_async(this::answerNode));
        g.addNode("finish", AsyncNodeAction.node_async(this::finishNode));
        g.addNode("verify", AsyncNodeAction.node_async(this::verifyNode));

        g.addEdge(StateGraph.START, "sanitize");
        // Module 06：输入被拒绝（安全违规）时直接结束，不再进入 LLM
        g.addConditionalEdges("sanitize", AsyncEdgeAction.edge_async(this::routeAfterSanitize),
                Map.of("think", "think", "end", StateGraph.END));
        // The final answer is already produced by the last synchronous think
        // call. Route directly to verification so a turn is never generated
        // twice solely to switch from reasoning to streaming output.
        g.addConditionalEdges("think", AsyncEdgeAction.edge_async(this::routeAfterThink),
                Map.of("act", "act", "answer", "verify", "finish", "finish"));
        g.addEdge("act", "think");
        g.addEdge("answer", "verify");
        g.addEdge("finish", "verify");
        g.addEdge("verify", StateGraph.END);
        this.graph = g;
    }

    // ======================= 对外接口 =======================

    @Override
    public SseEmitter chat(String userMessage, String sessionId) {
        if (!agentEnabled) {
            SseEmitter disabled = new SseEmitter(1_000L);
            try {
                disabled.send(SseEmitter.event().name("error").data("Agent 功能当前已禁用。"));
            } catch (IOException ignored) {
            }
            disabled.complete();
            return disabled;
        }
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        if (userId == null) {
            SseEmitter unauthorized = new SseEmitter(1_000L);
            try {
                unauthorized.send(SseEmitter.event().name("error").data("请先登录。"));
            } catch (IOException ignored) {
            }
            unauthorized.complete();
            return unauthorized;
        }
        String sid = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "") : sessionId;
        SseEmitter emitter = new SseEmitter(Math.max(1_000L, turnTimeoutMs));

        // === Module 06：分布式令牌桶限流，防止 API 滥用 ===
        if (!rateLimiter.tryAcquire(userId)) {
            try {
                emitter.send(SseEmitter.event().name("error").data("请求过于频繁，请稍后再试。"));
            } catch (IOException ignored) {
            }
            emitter.complete();
            return emitter;
        }

        Runnable work = () -> {
            SseContext.setEmitter(emitter);
            try {
                completeChat(sid, buildInitialState(sid, userId, userMessage), emitter);
            } catch (Exception e) {
                log.error("Agent chat failed, session={}, error={}", sid,
                        SensitiveLogSanitizer.exceptionSummary(e));
                sendError(emitter, "抱歉，Agent 服务暂时不可用，请稍后再试。");
            } finally {
                SseContext.clear();
            }
        };
        AtomicReference<Future<?>> taskRef = new AtomicReference<>();
        try {
            ExecutorService executor = agentExecutor == null ? FALLBACK_EXECUTOR : agentExecutor;
            Future<?> task = executor.submit(work);
            taskRef.set(task);
        } catch (RejectedExecutionException e) {
            metric("rejected");
            sendError(emitter, "当前请求较多，请稍后再试。");
            return emitter;
        }
        emitter.onTimeout(() -> {
            Future<?> task = taskRef.get();
            if (task != null) task.cancel(true);
            metric("timeout");
            sendError(emitter, "Agent 请求超时，请稍后再试。");
        });
        emitter.onCompletion(() -> {
            Future<?> task = taskRef.get();
            if (task != null && !task.isDone()) {
                task.cancel(true);
                metric("cancelled");
            }
        });
        emitter.onError(error -> {
            Future<?> task = taskRef.get();
            if (task != null) {
                task.cancel(true);
                metric("cancelled");
            }
        });
        return emitter;
    }

    @Override
    public Result getHistory(String sessionId) {
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        if (userId == null) {
            throw new UnauthorizedException();
        }
        AgentSession session = sessionManager.getOrCreate(sessionId, userId);
        return Result.ok(session);
    }

    // ======================= 测试可用的同步入口 =======================

    /** 同步执行一次完整的图，返回最终状态（供异步线程调用，也便于单元测试） */
    AgentState executeGraph(Map<String, Object> initialState) throws Exception {
        return graph.compile().invoke(initialState).orElse(null);
    }

    /** 构建图初始状态 */
    Map<String, Object> buildInitialState(String sessionId, Long userId, String userMessage) {
        AgentSession session = sessionManager.getOrCreate(sessionId, userId);
        Map<String, Object> init = new HashMap<>();
        init.put("sessionId", sessionId);
        init.put("userId", userId);
        init.put("userInput", userMessage);
        init.put("messages", session.getMessages());
        init.put("summary", session.getSummary());
        init.put("version", session.getVersion());
        init.put("iteration", 0);
        init.put("pendingToolCalls", new ArrayList<ToolCall>());
        init.put("toolResults", new ArrayList<ToolResult>());
        init.put("turnMessages", new ArrayList<TurnMessage>());
        init.put("maxIterations", maxIterations);
        init.put("totalTokens", 0);
        init.put("shouldFinish", false);
        return init;
    }

    /** 图执行结束后：持久化会话 + 发送 done 事件（同步执行，便于测试） */
    void completeChat(String sessionId, Map<String, Object> initialState, SseEmitter emitter) throws Exception {
        long start = System.currentTimeMillis();
        AgentState finalState = executeGraph(initialState);
        if (finalState != null) {
            sessionManager.save(sessionId, finalState);
        }
        AgentResponse response = AgentResponse.builder()
                .sessionId(sessionId)
                .answer(finalState == null ? "" : finalState.getFinalAnswer())
                .totalTokens(finalState == null ? 0 : finalState.getTotalTokens())
                .toolCallCount(finalState == null ? 0 : finalState.getToolResults().size())
                .elapsedTimeMs(System.currentTimeMillis() - start)
                .build();
        if (emitter != null) {
            if (finalState != null && finalState.getFinalAnswer() != null
                    && !finalState.getFinalAnswer().isBlank()) {
                // Only the verified final answer is observable to the client.
                emitter.send(SseEmitter.event().name("chunk").data(finalState.getFinalAnswer()));
            }
            emitter.send(SseEmitter.event().name("done").data(JSONUtil.toJsonStr(response)));
            emitter.complete();
        }
    }

    // ======================= 图节点 =======================

    private Map<String, Object> sanitizeNode(AgentState state) {
        try {
            SanitizedInput cleaned = inputSanitizer.sanitize(state.getUserInput());
            // Never echo the complete user prompt to an SSE client before the
            // final safety gate; prompts may contain phones or other PII.
            sendEvent("thinking", "正在理解您的问题");
            return Map.of("userInput", cleaned.getCleanedText());
        } catch (SecurityViolationException e) {
            metric("safety_rejected");
            log.warn("Input rejected by sanitizer: {}", SensitiveLogSanitizer.exceptionSummary(e));
            sendEvent("error", "您的输入包含不安全内容，已被系统拦截。");
            return Map.of("finalAnswer", "您的输入包含不安全内容，已被系统拦截。", "shouldFinish", true);
        }
    }

    private Map<String, Object> thinkNode(AgentState state) {
        int iteration = state.getIteration() + 1;
        List<ChatMessage> messages = buildMessages(state);
        List<ToolSpecification> tools = toolRegistry.buildToolSpecifications();

        Response<AiMessage> response = llmClient.chatSync(messages, tools);
        AiMessage aiMessage = response.content();
        int tokens = response.tokenUsage() == null ? 0 : response.tokenUsage().totalTokenCount();

        Map<String, Object> updates = new HashMap<>();
        updates.put("iteration", iteration);
        updates.put("totalTokens", state.getTotalTokens() + tokens);

        List<ToolExecutionRequest> requests =
                aiMessage.toolExecutionRequests() == null ? Collections.emptyList() : aiMessage.toolExecutionRequests();
        if (!requests.isEmpty()) {
            List<ToolCall> pending = new ArrayList<>();
            for (ToolExecutionRequest req : requests) {
                pending.add(ToolCall.builder()
                        .id(req.id())
                        .name(req.name())
                        .arguments(req.arguments())
                        .build());
            }
            updates.put("pendingToolCalls", pending);
            updates.put("shouldFinish", false);
            // 保存带 tool_calls 的 assistant 消息：下一轮 think 重建上下文时，
            // tool 结果消息必须紧跟声明其 tool_calls 的 assistant 消息（OpenAI 兼容协议）
            List<TurnMessage> turn = new ArrayList<>(state.getTurnMessages());
            turn.add(TurnMessage.builder()
                    .role("assistant")
                    .content(aiMessage.text() == null ? "" : aiMessage.text())
                    .toolCalls(pending)
                    .build());
            updates.put("turnMessages", turn);
            sendToolCalls(pending);
        } else {
            updates.put("pendingToolCalls", Collections.emptyList());
            updates.put("currentThought", aiMessage.text());
            updates.put("finalAnswer", aiMessage.text());
            updates.put("shouldFinish", true);
        }
        return updates;
    }

    private Map<String, Object> actNode(AgentState state) {
        List<ToolResult> merged = new ArrayList<>(state.getToolResults());
        List<TurnMessage> turn = new ArrayList<>(state.getTurnMessages());
        for (ToolCall call : state.getPendingToolCalls()) {
            long start = System.currentTimeMillis();

            // === Module 06：敏感操作门禁（退款需二次确认、黑名单工具拒绝）===
            GuardDecision decision = sensitiveGuard.evaluate(
                    call.getName(), call.getArguments(), state.getUserId());
            ToolResult result;
            if ("DENIED".equals(decision.getAction())) {
                result = ToolResult.builder()
                        .toolCallId(call.getId())
                        .toolName(call.getName())
                        .success(false)
                        .result("{\"error\":\"" + decision.getMessage() + "\"}")
                        .executionTimeMs(0)
                        .build();
                sendEvent("tool_result", call.getName() + " 被拒绝：" + decision.getMessage());
            } else if ("CONFIRM".equals(decision.getAction())) {
                // 不执行，推 confirm 事件给前端弹窗；用户在 /agent/confirm 中批准后由门禁代为执行
                result = ToolResult.builder()
                        .toolCallId(call.getId())
                        .toolName(call.getName())
                        .success(true)
                        .result("{\"status\":\"AWAITING_CONFIRMATION\",\"message\":\""
                                + decision.getMessage() + "\",\"confirmationId\":\""
                                + decision.getConfirmationId() + "\"}")
                        .executionTimeMs(0)
                        .build();
                sendConfirmEvent(decision);
            } else {
                // === ALLOWED：直接执行 ===
                // 工具执行发生在 LangGraph 异步线程，UserHolder(ThreadLocal) 为空；
                // 先把图状态里的 userId 写回 UserHolder，工具内 getUser() 才能取到当前用户，执行完再清理。
                Long toolUserId = state.getUserId();
                if (toolUserId != null) {
                    UserDTO toolUser = new UserDTO();
                    toolUser.setId(toolUserId);
                    UserHolder.saveUser(toolUser);
                }
                String r;
                boolean success = true;
                try {
                    r = toolRegistry.execute(call.getName(), call.getArguments());
                } catch (Exception e) {
                    success = false;
                    r = "{\"error\":\"tool execution failed\"}";
                } finally {
                    UserHolder.removeUser();
                }
                long cost = System.currentTimeMillis() - start;
                result = ToolResult.builder()
                        .toolCallId(call.getId())
                        .toolName(call.getName())
                        .success(success)
                        .result(r)
                        .executionTimeMs(cost)
                        .build();
                sendEvent("tool_result", call.getName() + " 执行完成，耗时 " + cost + "ms");
            }
            merged.add(result);
            // 每个工具结果都按协议追加 tool 消息（紧跟其 assistant tool_calls 消息之后）
            turn.add(TurnMessage.builder()
                    .role("tool")
                    .toolCallId(call.getId())
                    .toolName(call.getName())
                    .content(result.getResult())
                    .build());
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put("toolResults", merged);
        updates.put("pendingToolCalls", Collections.emptyList());
        updates.put("turnMessages", turn);
        return updates;
    }

    private Map<String, Object> answerNode(AgentState state) {
        List<ChatMessage> messages = buildMessages(state);
        SseEmitter emitter = SseContext.getEmitter();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> textRef = new AtomicReference<>("");
        AtomicReference<Response<AiMessage>> responseRef = new AtomicReference<>();

        llmClient.chatStream(messages, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                textRef.updateAndGet(s -> s + token);
                // Buffer provider chunks. PII masking and policy checks run in
                // verifyNode before completeChat emits a chunk.
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                responseRef.set(response);
                AiMessage content = response.content();
                if (content != null && content.text() != null) {
                    textRef.set(content.text());
                }
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
            log.error("Streaming chat error: {}", SensitiveLogSanitizer.exceptionSummary(error));
                latch.countDown();
            }
        });

        try {
            if (!latch.await(STREAM_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Streaming chat timed out after {}s", STREAM_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("finalAnswer", textRef.get());
        Response<AiMessage> resp = responseRef.get();
        if (resp != null && resp.tokenUsage() != null) {
            updates.put("totalTokens", state.getTotalTokens() + resp.tokenUsage().totalTokenCount());
        }
        return updates;
    }

    private Map<String, Object> finishNode(AgentState state) {
        return Map.of("finalAnswer", "抱歉，我在多次尝试后仍未完成您的请求，请换个说法再试一次。",
                "shouldFinish", true);
    }

    private Map<String, Object> verifyNode(AgentState state) {
        String answer = state.getFinalAnswer();
        if (answer == null || answer.isBlank()) {
            return Map.of("finalAnswer", "抱歉，我暂时无法给出可靠的回答，请稍后再试。");
        }
        // Module 06：输出验证（幻觉检测 + PII 脱敏 + 长度截断）
        VerificationContext context = VerificationContext.builder()
                .userQuestion(state.getUserInput())
                .toolResults(state.getToolResults())
                .totalIterations(state.getIteration())
                .build();
        VerificationResult result = outputVerifier.verify(answer, context);
        if (result.getCorrectedOutput() != null && !result.getCorrectedOutput().isBlank()) {
            return Map.of("finalAnswer", result.getCorrectedOutput());
        }
        if (!result.isPassed()) {
            metric("verification_rejected");
            return Map.of("finalAnswer", "抱歉，我暂时无法给出可靠的回答，请稍后再试。");
        }
        return Map.of();
    }

    // ======================= 路由 =======================

    private String routeAfterSanitize(AgentState state) {
        return state.isShouldFinish() ? "end" : "think";
    }

    private String routeAfterThink(AgentState state) {
        if (state.getIteration() >= state.getMaxIterations()) {
            return "finish";
        }
        if (!state.getPendingToolCalls().isEmpty()) {
            return "act";
        }
        return "answer";
    }

    // ======================= 消息组装 =======================

    private List<ChatMessage> buildMessages(AgentState state) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(buildSystemPrompt()));
        // Module 06：注入历史压缩摘要（Prime Agent 风格），让 LLM 感知早前对话上下文
        if (state.getSummary() != null && !state.getSummary().isBlank()) {
            messages.add(SystemMessage.from("[对话摘要] " + state.getSummary()));
        }
        for (MessageRecord record : state.getMessages()) {
            if (record.getContent() == null || record.getContent().isBlank()) {
                continue;
            }
            switch (record.getRole() == null ? "" : record.getRole()) {
                case "user" -> messages.add(UserMessage.from(record.getContent()));
                case "assistant" -> messages.add(AiMessage.from(record.getContent()));
                case "system" -> messages.add(SystemMessage.from(record.getContent()));
                // 持久化历史中不含 tool 消息：tool 消息离开本轮后失去对应的 assistant tool_calls，
                // 发给 LLM 会违反协议，故一律跳过
                default -> { /* 忽略未知角色及历史 tool 记录 */ }
            }
        }
        if (state.getUserInput() != null && !state.getUserInput().isBlank()) {
            messages.add(UserMessage.from(state.getUserInput()));
        }
        // 本轮已发生的消息（assistant tool_calls + tool 结果，按序排列）追加在用户输入之后
        for (TurnMessage tm : state.getTurnMessages()) {
            if ("assistant".equals(tm.getRole())) {
                List<ToolExecutionRequest> requests = tm.getToolCalls() == null ? Collections.emptyList()
                        : tm.getToolCalls().stream()
                                .map(tc -> ToolExecutionRequest.builder()
                                        .id(tc.getId()).name(tc.getName()).arguments(tc.getArguments())
                                        .build())
                                .toList();
                if (requests.isEmpty()) {
                    messages.add(AiMessage.from(tm.getContent() == null ? "" : tm.getContent()));
                } else {
                    messages.add(AiMessage.from(requests));
                }
            } else if ("tool".equals(tm.getRole())) {
                messages.add(ToolExecutionResultMessage.from(tm.getToolCallId(), tm.getToolName(), tm.getContent()));
            }
        }
        return messages;
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是校园生活服务平台 CampusDeal 的智能助手。请用简洁、友好的中文回答用户。\n");
        sb.append("你可以使用以下工具完成用户的请求；工具结果会自动带回，请基于真实工具结果回答，不要编造：\n");
        for (ToolMeta meta : toolRegistry.listTools()) {
            sb.append("- ").append(meta.getName()).append("：").append(meta.getDescription()).append("\n");
        }
        return sb.toString();
    }

    // ======================= SSE 事件推送 =======================

    private void sendChunk(SseEmitter emitter, String token) {
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("chunk").data(token));
            } catch (IOException e) {
                log.warn("SSE chunk send failed: {}", SensitiveLogSanitizer.exceptionSummary(e));
            }
        }
    }

    private void sendToolCalls(List<ToolCall> calls) {
        for (ToolCall call : calls) {
            sendEvent("tool_call", JSONUtil.toJsonStr(call));
        }
    }

    /** 推送敏感操作确认事件（前端弹窗），content 为 GuardDecision JSON */
    private void sendConfirmEvent(GuardDecision decision) {
        SseEmitter emitter = SseContext.getEmitter();
        if (emitter == null) {
            return;
        }
        AgentEvent event = AgentEvent.builder()
                .type("confirm")
                .content(JSONUtil.toJsonStr(decision))
                .timestamp(System.currentTimeMillis())
                .build();
        try {
            emitter.send(SseEmitter.event().name("confirm").data(JSONUtil.toJsonStr(event)));
        } catch (IOException e) {
            log.warn("SSE confirm event send failed: {}", SensitiveLogSanitizer.exceptionSummary(e));
        }
    }

    private void sendEvent(String type, String content) {
        SseEmitter emitter = SseContext.getEmitter();
        if (emitter == null) {
            return;
        }
        AgentEvent event = AgentEvent.builder()
                .type(type)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
        try {
            emitter.send(SseEmitter.event().name(type).data(JSONUtil.toJsonStr(event)));
        } catch (IOException e) {
            log.warn("SSE event [{}] send failed: {}", type, SensitiveLogSanitizer.exceptionSummary(e));
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("error").data(message));
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    private void metric(String outcome) {
        if (reliabilityMetrics != null) {
            reliabilityMetrics.increment("campusdeal.agent.turn", outcome);
        }
    }
}
