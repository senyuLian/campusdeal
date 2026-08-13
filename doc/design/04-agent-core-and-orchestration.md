# 设计文档 04：Agent 核心框架与编排模块

> 所属 Phase：Phase 3（AI 智能助手 Agent）
> 模块定位：Agent 的大脑——LLM 客户端、LangGraph4j 状态图编排、工具注册、SSE 流式输出
> 依赖关系：依赖 langgraph4j + langchain4j 库，不依赖 RAG/安全模块的具体实现

---

## 1. 模块概述

### 1.1 职责

| 组件 | 职责 |
|---|---|
| **LLM Client** | 封装 DeepSeek API 调用，支持 chat/completions 同步 + SSE 流式 |
| **Agent State** | 定义 Agent 在整个对话生命周期中的状态结构 |
| **State Graph** | LangGraph4j `StateGraph` 定义 ReAct 循环：think → act → observe → (loop / finish) |
| **Tool Registry** | 注册/查找/调用 Function Call 工具 |
| **Tool Adapter** | 将现有 Service 方法适配为 LangChain4j `@Tool` 注解的方法 |
| **SSE Controller** | `text/event-stream` 流式推送 Agent 思考过程和最终回复 |
| **Session Manager** | 管理多轮对话会话（基于 Redis Hash 存储） |

### 1.2 模块边界

```
                    ┌───────────────────────────────────────────┐
                    │  本模块                                     │
  前端 SSE 请求 ──▶│  AgentController(SSE)                       │
                    │      │                                     │
                    │  AgentOrchestrator                          │
                    │      │                                     │
                    │      ├─ LLM Client (DeepSeek API)          │
                    │      ├─ StateGraph (ReAct 循环)             │
                    │      ├─ ToolRegistry (工具调度)              │
                    │      └─ SessionManager (Redis)              │
                    │                                             │
                    │  工具列表（通过 ToolAdapter 适配）：         │
                    │  · QueryOrderTool (查订单)                  │
                    │  · SearchMerchantTool (搜商家)              │
                    │  · QueryCouponTool (查优惠券)               │
                    │  · ApplyRefundTool (申请退款)               │
                    │  · SearchFaqTool (搜 FAQ → RAG 模块)        │
                    └───────────────────────────────────────────┘
```

### 1.3 与其他模块的关系

```
本模块 → 调用 05-RAG 模块的 SearchFaqTool（通过 Tool 接口，不直接依赖实现）
本模块 → 调用 06-安全模块的 InputSanitizer / OutputVerifier（前置/后置）
本模块 → 不依赖 Phase 2 模块
本模块 → 被前端 chat UI（06-UI）通过 SSE 连接
```

---

## 2. 对外接口（API 契约）

### 2.1 SSE Controller

```java
@RestController
@RequestMapping("/agent")
public class AgentController {

    @Resource private AgentOrchestrator orchestrator;

    /**
     * 发起对话（SSE 流式）
     * POST /agent/chat
     * Content-Type: application/x-www-form-urlencoded
     * Body: message=用户问题&sessionId=会话ID（可选，首次为空）
     *
     * Response: text/event-stream
     *
     * 事件类型：
     *   event: thinking    → data: "正在查询您的订单信息..."
     *   event: tool_call   → data: {"tool":"queryOrders","args":{"userId":1001}}
     *   event: chunk       → data: "您"
     *   event: chunk       → data: "的"
     *   event: chunk       → data: "订单"
     *   ...
     *   event: done        → data: {"sessionId":"sess_xxx"}
     *   event: error       → data: {"message":"抱歉，处理出错了"}
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @RequestParam String message,
            @RequestParam(required = false) String sessionId) {
        return orchestrator.chat(message, sessionId);
    }

    /**
     * 获取历史消息
     * GET /agent/history/{sessionId}
     */
    @GetMapping("/history/{sessionId}")
    public Result history(@PathVariable String sessionId) {
        return orchestrator.getHistory(sessionId);
    }
}
```

### 2.2 SSE 事件格式

```java
/**
 * SSE 事件封装
 */
@Data
@Builder
public class AgentEvent {
    /** 事件类型：thinking / tool_call / chunk / done / error */
    private String type;
    /** 事件数据（text/event-stream 的 data: 字段） */
    private String data;
}

/**
 * Agent 最终响应
 */
@Data
@Builder
public class AgentResponse {
    private String sessionId;
    private String answer;
    private List<ToolCallRecord> toolCalls;
    private int totalTokens;
}
```

### 2.3 AgentOrchestrator 接口

