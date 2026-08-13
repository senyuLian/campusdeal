package com.campusdeal.agent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.campusdeal.security.CompactionService;
import com.campusdeal.security.CompactedSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话管理：Redis Hash 持久化对话历史与摘要。
 *
 * <p>key 形如 {@code agent:session:{sessionId}}，字段：userId / messages(JSON 数组) / summary(可选)。</p>
 */
@Slf4j
@Component
public class SessionManager {

    public static final String SESSION_KEY_PREFIX = "agent:session:";
    public static final int MAX_HISTORY = 20;
    public static final int COMPACT_THRESHOLD = 15;
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CompactionService compactionService;

    /**
     * 获取会话；不存在时创建一个空会话（sessionId 为空则生成）。
     */
    public AgentSession getOrCreate(String sessionId, Long userId) {
        if (StrUtil.isBlank(sessionId)) {
            sessionId = UUID.randomUUID().toString().replace("-", "");
        }
        String key = SESSION_KEY_PREFIX + sessionId;
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);
        if (map == null || map.isEmpty()) {
            return AgentSession.builder().sessionId(sessionId).userId(userId).messages(new ArrayList<>()).build();
        }
        List<MessageRecord> messages = null;
        Object raw = map.get("messages");
        if (raw != null) {
            try {
                messages = JSONUtil.toList(raw.toString(), MessageRecord.class);
            } catch (Exception e) {
                log.warn("Parse session messages failed: {}", sessionId, e);
            }
        }
        return AgentSession.builder()
                .sessionId(sessionId)
                .userId(userId)
                .messages(messages == null ? new ArrayList<>() : messages)
                .summary(map.get("summary") == null ? null : map.get("summary").toString())
                .build();
    }

    /**
     * 保存会话：把当前轮 user + assistant 追加到历史，超出 {@link #MAX_HISTORY} 则裁剪最旧的。
     */
    public void save(String sessionId, AgentState state) {
        List<MessageRecord> history = new ArrayList<>(state.getMessages());
        history.add(MessageRecord.builder().role("user").content(state.getUserInput())
                .timestamp(System.currentTimeMillis()).build());
        String answer = state.getFinalAnswer();
        if (answer != null) {
            history.add(MessageRecord.builder().role("assistant").content(answer)
                    .timestamp(System.currentTimeMillis()).build());
        }
        if (history.size() > MAX_HISTORY) {
            history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY, history.size()));
        }
        Map<String, String> data = new HashMap<>();
        data.put("userId", String.valueOf(state.getUserId()));
        data.put("messages", JSONUtil.toJsonStr(history));
        // Module 06：历史超长时用 CompactionService 生成结构化摘要（Prime Agent 风格）
        String summary = compactIfNeeded(history);
        if (summary != null) {
            data.put("summary", summary);
        }
        String key = SESSION_KEY_PREFIX + sessionId;
        stringRedisTemplate.opsForHash().putAll(key, data);
        stringRedisTemplate.expire(key, SESSION_TTL);
    }

    public void delete(String sessionId) {
        stringRedisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
    }

    /**
     * 历史超长时生成结构化摘要；LLM 不可用时由 CompactionService 内部降级为规则摘要。
     * 摘要为单行文本（Goal/Progress/Key Info/Next Steps 拼接），可读且可注入提示词。
     */
    private String compactIfNeeded(List<MessageRecord> history) {
        if (history.size() <= COMPACT_THRESHOLD) {
            return null;
        }
        try {
            CompactedSummary summary = compactionService.compact(history);
            return compactionService.renderSummary(summary);
        } catch (Exception e) {
            log.warn("Compaction failed, skip summary: {}", e.getMessage());
            return null;
        }
    }
}
