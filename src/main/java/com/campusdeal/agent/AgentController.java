package com.campusdeal.agent;

import com.campusdeal.dto.Result;
import com.campusdeal.security.ConfirmRequest;
import com.campusdeal.security.GuardResult;
import com.campusdeal.security.SensitiveGuard;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;

/**
 * Agent 接口（需登录：LoginInterceptor 保护，token 经 RefreshTokenInterceptor 写入 UserHolder）。
 *
 * <p>{@code POST /agent/chat} 返回 SSE 流（text/event-stream），事件名依次为
 * thinking / tool_call / tool_result / confirm / chunk / done / error。</p>
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    @Resource
    private AgentOrchestrator orchestrator;
    @Resource
    private SensitiveGuard sensitiveGuard;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam("message") String message,
                           @RequestParam(value = "sessionId", required = false) String sessionId) {
        return orchestrator.chat(message, sessionId);
    }

    @GetMapping("/history/{sessionId}")
    public Result history(@PathVariable("sessionId") String sessionId) {
        return orchestrator.getHistory(sessionId);
    }

    /**
     * Module 06：敏感操作二次确认。用户在前端确认弹窗中点击后调用；
     * 批准时由 SensitiveGuard 代为执行原工具并返回结果。
     */
    @PostMapping("/confirm")
    public Result confirm(@RequestBody ConfirmRequest request) {
        // T8：越权校验 —— 传入当前登录用户，SensitiveGuard 校验其与确认创建者一致
        Long userId = com.campusdeal.utils.UserHolder.getUser() == null
                ? null : com.campusdeal.utils.UserHolder.getUser().getId();
        GuardResult result = sensitiveGuard.handleConfirmation(
                request.getConfirmationId(), request.isApproved(), userId);
        return result.isExecuted() ? Result.ok(result) : Result.fail(result.getMessage());
    }
}
