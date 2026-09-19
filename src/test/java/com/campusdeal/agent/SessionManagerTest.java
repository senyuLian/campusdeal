package com.campusdeal.agent;

import cn.hutool.json.JSONUtil;
import com.campusdeal.security.CompactionService;
import com.campusdeal.security.CompactedSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SP-01..05：会话管理测试（Mock StringRedisTemplate + CompactionService）。
 */
@ExtendWith(MockitoExtension.class)
class SessionManagerTest {

    @Mock
    StringRedisTemplate stringRedisTemplate;
    @Mock
    HashOperations<String, Object, Object> hashOps;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    CompactionService compactionService;

    @InjectMocks
    SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        lenient().when(hashOps.putIfAbsent(anyString(), any(), any())).thenReturn(true);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenReturn(true);
        lenient().when(hashOps.get("agent:session:s1", "userId")).thenReturn("1");
        lenient().when(hashOps.get("agent:session:s1", "version")).thenReturn("0");
        // 仅 SP-04/SP-05 触发压缩，其余用例不调用，故用 lenient
        lenient().when(compactionService.compact(anyList()))
                .thenReturn(CompactedSummary.builder().goal("目标").progress("进度").build());
        lenient().when(compactionService.renderSummary(any())).thenReturn("目标: 目标 | 进度: 进度");
    }

    @Test
    @DisplayName("SP-01 新会话：Redis 无记录时返回空会话")
    void sp01_getOrCreateNewSession() {
        when(hashOps.entries(anyString())).thenReturn(Map.of());

        AgentSession session = sessionManager.getOrCreate("s1", 1L);

        assertEquals("s1", session.getSessionId());
        assertEquals(1L, session.getUserId());
        assertTrue(session.getMessages().isEmpty());
    }

    @Test
    @DisplayName("SP-02 空 sessionId：自动生成")
    void sp02_blankSessionIdGenerated() {
        when(hashOps.entries(anyString())).thenReturn(Map.of());

        AgentSession session = sessionManager.getOrCreate(null, 1L);

        assertNotNull(session.getSessionId());
        assertFalse(session.getSessionId().isBlank());
    }

    @Test
    @DisplayName("SP-03 已有会话：解析 Redis 中的消息 JSON")
    void sp03_existingSessionParsed() {
        List<MessageRecord> messages = List.of(
                MessageRecord.builder().role("user").content("你好").timestamp(1L).build(),
                MessageRecord.builder().role("assistant").content("您好").timestamp(2L).build());
        Map<Object, Object> data = new HashMap<>();
        data.put("userId", "1");
        data.put("messages", JSONUtil.toJsonStr(messages));
        when(hashOps.entries("agent:session:s1")).thenReturn(data);

        AgentSession session = sessionManager.getOrCreate("s1", 1L);

        assertEquals(2, session.getMessages().size());
        assertEquals("你好", session.getMessages().get(0).getContent());
        assertEquals("assistant", session.getMessages().get(1).getRole());
    }

    @Test
    @DisplayName("SP-04 保存：追加 user+assistant，未超上限不裁剪")
    void sp04_saveAppendsMessages() {
        List<MessageRecord> history = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            history.add(MessageRecord.builder().role("user").content("m" + i).timestamp((long) i).build());
        }
        AgentState state = mockState(history);

        sessionManager.save("s1", state);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(eq("agent:session:s1"), captor.capture());
        List<MessageRecord> saved = JSONUtil.toList(captor.getValue().get("messages"), MessageRecord.class);
        assertEquals(17, saved.size()); // 15 + user + assistant
    }

    @Test
    @DisplayName("SP-05 保存：超过 MAX_HISTORY 裁剪最旧记录并刷新 TTL")
    void sp05_saveTrimsAndExpires() {
        List<MessageRecord> history = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            history.add(MessageRecord.builder().role("user").content("m" + i).timestamp((long) i).build());
        }
        AgentState state = mockState(history);

        sessionManager.save("s1", state);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(eq("agent:session:s1"), captor.capture());
        List<MessageRecord> saved = JSONUtil.toList(captor.getValue().get("messages"), MessageRecord.class);
        assertEquals(SessionManager.MAX_HISTORY, saved.size());
        verify(stringRedisTemplate).expire(eq("agent:session:s1"), any(Duration.class));
    }

    @Test
    @DisplayName("SP-06 owner/version 校验：旧版本不能覆盖，删除必须匹配所有者")
    void sp06_ownerAndVersionAreEnforced() {
        AgentState state = mockState(List.of());
        when(state.getVersion()).thenReturn(1L);

        assertThatThrownBy(() -> sessionManager.save("s1", state))
                .isInstanceOf(com.campusdeal.exception.ConflictException.class);

        when(hashOps.get("agent:session:s1", "userId")).thenReturn("2");
        assertThatThrownBy(() -> sessionManager.delete("s1", 1L))
                .isInstanceOf(com.campusdeal.exception.ForbiddenException.class);
    }

    @Test
    @DisplayName("SP-07 ownerless legacy session is never implicitly claimed and receives expiry")
    void sp07_ownerlessSessionExpiresInsteadOfBeingClaimed() {
        when(hashOps.entries("agent:session:legacy")).thenReturn(Map.of("messages", "[]"));

        assertThatThrownBy(() -> sessionManager.getOrCreate("legacy", 1L))
                .isInstanceOf(com.campusdeal.exception.ForbiddenException.class);

        verify(stringRedisTemplate).expire(eq("agent:session:legacy"), any(Duration.class));
    }

    @Test
    @DisplayName("SP-08 concurrent save holding the session lock returns a retryable conflict")
    void sp08_lockContentionIsConflict() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenReturn(false);
        AgentState state = mockState(List.of());

        assertThatThrownBy(() -> sessionManager.save("s1", state))
                .isInstanceOf(com.campusdeal.exception.ConflictException.class);
    }

    private AgentState mockState(List<MessageRecord> history) {
        AgentState state = org.mockito.Mockito.mock(AgentState.class);
        lenient().when(state.getMessages()).thenReturn(history);
        lenient().when(state.getUserInput()).thenReturn("提问");
        lenient().when(state.getFinalAnswer()).thenReturn("回答");
        lenient().when(state.getUserId()).thenReturn(1L);
        return state;
    }
}
