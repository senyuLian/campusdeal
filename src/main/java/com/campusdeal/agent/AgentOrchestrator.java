package com.campusdeal.agent;

import com.campusdeal.dto.Result;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 编排器：对外暴露 SSE 对话入口与会话历史查询。
 */
public interface AgentOrchestrator {

    /**
     * 发起一次对话。
     *
     * @param userMessage 用户输入
     * @param sessionId   会话 ID（可为空，为空则新建会话）
     * @return SSE 发射器，事件流实时推送 thinking / tool_call / chunk / done
     */
    SseEmitter chat(String userMessage, String sessionId);

    /**
     * 查询会话历史。
     */
    Result getHistory(String sessionId);
}