```java
public interface AgentOrchestrator {
    /**
     * 核心对话方法，返回 SSE 流
     */
    SseEmitter chat(String userMessage, String sessionId);

    /**
     * 获取会话历史
     */
    List<MessageRecord> getHistory(String sessionId);

    /**
     * 清空会话上下文（用于调试）
     */
    void clearSession(String sessionId);
}
```

---

## 3. 数据模型

### 3.1 Agent 状态（LangGraph4j State）

```java
@Data
@Builder
public class AgentState {
    /** 会话 ID */
    private String sessionId;
    /** 用户 ID */
    private Long userId;
    /** 当前用户消息 */
    private String userInput;
    /** 完整对话历史 */
    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();
    /** ReAct 循环迭代计数 */
    @Builder.Default
    private int iteration = 0;
    /** 当前 LLM 思考输出 */
    private String currentThought;
    /** 待执行的工具列表（LLM 返回的 function call） */
    @Builder.Default
    private List<ToolCall> pendingToolCalls = new ArrayList<>();
    /** 工具执行结果列表 */
    @Builder.Default
    private List<ToolResult> toolResults = new ArrayList<>();
    /** Agent 最终回复 */
    private String finalAnswer;
    /** 是否应该终止循环 */
    @Builder.Default
    private boolean shouldFinish = false;
    /** 总 token 消耗 */
    @Builder.Default
    private int totalTokens = 0;
    /** 最大迭代次数（防止无限循环） */
    @Builder.Default
    private int maxIterations = 5;
}

@Data
@Builder
public class ToolCall {
    private String id;        // 调用 ID（LLM 生成）
    private String name;      // 工具名
    private String arguments; // JSON 参数字符串
}
```

### 3.2 对话消息

```java
@Data
@Builder
public class ChatMessage {
    private String role;       // system / user / assistant / tool
    private String content;    // 文本内容
    private String toolCallId;  // tool 消息关联的 tool call id
    private Long timestamp;
}

@Data
@Builder
public class MessageRecord implements Serializable {
    private String role;
    private String content;
    private Long timestamp;
}
```

### 3.3 工具调用记录

```java
@Data
@Builder
public class ToolCallRecord {
    private String toolName;
    private String arguments;
    private String result;      // JSON 字符串
    private long executionTimeMs;
    private boolean success;
}
```

### 3.4 会话存储（Redis）

```
Key:   agent:session:{sessionId}
Type:  Hash
Fields:
  userId      → "1001"
  created_at  → "1723248000000"
  last_active → "1723248100000"
  messages    → JSON Array of MessageRecord (最近 20 条)
  summary     → 上下文的 Prime Agent 风格压缩摘要

TTL: 30 分钟（会话级别的滑动过期）
```

### 3.5 工具注册元数据

```java
@Data
@Builder
public class ToolMeta {
    /** 工具唯一名称 */
    private String name;
    /** 功能描述（用于 LLM prompt） */
    private String description;
    /** 参数 Schema（JSON Schema 格式） */
    private JsonNode parametersSchema;
    /** 是否需要用户确认（退款类操作） */
    @Builder.Default
    private boolean requireConfirmation = false;
    /** 对应用户权限列表 */
    @Builder.Default
    private List<String> requiredPermissions = new ArrayList<>();
}
```

---

## 4. 核心类设计

### 4.1 类图总览

```
┌──────────────────────────┐     ┌──────────────────────────┐
│    AgentController        │     │    AgentOrchestrator      │
│    (SSE 端点)             │     │    (核心编排)              │
├──────────────────────────┤     ├──────────────────────────┤
│ + chat(msg, sid): SseEmit│────▶│ + chat(msg, sid): SseEmit│
│ + history(sid): Result   │     │ - executeGraph(): void   │
└──────────────────────────┘     └──────────┬───────────────┘
                                            │
                    ┌───────────────────────┼───────────────────────┐
                    │                       │                       │
                    ▼                       ▼                       ▼
          ┌─────────────────┐   ┌──────────────────┐   ┌──────────────────┐
          │   LLM Client     │   │  ToolRegistry     │   │  SessionManager  │
          │  (DeepSeek API)  │   │  (工具注册/查找)   │   │  (Redis 会话)    │
          ├─────────────────┤   ├──────────────────┤   ├──────────────────┤
          │ + chatSync()    │   │ + registerTool()  │   │ + getSession()   │
          │ + chatStream()  │   │ + executeTool()   │   │ + saveSession()  │
          └─────────────────┘   │ + listTools()     │   │ + compact()      │
                                └──────────────────┘   └──────────────────┘
```

### 4.2 LLM Client 实现

