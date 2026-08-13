package com.campusdeal.agent;

import dev.langchain4j.agent.tool.ToolParameters;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具的元信息（注册表中暴露给模型 / 前端）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolMeta {

    private String name;
    private String description;
    private ToolParameters parametersSchema;
    private boolean requireConfirmation;
    private String[] requiredPermissions;
}
