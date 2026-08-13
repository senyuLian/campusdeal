package com.campusdeal.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 本轮对话中已发生消息的可序列化 DTO（对应 langchain4j ChatMessage 的轻量表示）。
 *
 * <p>用于在 ReAct 循环中按序记录 assistant 的 tool_calls 消息与其对应的 tool 结果消息，
 * 使重建给 LLM 的消息满足 OpenAI 兼容协议：tool 消息必须紧跟声明其 tool_calls 的 assistant 消息。
 * 实现 {@link Serializable}：langgraph4j 在执行过程中会对状态做 Java 序列化。</p>
 *
 * <pre>
 *   role = "assistant"  → toolCalls 非空（content 为 assistant 文本，工具场景下通常为空）
 *   role = "tool"       → toolCallId / toolName / content 为工具结果 JSON
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String role;
    private String content;
    private List<ToolCall> toolCalls;
    private String toolCallId;
    private String toolName;
}