```java
@Component
@Slf4j
public class DeepSeekChatClient {

    private final OpenAiChatModel model;
    private final ChatLanguageModel streamingModel;

    public DeepSeekChatClient(
            @Value("${campusdeal.deepseek.api-key}") String apiKey,
            @Value("${campusdeal.deepseek.base-url:https://api.deepseek.com}") String baseUrl) {

        // 同步调用模型
        this.model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl + "/v1")
                .modelName("deepseek-chat")
                .temperature(0.1)   // 低温度 = 更确定的工具调用
                .maxTokens(4096)
                .build();

        // 流式输出模型
        this.streamingModel = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl + "/v1")
                .modelName("deepseek-chat")
                .temperature(0.1)
                .maxTokens(4096)
                .build();
    }

    /**
     * 同步调用（Think 阶段：获取 LLM 的思考 + 可能的 function call）
     */
    public Response<AiMessage> chatSync(List<ChatMessage> messages, List<ToolSpecification> tools) {
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(tools)
                .build();
        return model.generate(request);
    }

    /**
     * 流式调用（Answer 阶段：边生成边 push 到 SSE）
     */
    public void chatStream(List<ChatMessage> messages, StreamingResponseHandler handler) {
        streamingModel.generate(messages, handler);
    }
}
```

### 4.3 工具注册表实现

```java
@Component
public class ToolRegistry {

    /** 工具名 → 工具元数据 */
    private final Map<String, ToolMeta> toolMetas = new ConcurrentHashMap<>();

    /** 工具名 → 实际执行工具的对象 */
    private final Map<String, Object> toolBeans = new ConcurrentHashMap<>();

    /**
     * 注册工具（通过 ToolAdapter 注解扫描）
     */
    public void registerTool(String name, ToolMeta meta, Object toolBean) {
        toolMetas.put(name, meta);
        toolBeans.put(name, toolBean);
        log.info("Tool registered: {} ({} tools total)", name, toolMetas.size());
    }

    /**
     * 获取所有工具规格（用于生成 LLM 的 tools 参数）
     */
    public List<ToolSpecification> getToolSpecifications() {
        return toolMetas.values().stream()
                .map(meta -> ToolSpecification.builder()
                        .name(meta.getName())
                        .description(meta.getDescription())
                        .parameters(meta.getParametersSchema())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 执行工具调用（通过反射调用注册的工具 Bean）
     */
    public ToolResult executeTool(ToolCall toolCall) {
        long start = System.currentTimeMillis();
        ToolMeta meta = toolMetas.get(toolCall.getName());
        if (meta == null) {
            return ToolResult.builder()
                    .success(false)
                    .result("Unknown tool: " + toolCall.getName())
                    .build();
        }

        try {
            Object bean = toolBeans.get(toolCall.getName());
            // 通过反射调用工具方法
            Object result = invokeToolMethod(bean, toolCall.getArguments());
            return ToolResult.builder()
                    .success(true)
                    .result(JSONUtil.toJsonStr(result))
                    .executionTimeMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolCall.getName(), e);
            return ToolResult.builder()
                    .success(false)
                    .result("Error: " + e.getMessage())
                    .executionTimeMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    private Object invokeToolMethod(Object bean, String arguments) throws Exception {
        // 使用 JSON 反序列化参数，反射调用对应方法
        JsonNode args = JSONUtil.parseObj(arguments);
        Method method = bean.getClass().getMethods()[0]; // 简化：取第一个 public 方法
        // ... 参数转换 + 反射调用
        return method.invoke(bean /* params */);
    }
}
```

### 4.4 LangGraph4j StateGraph 编排（核心）

