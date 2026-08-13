package com.campusdeal.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 一次工具调用的执行结果。
 *
 * <p>toolCallId / toolName 用于在下一轮 think 中重建 ToolExecutionResultMessage，
 * 使模型能看到本轮工具输出。实现 {@link Serializable}：langgraph4j 会对状态做 Java 序列化。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String toolCallId;
    private String toolName;
    private boolean success;
    private String result;
    private long executionTimeMs;
}
