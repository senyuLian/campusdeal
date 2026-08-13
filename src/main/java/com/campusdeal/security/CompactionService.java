package com.campusdeal.security;

import com.campusdeal.agent.MessageRecord;

import java.util.List;

/**
 * 长对话上下文压缩：Prime Agent 风格的结构化摘要（Goal/Progress/Key Info/Next Steps）。
 */
public interface CompactionService {

    /**
     * 压缩对话历史为结构化摘要。
     *
     * @param messages 需要压缩的消息列表（旧的）
     * @return Prime Agent 风格结构化摘要
     */
    CompactedSummary compact(List<MessageRecord> messages);

    /**
     * 将摘要作为 system 消息注入到消息列表头部（替代被压缩的消息）。
     */
    List<ChatMessage> injectSummary(List<ChatMessage> messages, CompactedSummary summary);

    /**
     * 将结构化摘要渲染为单行文本（用于写入会话记录 / 注入系统提示）。
     */
    String renderSummary(CompactedSummary summary);
}