```java
@Component
@Slf4j
public class AgentOrchestratorImpl implements AgentOrchestrator {

    @Resource private DeepSeekChatClient llmClient;
    @Resource private ToolRegistry toolRegistry;
    @Resource private SessionManager sessionManager;
    @Resource private InputSanitizer inputSanitizer;
    @Resource private OutputVerifier outputVerifier;

    private StateGraph<AgentState> graph;

    @PostConstruct
    public void buildGraph() {
        graph = new StateGraph<>(AgentState.class)
            // === 节点定义 ===
            .addNode("sanitize", this::sanitizeNode)     // 输入安全清洗
            .addNode("think", this::thinkNode)           // LLM 思考 + function call 决策
            .addNode("act", this::actNode)               // 执行工具调用
            .addNode("answer", this::answerNode)         // 流式生成最终回复
            .addNode("verify", this::verifyNode)         // 输出安全验证

            // === 边与条件 ===
            .setEntryPoint("sanitize")
            .addEdge("sanitize", "think")
            .addConditionalEdges("think", this::routeAfterThink,
                    Map.of("act", "act",    // 需要调工具
                           "answer", "answer",  // 直接回复
                           "finish", "verify"  // 超过迭代上限
                    ))
            .addEdge("act", "think")          // 工具结果 → 回到思考
            .addEdge("answer", "verify")
            .setFinishPoint("verify");
    }

    // ================ 节点实现 ================

    /**
     * sanitize 节点：输入安全清洗
     */
    private AgentState sanitizeNode(AgentState state) {
        String cleaned = inputSanitizer.sanitize(state.getUserInput());
        state.setUserInput(cleaned);
        return state;
    }

    /**
     * think 节点：调用 LLM 获取下一步行动
     */
    private AgentState thinkNode(AgentState state) {
        state.setIteration(state.getIteration() + 1);

        // 构建 messages（含 system prompt + 历史 + tool results）
        List<ChatMessage> messages = buildMessages(state);

        // 调用 LLM（同步）
        Response<AiMessage> response = llmClient.chatSync(
                messages, toolRegistry.getToolSpecifications());
        AiMessage aiMsg = response.content();

        state.setTotalTokens(state.getTotalTokens() + response.tokenUsage().totalTokenCount());

        if (aiMsg.hasToolExecutionRequests()) {
            // LLM 决定调用工具
            state.setPendingToolCalls(
                aiMsg.toolExecutionRequests().stream()
                    .map(req -> ToolCall.builder()
                            .id(req.id())
                            .name(req.name())
                            .arguments(req.arguments())
                            .build())
                    .collect(Collectors.toList())
            );
        } else {
            // LLM 决定直接回复
            state.setCurrentThought(aiMsg.text());
            state.setShouldFinish(true);
        }

        return state;
    }

    /**
     * act 节点：执行工具调用
     */
    private AgentState actNode(AgentState state) {
        List<ToolResult> results = state.getPendingToolCalls().stream()
                .map(tc -> {
                    ToolResult tr = toolRegistry.executeTool(tc);
                    log.info("Tool {} executed in {}ms: success={}",
                            tc.getName(), tr.getExecutionTimeMs(), tr.isSuccess());
                    return tr;
                })
                .collect(Collectors.toList());
        state.setToolResults(results);
        state.setPendingToolCalls(Collections.emptyList());
        return state;
    }

    /**
     * answer 节点：流式生成最终回复（通过 StreamingResponseHandler push 到 SSE）
     */
    private AgentState answerNode(AgentState state) {
        List<ChatMessage> messages = buildMessages(state);
        // 最终回复通过流式调用，chunk 会实时 push 到 SseEmitter
        // SseEmitter 引用通过 ThreadLocal 传递
        final SseEmitter emitter = SseContext.getEmitter();

        llmClient.chatStream(messages, new StreamingResponseHandler() {
            @Override
            public void onNext(String token) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("chunk")
                            .data(token));
                } catch (IOException e) {
                    log.error("SSE send failed", e);
                }
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                state.setFinalAnswer(response.content().text());
                state.setTotalTokens(state.getTotalTokens() +
                        response.tokenUsage().totalTokenCount());
            }

            @Override
            public void onError(Throwable error) {
                log.error("Stream error", error);
            }
        });

        return state;
    }

    /**
     * verify 节点：输出安全验证
     */
    private AgentState verifyNode(AgentState state) {
        outputVerifier.verify(state.getFinalAnswer());  // 抛异常 = 替换为安全回复
        return state;
    }

    /**
     * 条件路由：Think 后决定下一步
     */
    private String routeAfterThink(AgentState state) {
        if (state.getIteration() >= state.getMaxIterations()) {
            return "finish";  // 超限 → 强制结束
        }
        if (!state.getPendingToolCalls().isEmpty()) {
            return "act";     // 需要调工具
        }
        return "answer";      // LLM 认为可以回复了
    }

    // ================ 核心入口 ================

    @Override
    public SseEmitter chat(String userMessage, String sessionId) {
        // 创建或复用 SseEmitter
        String sid = sessionId != null ? sessionId : UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(300_000L);  // 5 分钟超时
        SseContext.setEmitter(emitter);

        // 加载历史会话
        AgentSession session = sessionManager.getOrCreate(sid, UserHolder.getUser().getId());
        List<MessageRecord> history = session.getMessages();

        // 构建 AgentState
        AgentState state = AgentState.builder()
                .sessionId(sid)
                .userId(UserHolder.getUser().getId())
                .userInput(userMessage)
                .messages(buildChatMessages(history))
                .build();

        // 异步执行状态图（不阻塞 Controller 线程）
        CompletableFuture.runAsync(() -> {
            try {
                graph.invoke(state);

                // 保存会话
                sessionManager.save(sid, state);

                // 发送完成事件
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(JSONUtil.toJsonStr(AgentResponse.builder()
                                .sessionId(sid)
                                .answer(state.getFinalAnswer())
                                .totalTokens(state.getTotalTokens())
                                .build())));
                emitter.complete();
            } catch (Exception e) {
                log.error("Agent execution failed", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"message\":\"抱歉，我处理您的请求时遇到了问题\"}"));
                    emitter.complete();
                } catch (IOException ex) { /* ignore */ }
            } finally {
                SseContext.clear();
            }
        });

        return emitter;
    }
}
```

