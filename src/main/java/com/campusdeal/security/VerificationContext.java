package com.campusdeal.security;

import com.campusdeal.agent.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 输出校验上下文（用户问题 + 工具结果）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationContext {

    private String userQuestion;

    private List<ToolResult> toolResults;

    private int totalIterations;
}
