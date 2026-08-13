package com.campusdeal.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 模型发起的一次工具调用（对应 langchain4j ToolExecutionRequest 的轻量 DTO）。
 *
 * <p>实现 {@link Serializable}：langgraph4j 在执行过程中会对状态做 Java 序列化。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String arguments;
}