### 4.5 SessionManager 实现

```java
@Component
@Slf4j
public class SessionManager {

    @Resource private StringRedisTemplate stringRedisTemplate;

    private static final String SESSION_KEY_PREFIX = "agent:session:";
    private static final int SESSION_TTL_MINUTES = 30;
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int COMPACTION_THRESHOLD = 15; // 超过15条触发压缩

    public AgentSession getOrCreate(String sessionId, Long userId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);
        if (map.isEmpty()) {
            return AgentSession.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .messages(new ArrayList<>())
                    .build();
        }
        // 反序列化
        List<MessageRecord> messages = JSONUtil.toList(
                (String) map.get("messages"), MessageRecord.class);
        return AgentSession.builder()
                .sessionId(sessionId)
                .userId(Long.valueOf((String) map.get("userId")))
                .messages(messages)
                .summary((String) map.get("summary"))
                .build();
    }

    public void save(String sessionId, AgentState state) {
        // 添加用户消息和 AI 回复到历史
        List<MessageRecord> history = new ArrayList<>(state.getMessages());
        history.add(MessageRecord.builder()
                .role("user").content(state.getUserInput())
                .timestamp(System.currentTimeMillis()).build());
        history.add(MessageRecord.builder()
                .role("assistant").content(state.getFinalAnswer())
                .timestamp(System.currentTimeMillis()).build());

        // 只保留最近 20 条
        if (history.size() > MAX_HISTORY_MESSAGES) {
            history = history.subList(history.size() - MAX_HISTORY_MESSAGES, history.size());
        }

        // 压缩（超过15条时触发）
        String summary = state.getCompactedSummary();
        if (history.size() > COMPACTION_THRESHOLD) {
            summary = compactHistory(history);
        }

        String key = SESSION_KEY_PREFIX + sessionId;
        Map<String, String> data = new HashMap<>();
        data.put("userId", state.getUserId().toString());
        data.put("messages", JSONUtil.toJsonStr(history));
        if (summary != null) data.put("summary", summary);

        stringRedisTemplate.opsForHash().putAll(key, data);
        stringRedisTemplate.expire(key, Duration.ofMinutes(SESSION_TTL_MINUTES));
    }

    private String compactHistory(List<MessageRecord> history) {
        // 保留最近 5 条原始消息 + 前部摘要
        // 摘要格式参考 Prime Agent 的结构化压缩：
        // Goal / Progress / Key Info / Next Steps
        //
        // 实际实现见文档 06：安全与上下文管理模块
        return "";  // placeholder
    }
}
```

### 4.6 工具适配器（以 QueryOrderTool 为例）

```java
/**
 * 工具 Bean：查询用户订单
 */
@Component("queryOrders")
public class QueryOrderTool {

    @Resource private ICouponOrderService couponOrderService;

    /**
     * 查询用户的秒杀订单
     * @param status 订单状态（可选：0=全部, 1=已支付, 2=已退款）
     * @return 订单列表
     */
    public String execute(Integer status) {
        Long userId = UserHolder.getUser().getId();
        List<CouponOrder> orders = couponOrderService.lambdaQuery()
                .eq(CouponOrder::getUserId, userId)
                .eq(status != null && status > 0, CouponOrder::getStatus, status)
                .orderByDesc(CouponOrder::getCreateTime)
                .last("LIMIT 10")
                .list();
        return JSONUtil.toJsonStr(Result.ok(orders));
    }
}
```

### 4.7 ToolAutoRegister（启动时自动扫描注册）

```java
@Component
public class ToolAutoRegister implements ApplicationContextAware {

    @Resource private ToolRegistry toolRegistry;

    /**
     * Spring 容器启动后，扫描所有 @Tool 注解的 Bean，自动注册到 ToolRegistry
     */
    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        Map<String, Object> toolBeans = ctx.getBeansWithAnnotation(ToolAnnotation.class);
        for (Map.Entry<String, Object> entry : toolBeans.entrySet()) {
            Object bean = entry.getValue();
            ToolAnnotation annotation = bean.getClass().getAnnotation(ToolAnnotation.class);

            ToolMeta meta = ToolMeta.builder()
                    .name(annotation.name())
                    .description(annotation.description())
                    .parametersSchema(buildSchema(bean.getClass()))
                    .requireConfirmation(annotation.requireConfirmation())
                    .requiredPermissions(Arrays.asList(annotation.requiredPermissions()))
                    .build();

            toolRegistry.registerTool(annotation.name(), meta, bean);
        }
    }

    private JsonNode buildSchema(Class<?> toolClass) {
        // 通过反射解析方法参数生成 JSON Schema
        Method execMethod = toolClass.getMethods()[0];  // execute 方法
        // ... 参数 → JSON Schema 映射
        return JSONUtil.parseObj("""
            {
              "type": "object",
              "properties": {
                "status": {
                  "type": "integer",
                  "description": "订单状态：0=全部, 1=已支付, 2=已退款"
                }
              }
            }
            """);
    }
}
```

