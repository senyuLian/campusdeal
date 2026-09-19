package com.campusdeal.security;

import com.campusdeal.agent.DeepSeekChatClient;
import com.campusdeal.agent.MessageRecord;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 上下文压缩实现（Prime Agent 风格结构化摘要）。
 *
 * <p>优先调用 LLM 生成结构化摘要；LLM 不可用（未配置 API Key / 异常）时降级为
 * 规则摘要，保证功能在无外部依赖环境下可用。</p>
 */
@Slf4j
@Component
public class CompactionServiceImpl implements CompactionService {

    /** 保留最近 N 条消息不压缩，只压缩更早的 */
    private static final int KEEP_RECENT = 5;

    private static final String COMPACTION_PROMPT = """
        You are a conversation summarizer. Given the chat history below,
        produce a structured summary in Chinese, in this EXACT format:

        Goal: [一句话描述用户的核心目标]
        Progress: [已完成的关键步骤，用分号分隔]
        Key Info: [对话中获得的重要信息，用分号分隔]
        Next Steps: [下一步可能需要做什么]

        Keep each section concise. Use only information present in the conversation.
        Do NOT invent or assume anything not stated.

        Chat history:
        %s
        """;

    @Resource
    private DeepSeekChatClient llmClient;

    @Override
    public CompactedSummary compact(List<MessageRecord> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompactedSummary.builder().goal("无").progress("无").build();
        }
        List<MessageRecord> toCompress = messages.size() > KEEP_RECENT
                ? new ArrayList<>(messages.subList(0, messages.size() - KEEP_RECENT))
                : List.of();
        if (toCompress.isEmpty()) {
            return CompactedSummary.builder().goal("无").progress("无").build();
        }

        CompactedSummary fallback = ruleBasedSummary(toCompress);
        try {
            CompactedSummary llmSummary = llmCompact(toCompress);
            if (llmSummary != null && hasAnyContent(llmSummary)) {
                return llmSummary;
            }
        } catch (Exception e) {
            log.warn("LLM 摘要压缩失败，降级为规则摘要: {}", SensitiveLogSanitizer.exceptionSummary(e));
        }
        return fallback;
    }

    private CompactedSummary llmCompact(List<MessageRecord> toCompress) {
        String historyText = toCompress.stream()
                .map(m -> String.format("[%s]: %s", m.getRole(), m.getContent()))
                .collect(Collectors.joining("\n"));
        String prompt = String.format(COMPACTION_PROMPT, historyText);
        // 显示强转为 langchain4j ChatMessage（避免与 security.ChatMessage 同名冲突而无法 import）
        Response<AiMessage> response = llmClient.chatSync(
                List.of((dev.langchain4j.data.message.ChatMessage) UserMessage.from(prompt)),
                Collections.emptyList());
        String text = response.content() == null ? "" : response.content().text();
        return parseCompactSummary(text);
    }

    /** 规则摘要降级：无 LLM 依赖，抽取最近用户提问作为核心信息 */
    private CompactedSummary ruleBasedSummary(List<MessageRecord> toCompress) {
        StringBuilder goal = new StringBuilder();
        for (MessageRecord m : toCompress) {
            if ("user".equals(m.getRole()) && m.getContent() != null) {
                goal.append(m.getContent()).append("；");
            }
        }
        String goalText = goal.length() > 60 ? goal.substring(0, 60) + "…" : goal.toString();
        return CompactedSummary.builder()
                .goal(goalText.isEmpty() ? "未明确" : goalText)
                .progress("已完成 " + toCompress.size() + " 轮对话")
                .keyInfo("最近用户提问：" + lastUserContent(toCompress))
                .nextSteps("根据用户最新需求继续解答")
                .build();
    }

    private String lastUserContent(List<MessageRecord> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageRecord m = messages.get(i);
            if ("user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return "无";
    }

    private boolean hasAnyContent(CompactedSummary s) {
        return (s.getGoal() != null && !s.getGoal().isBlank())
                || (s.getProgress() != null && !s.getProgress().isBlank())
                || (s.getKeyInfo() != null && !s.getKeyInfo().isBlank());
    }

    private CompactedSummary parseCompactSummary(String text) {
        return CompactedSummary.builder()
                .goal(extractField(text, "Goal"))
                .progress(extractField(text, "Progress"))
                .keyInfo(extractField(text, "Key Info"))
                .nextSteps(extractField(text, "Next Steps"))
                .build();
    }

    private String extractField(String text, String field) {
        if (text == null) {
            return "";
        }
        Pattern pattern = Pattern.compile(field + ":\\s*(.+?)(?=\\n[A-Z]|$)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    @Override
    public List<ChatMessage> injectSummary(List<ChatMessage> messages, CompactedSummary summary) {
        List<ChatMessage> result = new ArrayList<>();
        result.add(ChatMessage.builder()
                .role("system")
                .content(renderSummary(summary))
                .timestamp(System.currentTimeMillis())
                .build());
        result.addAll(messages);
        return result;
    }

    @Override
    public String renderSummary(CompactedSummary summary) {
        return String.format("目标: %s | 进度: %s | 关键信息: %s | 下一步: %s",
                summary.getGoal(), summary.getProgress(),
                summary.getKeyInfo(), summary.getNextSteps());
    }
}
