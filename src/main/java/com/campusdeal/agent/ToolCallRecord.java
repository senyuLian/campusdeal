package com.campusdeal.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 完整的一次工具调用记录（含入参与出参），用于可观测性与痕迹追溯（Module 06 幻觉溯源）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRecord {

    private String toolName;
    private String arguments;
    private String result;
    private long executionTimeMs;
    private boolean success;
}