---

## 5. 序列图

### 5.1 完整对话流程（ReAct 循环）

```
  SSE Client    AgentController   Orchestrator   LLMClient   ToolRegistry   Service Layer
     │               │                 │              │            │              │
     │ POST /chat    │                 │              │            │              │
     │──────────────▶│  chat(msg,sid) │              │            │              │
     │               │───────────────▶│              │            │              │
     │               │    SseEmitter  │              │            │              │
     │               │◀───────────────│              │            │              │
     │               │                 │              │            │              │
     │               │                 │ [async] invoke graph                     │
     │               │                 │── sanitize                               │
     │               │                 │── think                                  │
     │               │                 │    chatSync  │            │              │
     │               │                 │─────────────▶│            │              │
     │               │                 │ AiMessage +  │            │              │
     │               │                 │ FunctionCall │            │              │
     │               │                 │◀─────────────│            │              │
     │               │                 │              │            │              │
event: thinking     │                 │              │            │              │
◀───────────────────┼─────────────────│              │            │              │
data: "正在查询..."  │                 │              │            │              │
     │               │                 │              │            │              │
     │               │                 │── act (route: "act")     │              │
     │               │                 │   executeTool│            │              │
     │               │                 │─────────────────────────▶│              │
     │               │                 │              │            │ queryOrders  │
     │               │                 │              │            │──────────────▶│
     │               │                 │              │            │   orders     │
     │               │                 │              │            │◀──────────────│
     │               │                 │  ToolResult  │            │              │
     │               │                 │◀─────────────────────────│              │
     │               │                 │              │            │              │
event: tool_call    │                 │              │            │              │
◀───────────────────┼─────────────────│              │            │              │
data: {"tool":...}  │                 │              │            │              │
     │               │                 │              │            │              │
     │               │                 │── think (with tool result)             │
     │               │                 │   chatSync   │            │              │
     │               │                 │─────────────▶│            │              │
     │               │                 │text: "您的...│            │              │
     │               │                 │◀─────────────│            │              │
     │               │                 │              │            │              │
     │               │                 │── answer (route: "answer")              │
event: chunk         │                 │   chatStream │            │              │
◀───────────────────┼─────────────────│─────────────▶│            │              │
data: "您"          │                 │   token      │            │              │
◀───────────────────┼─────────────────│◀─────────────│            │              │
     ... (tokens streaming)           │              │            │              │
event: chunk         │                 │              │            │              │
◀───────────────────┼─────────────────│              │            │              │
     │               │                 │── verify     │            │              │
event: done         │                 │              │            │              │
◀───────────────────┼─────────────────│              │            │              │
     │               │                 │              │            │              │
     │               │                 │ save session (Redis)     │              │
```

### 5.2 多轮对话演进

```
  Session 开始
       │
       ▼
  ┌──────────────────────┐
  │ 1. 用户: "帮我查最近的订单" │
  │    状态: think→act(queryOrders)→think→answer  │
  │    history: [user, assistant]                 │
  └─────────┬────────────┘
            │
            ▼
  ┌──────────────────────┐
  │ 2. 用户: "第一单能退款吗" │
  │    状态: think→act(queryOrders)→act(applyRefund)→  │
  │           think→answer                            │
  │    注意: LLM 根据上下文理解"第一单"指代，无需用户重复 │
  │    history: [user, assistant, user, assistant]    │
  └─────────┬────────────┘
            │
            ▼
  ┌──────────────────────┐
  │ 3. 达到 15 条 → 触发压缩 │
  │    compact: Prime Agent 风格摘要                  │
  │    history: [summary, ...recent 5]               │
  └──────────────────────┘
```

### 5.3 System Prompt 结构

