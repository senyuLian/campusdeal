package com.campusdeal.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 状态：基于 langgraph4j 的 Map-backed {@code AgentState}，提供类型化访问器。
 *
 * <p>状态字段：sessionId / userId / userInput / messages / iteration / maxIterations /
 * totalTokens / currentThought / pendingToolCalls / toolResults / finalAnswer / shouldFinish。</p>
 *
 * <p>注意：基类 {@code org.bsc.langgraph4j.state.AgentState} 与本类同名，因此不使用 import，而是全限定名继承。</p>
 */
public class AgentState extends org.bsc.langgraph4j.state.AgentState {

    public AgentState(Map<String, Object> data) {
        super(data);
    }

    public String getSessionId() {
        return value("sessionId").map(Object::toString).orElse(null);
    }

    public Long getUserId() {
        return value("userId").map(o -> Long.valueOf(o.toString())).orElse(null);
    }

    public long getVersion() {
        return value("version").map(o -> ((Number) o).longValue()).orElse(0L);
    }

    public String getUserInput() {
        return value("userInput").map(Object::toString).orElse("");
    }

    @SuppressWarnings("unchecked")
    public List<MessageRecord> getMessages() {
        return value("messages").map(o -> (List<MessageRecord>) o).orElseGet(ArrayList::new);
    }

    public int getIteration() {
        return value("iteration").map(o -> ((Number) o).intValue()).orElse(0);
    }

    public int getMaxIterations() {
        return value("maxIterations").map(o -> ((Number) o).intValue()).orElse(5);
    }

    public int getTotalTokens() {
        return value("totalTokens").map(o -> ((Number) o).intValue()).orElse(0);
    }

    public String getCurrentThought() {
        return value("currentThought").map(Object::toString).orElse(null);
    }

    public String getFinalAnswer() {
        return value("finalAnswer").map(Object::toString).orElse(null);
    }

    public boolean isShouldFinish() {
        return value("shouldFinish").map(o -> Boolean.parseBoolean(o.toString())).orElse(false);
    }

    @SuppressWarnings("unchecked")
    public List<ToolCall> getPendingToolCalls() {
        return value("pendingToolCalls").map(o -> (List<ToolCall>) o).orElseGet(ArrayList::new);
    }

    @SuppressWarnings("unchecked")
    public List<ToolResult> getToolResults() {
        return value("toolResults").map(o -> (List<ToolResult>) o).orElseGet(ArrayList::new);
    }

    /** 会话历史摘要（Module 06：CompactionService 压缩后写入状态，供提示词注入） */
    public String getSummary() {
        return value("summary").map(Object::toString).orElse(null);
    }

    /**
     * 本轮对话中已发生的完整消息序列（内存态，不持久化）：
     * 包含带 {@code tool_calls} 的 assistant 消息与对应的 tool 结果消息，按序排列。
     * 组装发给 LLM 的消息时，tool 消息必须紧跟在声明其 tool_calls 的 assistant 消息之后（OpenAI 兼容协议）。
     */
    @SuppressWarnings("unchecked")
    public List<TurnMessage> getTurnMessages() {
        return value("turnMessages").map(o -> (List<TurnMessage>) o).orElseGet(ArrayList::new);
    }
}
