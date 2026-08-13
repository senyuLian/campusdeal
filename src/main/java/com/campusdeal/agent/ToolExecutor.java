package com.campusdeal.agent;

/**
 * 工具执行函数：入参为 JSON 字符串，返回 JSON 字符串结果。
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * 执行工具。
     *
     * @param argumentsJson 工具入参（JSON 对象字符串）
     * @return 工具结果（JSON 字符串）
     */
    String execute(String argumentsJson) throws Exception;
}
