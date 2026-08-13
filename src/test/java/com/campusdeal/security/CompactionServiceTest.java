package com.campusdeal.security;

import com.campusdeal.agent.DeepSeekChatClient;
import com.campusdeal.agent.MessageRecord;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * CS-01..04：上下文压缩测试（Mock LLM，LLM 不可用时降级为规则摘要）。
 */
@ExtendWith(MockitoExtension.class)
class CompactionServiceTest {

    @Mock
    private DeepSeekChatClient llmClient;

    @InjectMocks
    private CompactionServiceImpl compactionService;

    private List<MessageRecord> messages(int n) {
        List<MessageRecord> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(MessageRecord.builder()
                    .role("user")
                    .content("m" + i)
                    .timestamp((long) i)
                    .build());
        }
        return list;
    }

    @Test
    @DisplayName("CS-01 空历史：返回占位摘要")
    void cs01_emptyHistory() {
        CompactedSummary s1 = compactionService.compact(null);
        assertEquals("无", s1.getGoal());
        assertEquals("无", s1.getProgress());

        CompactedSummary s2 = compactionService.compact(List.of());
        assertEquals("无", s2.getGoal());
    }

    @Test
    @DisplayName("CS-02 历史不足 5 条：不触发压缩")
    void cs02_belowKeepRecent() {
        CompactedSummary s = compactionService.compact(messages(3));
        assertEquals("无", s.getGoal());
        assertEquals("无", s.getProgress());
    }

    @Test
    @DisplayName("CS-03 LLM 生成结构化摘要")
    void cs03_llmStructuredSummary() {
        when(llmClient.chatSync(anyList(), anyList())).thenReturn(Response.from(AiMessage.from(
                "Goal: 查订单\nProgress: 已查询订单\nKey Info: 订单号123\nNext Steps: 确认退款")));

        CompactedSummary s = compactionService.compact(messages(7));

        assertEquals("查订单", s.getGoal());
        assertEquals("已查询订单", s.getProgress());
        assertEquals("订单号123", s.getKeyInfo());
        assertEquals("确认退款", s.getNextSteps());
    }

    @Test
    @DisplayName("CS-04 LLM 不可用时降级为规则摘要")
    void cs04_fallbackWhenLlmFails() {
        when(llmClient.chatSync(anyList(), anyList()))
                .thenThrow(new RuntimeException("api down"));

        CompactedSummary s = compactionService.compact(messages(7));

        assertNotNull(s.getGoal());
        assertTrue(s.getGoal().contains("m0"));
    }

    @Test
    @DisplayName("CS-05 摘要注入：在消息头部插入 system 消息")
    void cs05_injectSummary() {
        CompactedSummary summary = CompactedSummary.builder()
                .goal("查订单").progress("已完成").keyInfo("订单123").nextSteps("确认").build();
        List<ChatMessage> originals = List.of(
                ChatMessage.builder().role("user").content("最新提问").timestamp(1L).build());

        List<ChatMessage> injected = compactionService.injectSummary(originals, summary);

        assertEquals(2, injected.size());
        assertEquals("system", injected.get(0).getRole());
        assertTrue(injected.get(0).getContent().contains("查订单"));
        assertEquals("最新提问", injected.get(1).getContent());
    }

    @Test
    @DisplayName("CS-06 渲染摘要为单行文本")
    void cs06_renderSummary() {
        CompactedSummary summary = CompactedSummary.builder()
                .goal("目标A").progress("进度B").keyInfo("信息C").nextSteps("步骤D").build();

        String rendered = compactionService.renderSummary(summary);

        assertTrue(rendered.contains("目标A"));
        assertTrue(rendered.contains("进度B"));
        assertTrue(rendered.contains("信息C"));
        assertTrue(rendered.contains("步骤D"));
    }
}
