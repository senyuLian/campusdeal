package com.campusdeal.agent;

import com.campusdeal.security.SensitiveLogSanitizer;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表：注册 / 查询 / 执行工具，并为模型生成 ToolSpecification 列表。
 *
 * <p>单个工具执行异常会被捕获并转为 JSON 错误结果，不影响编排主流程。</p>
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, ToolEntry> tools = new ConcurrentHashMap<>();

    /** 注册一个工具 */
    public void register(ToolMeta meta, ToolExecutor executor) {
        tools.put(meta.getName(), new ToolEntry(meta, executor));
        log.info("Tool registered: {}", meta.getName());
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public Optional<ToolMeta> getMeta(String name) {
        ToolEntry entry = tools.get(name);
        return entry == null ? Optional.empty() : Optional.of(entry.meta);
    }

    public List<ToolMeta> listTools() {
        return tools.values().stream().map(e -> e.meta).toList();
    }

    /**
     * 执行工具。
     *
     * @param name         工具名
     * @param argumentsJson 入参（JSON 字符串）
     * @return 工具结果 JSON；未知工具或执行异常时返回带 error 字段的结果
     */
    public String execute(String name, String argumentsJson) {
        ToolEntry entry = tools.get(name);
        if (entry == null) {
            return "{\"error\":\"unknown tool\"}";
        }
        try {
            String result = entry.executor.execute(argumentsJson);
            return result == null ? "{}" : result;
        } catch (Exception e) {
            log.error("Tool [{}] execution failed: {}", name,
                    SensitiveLogSanitizer.exceptionSummary(e));
            return "{\"error\":\"tool execution failed\"}";
        }
    }

    /** 给模型的工具规格列表 */
    public List<ToolSpecification> buildToolSpecifications() {
        return tools.values().stream().map(e -> ToolSpecification.builder()
                .name(e.meta.getName())
                .description(e.meta.getDescription())
                .parameters(e.meta.getParametersSchema())
                .build()).toList();
    }


    private record ToolEntry(ToolMeta meta, ToolExecutor executor) {
    }
}
