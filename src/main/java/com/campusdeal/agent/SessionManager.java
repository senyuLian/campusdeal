package com.campusdeal.agent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.campusdeal.security.CompactionService;
import com.campusdeal.security.CompactedSummary;
import com.campusdeal.security.SensitiveLogSanitizer;
import com.campusdeal.exception.ForbiddenException;
import com.campusdeal.exception.UnauthorizedException;
import com.campusdeal.exception.ConflictException;
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
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.ValueOperations;

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
    private static final Duration LEGACY_OWNERLESS_TTL = Duration.ofMinutes(10);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CompactionService compactionService;

    /**
     * 获取会话；不存在时创建一个空会话（sessionId 为空则生成）。
     */
    public AgentSession getOrCreate(String sessionId, Long userId) {
        if (userId == null) {
            throw new UnauthorizedException();
        }
        if (StrUtil.isBlank(sessionId)) {
            sessionId = UUID.randomUUID().toString().replace("-", "");
        }
        String key = SESSION_KEY_PREFIX + sessionId;
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);
        if (map == null || map.isEmpty()) {
            // Hash field initialization must be atomic. Two callers may both
            // observe a cold key; putIfAbsent makes the first caller the only
            // owner and forces the loser to re-read the immutable owner.
            Boolean ownerCreated = stringRedisTemplate.opsForHash()
                    .putIfAbsent(key, "userId", String.valueOf(userId));
            if (!Boolean.FALSE.equals(ownerCreated)) {
                stringRedisTemplate.opsForHash().putIfAbsent(key, "version", "0");
                stringRedisTemplate.expire(key, SESSION_TTL);
                return AgentSession.builder().sessionId(sessionId).userId(userId).version(0L)
                        .messages(new ArrayList<>()).build();
            }
            map = stringRedisTemplate.opsForHash().entries(key);
            if (map == null || map.isEmpty()) {
                throw new ConflictException("会话正在初始化，请稍后重试");
            }
        }
        Object owner = map.get("userId");
        if (owner == null || !String.valueOf(userId).equals(owner.toString())) {
            if (owner == null) {
                // Legacy sessions without an immutable owner cannot be
                // claimed by the first caller who presents the identifier.
                // Give the record a short expiry so an operator can migrate
                // it explicitly, then reject the request.
                stringRedisTemplate.expire(key, LEGACY_OWNERLESS_TTL);
            }
            throw new ForbiddenException();
        }
        return parseSession(sessionId, userId, map);
    }

    private AgentSession parseSession(String sessionId, Long userId, Map<Object, Object> map) {
        List<MessageRecord> messages = null;
        Object raw = map.get("messages");
        if (raw != null) {
            try {
                messages = JSONUtil.toList(raw.toString(), MessageRecord.class);
            } catch (Exception e) {
                log.warn("Parse session messages failed: {}", sessionId);
            }
        }
        return AgentSession.builder()
                .sessionId(sessionId)
                .userId(userId)
                .version(parseVersion(map.get("version")))
                .messages(messages == null ? new ArrayList<>() : messages)
                .summary(map.get("summary") == null ? null : map.get("summary").toString())
                .build();
    }

    /**
     * 保存会话：把当前轮 user + assistant 追加到历史，超出 {@link #MAX_HISTORY} 则裁剪最旧的。
     */
    public void save(String sessionId, AgentState state) {
        if (state == null || state.getUserId() == null) {
            throw new UnauthorizedException();
        }
        String key = SESSION_KEY_PREFIX + sessionId;
        Object owner = stringRedisTemplate.opsForHash().get(key, "userId");
        if (owner == null) {
            // Legacy ownerless sessions cannot be claimed implicitly. They
            // must be migrated by an audited operator before writes resume.
            throw new ForbiddenException();
        }
        if (!String.valueOf(state.getUserId()).equals(owner.toString())) {
            throw new ForbiddenException();
        }
        long storedVersion = parseVersion(stringRedisTemplate.opsForHash().get(key, "version"));
        if (state.getVersion() != storedVersion) {
            throw new ConflictException("会话版本已更新，请重新提交");
        }
        String lockKey = key + ":lock";
        String token = UUID.randomUUID().toString();
        ValueOperations<String, String> values = stringRedisTemplate.opsForValue();
        Boolean acquired = values == null ? null : values.setIfAbsent(lockKey, token, 15, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new ConflictException("会话正在处理中，请稍后重试");
        }
        try {
            // Re-read the owner and version after acquiring the lock. A
            // caller may have passed the optimistic check while another turn
            // was committing; without this second check that stale turn could
            // overwrite the newer history after the lock becomes available.
            Object lockedOwner = stringRedisTemplate.opsForHash().get(key, "userId");
            long lockedVersion = parseVersion(stringRedisTemplate.opsForHash().get(key, "version"));
            if (lockedOwner == null || !String.valueOf(state.getUserId()).equals(lockedOwner.toString())) {
                throw new ForbiddenException();
            }
            if (state.getVersion() != lockedVersion) {
                throw new ConflictException("会话版本已更新，请重新提交");
            }
            List<MessageRecord> history = readMessages(key);
            if (history.isEmpty()) {
                history = new ArrayList<>(state.getMessages());
            }
            boolean alreadySaved = !history.isEmpty()
                    && state.getUserInput() != null
                    && history.stream().anyMatch(m -> "user".equals(m.getRole())
                    && state.getUserInput().equals(m.getContent()));
            if (!alreadySaved) {
                history.add(MessageRecord.builder().role("user").content(state.getUserInput())
                        .timestamp(System.currentTimeMillis()).build());
                String answer = state.getFinalAnswer();
                if (answer != null) {
                    history.add(MessageRecord.builder().role("assistant").content(answer)
                            .timestamp(System.currentTimeMillis()).build());
                }
            }
            if (history.size() > MAX_HISTORY) {
                history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY, history.size()));
            }
            Map<String, String> data = new HashMap<>();
            data.put("userId", String.valueOf(state.getUserId()));
            data.put("version", String.valueOf(lockedVersion + 1));
            data.put("messages", JSONUtil.toJsonStr(history));
            String summary = compactIfNeeded(history);
            if (summary != null) data.put("summary", summary);
            stringRedisTemplate.opsForHash().putAll(key, data);
            stringRedisTemplate.expire(key, SESSION_TTL);
        } finally {
            try {
                stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
            } catch (Exception e) {
                log.warn("Session lock release failed: {}", SensitiveLogSanitizer.exceptionSummary(e));
            }
        }
    }

    public void delete(String sessionId, Long userId) {
        if (userId == null) {
            throw new UnauthorizedException();
        }
        String key = SESSION_KEY_PREFIX + sessionId;
        Object owner = stringRedisTemplate.opsForHash().get(key, "userId");
        if (owner == null || !String.valueOf(userId).equals(owner.toString())) {
            throw new ForbiddenException();
        }
        stringRedisTemplate.delete(key);
    }

    /**
     * Legacy callers must provide the owner explicitly; silently deleting by
     * session ID would reintroduce cross-user data loss.
     */
    @Deprecated
    public void delete(String sessionId) {
        throw new UnauthorizedException();
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
            log.warn("Compaction failed, skip summary: {}", SensitiveLogSanitizer.exceptionSummary(e));
            return null;
        }
    }

    private List<MessageRecord> readMessages(String key) {
        Object raw = stringRedisTemplate.opsForHash().get(key, "messages");
        if (raw == null) return new ArrayList<>();
        try {
            return JSONUtil.toList(raw.toString(), MessageRecord.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private long parseVersion(Object raw) {
        if (raw == null) return 0L;
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