```
┌─────────────────────────────────────────────────────┐
│  System Prompt (每次 think 调用时作为第一条消息)      │
│                                                     │
│  ## 角色                                              │
│  你是 CampusDeal 校园生活服务平台的智能助手              │
│                                                     │
│  ## 可用工具                                           │
│  1. queryOrders - 查询用户订单                         │
│  2. searchMerchants - 搜索周边商家                     │
│  3. queryCoupons - 查询可领取的优惠券                   │
│  4. applyRefund - 申请退款                             │
│  5. searchFaq - 搜索 FAQ 知识库                        │
│                                                     │
│  ## 行为约束                                           │
│  - 不要编造没有查到数据                                 │
│  - 退款操作必须明确确认                                 │
│  - 涉及金额/库存时引用精确数字                          │
│  - 用户信息来自 UserHolder，不要询问                    │
│                                                     │
│  ## 当前上下文摘要                                     │
│  {session.summary}                                  │
└─────────────────────────────────────────────────────┘
```

---

## 6. 测试策略

### 6.1 测试清单

#### LLM Client 测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| LL-01 | 同步调用返回文本 | Mock LLM API 返回固定 JSON | chatSync | 返回 AiMessage，hasToolExecutionRequests()=false |
| LL-02 | 同步调用返回 function call | Mock LLM API 返回含 tool_calls 的 JSON | chatSync | hasToolExecutionRequests()=true，toolCalls 正确解析 |
| LL-03 | 流式调用 | Mock 流式 API，逐 token 返回 | chatStream | handler.onNext 逐个收到 token |
| LL-04 | API 超时 | Mock 返回 5s 超时 | chatSync | 重试 1 次后抛异常 |
| LL-05 | API 返回 429 | Mock 返回 429 状态码 | chatSync | 重试 3 次（指数退避），仍失败则抛异常 |

#### StateGraph 导航测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| SG-01 | 简单问答无工具 | LLM 返回纯文本 | graph.invoke | 走 think→answer→verify |
| SG-02 | 需要 1 个工具 | LLM 返回 function call | graph.invoke | 走 think→act→think→answer→verify |
| SG-03 | 需要 2 个工具 | LLM 返回 2 个 function call | graph.invoke | 走 think→act→think→answer→verify |
| SG-04 | 迭代超限 | LLM 一直返回 function call | graph.invoke | 第5次 think 后 route 到 verify |
| SG-05 | 工具执行失败 | Tool 抛异常 | graph.invoke | ToolResult.success=false，LLM 收到错误信息 |

#### ToolRegistry 测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| TR-01 | 注册并查找 | registerTool("queryOrders", ...) | listTools | 列表中包含 queryOrders |
| TR-02 | 执行已注册工具 | 注册 queryOrderTool Bean | executeTool(ToolCall) | 返回 ToolResult，success=true |
| TR-03 | 执行未注册工具 | — | executeTool(unknown) | ToolResult.success=false |
| TR-04 | 获取 LLM 规格 | 注册 5 个工具 | getToolSpecifications() | 返回 5 个 ToolSpecification |

#### Session 持久化测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| SP-01 | 新建会话 | Redis 无该 key | getOrCreate | 返回空会话 |
| SP-02 | 加载已有会话 | Redis 存有 5 条消息 | getOrCreate | messages 列表含 5 条 |
| SP-03 | 保存会话 | 5 条历史 + 新 1 轮 | save | Redis Hash 有 7 条消息 |
| SP-04 | 超限截断 | 25 条历史 | save | 只保留最近 20 条 |
| SP-05 | 压缩触发 | 16 条历史 | save | summary 字段非空，messages 含摘要 |

### 6.2 测试代码示例

