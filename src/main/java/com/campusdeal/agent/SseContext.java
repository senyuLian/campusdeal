package com.campusdeal.agent;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 当前请求的 SSE 发射器（ThreadLocal）。
 *
 * <p>编排器在图节点中通过 {@link #getEmitter()} 实时推送 thinking / tool_call / chunk 事件；
 * 图在异步线程执行，因此用 ThreadLocal 将发射器与当前对话绑定。</p>
 */
public final class SseContext {

    private static final ThreadLocal<SseEmitter> EMITTER = new ThreadLocal<>();

    private SseContext() {
    }

    public static void setEmitter(SseEmitter emitter) {
        EMITTER.set(emitter);
    }

    public static SseEmitter getEmitter() {
        return EMITTER.get();
    }

    public static void clear() {
        EMITTER.remove();
    }
}