```java
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock private DeepSeekChatClient llmClient;
    @Mock private ToolRegistry toolRegistry;
    @Mock private SessionManager sessionManager;
    @Mock private InputSanitizer inputSanitizer;
    @Mock private OutputVerifier outputVerifier;

    @InjectMocks private AgentOrchestratorImpl orchestrator;

    @BeforeEach
    void setUp() {
        // 初始化 StateGraph
        orchestrator.buildGraph();

        // Mock UserHolder
        UserDTO mockUser = new UserDTO();
        mockUser.setId(1001L);
        UserHolder.saveUser(mockUser);
    }

    @Test
    @DisplayName("SG-01: 简单问答，无工具调用，直接回复")
    void shouldAnswerDirectlyWithoutTools() {
        // Given
        String userMsg = "你们平台有什么功能？";
        when(inputSanitizer.sanitize(anyString())).thenReturn(userMsg);

        AiMessage aiMsg = AiMessage.from("我们平台提供校园生活服务...");
        Response<AiMessage> llmResponse = Response.from(aiMsg, new TokenUsage(50, 30));
        when(llmClient.chatSync(anyList(), anyList())).thenReturn(llmResponse);

        // Mock SessionManager
        AgentSession session = AgentSession.builder()
                .sessionId("test-sess").userId(1001L)
                .messages(new ArrayList<>()).build();
        when(sessionManager.getOrCreate(anyString(), anyLong())).thenReturn(session);

        // When: 直接调用 graph.invoke（不使用 SseEmitter）
        SseEmitter emitter = orchestrator.chat(userMsg, null);

        // Then: 验证 LLM 被调用了 1 次
        verify(llmClient).chatSync(anyList(), anyList());
        // 验证 session 被保存
        verify(sessionManager).save(anyString(), any(AgentState.class));
    }

    @Test
    @DisplayName("SG-02: 需要工具调用后回答")
    void shouldCallToolThenAnswer() {
        // Given
        String userMsg = "帮我查一下我的订单";
        when(inputSanitizer.sanitize(anyString())).thenReturn(userMsg);

        // 第一次 LLM 调用返回 function call
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_1").name("queryOrders")
                .arguments("{\"status\":0}").build();
        AiMessage fcMsg = AiMessage.from(toolReq);
        Response<AiMessage> fcResponse = Response.from(fcMsg, new TokenUsage(40, 20));
        when(llmClient.chatSync(anyList(), anyList()))
                .thenReturn(fcResponse)   // 第一次：function call
                .thenReturn(Response.from(    // 第二次：最终回复
                    AiMessage.from("您有3个订单..."),
                    new TokenUsage(80, 40)));

        // Mock tool execution
        ToolResult toolResult = ToolResult.builder()
                .success(true).result("[{\"id\":1,\"status\":\"PAID\"}]")
                .executionTimeMs(5).build();
        when(toolRegistry.executeTool(any())).thenReturn(toolResult);

        // When
        AgentState state = AgentState.builder()
                .sessionId("test").userId(1001L)
                .userInput(userMsg)
                .messages(new ArrayList<>())
                .build();

        // 直接调用 graph（测试模式，不使用模拟 SseEmitter）
        // graph.invoke(state) 在此处仅验证 routing 逻辑
    }
}
```

### 6.3 集成测试

```java
@SpringBootTest
@DisplayName("Agent E2E 测试（Mock LLM API）")
class AgentIntegrationTest {

    @Autowired private AgentOrchestrator orchestrator;
    @Autowired private ToolRegistry toolRegistry;

    @Test
    @DisplayName("完整 ReAct 循环：查订单 → 回答")
    void shouldExecuteFullReactCycle() throws Exception {
        // 使用 WireMock 模拟 DeepSeek API
        // WireMock: POST /v1/chat/completions → 返回 function call
        // WireMock: POST /v1/chat/completions → 返回最终回复

        SseEmitter emitter = orchestrator.chat("我的订单状态", "test-sess-1");

        List<String> events = new ArrayList<>();
        // ... collect SSE events from emitter

        assertThat(events).anyMatch(e -> e.contains("tool_call"));
        assertThat(events).anyMatch(e -> e.contains("done"));
    }
}
```

### 6.4 测试独立性说明

```
LLM Client     ← WireMock 模拟 DeepSeek API    → 不需要真实 LLM
StateGraph     ← Mock LLMClient + Mock Tool    → 纯逻辑测试
ToolRegistry   ← 注册 Mock Bean + 反射调用      → 不依赖外部服务
SessionManager ← Mock StringRedisTemplate      → 不需要 Redis
SSE Controller ← Mock AgentOrchestrator        → 不依赖 Agent 逻辑
```

---

## 附录：面试话术

> **"为什么选 LangGraph4j 而不是直接调 LLM API？"**
> LangGraph4j 提供了显式的状态图抽象——每个节点是纯函数，边定义了流转条件。这种"显式状态机"比提示词工程（"你是一个 Agent，请想想下一步做什么"）更可控：
> - 迭代次数有硬上限（maxIterations），防止无限循环消耗 token
> - 每个节点的行为可单独测试（think/act/answer）
> - 状态完整存储在 AgentState，便于调试和回放

> **"为什么不用 Spring AI 或 LangChain4j 内置的 Agent？"**
> Spring AI 的 Agent 抽象还比较早期，功能不如 LangGraph4j 的 `StateGraph` 灵活（例如自定义条件路由、多节点并行）。我们的方案是 LangChain4j 负责 LLM 客户端（`ChatLanguageModel` + `ToolSpecification`），LangGraph4j 负责编排——各取所长。

> **"ReAct 循环的安全性怎么保证？"**
> 三层防护：① maxIterations 硬上限（5 轮）→ 防止无限循环；② sanitize 节点前置清洗（PII 脱敏 + prompt injection 检测）；③ verify 节点后置验证（检查回复是否客观准确，防止幻觉）。所有防护都是独立的节点插入到 StateGraph 中。
