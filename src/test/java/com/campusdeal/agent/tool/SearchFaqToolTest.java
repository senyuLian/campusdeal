package com.campusdeal.agent.tool;

import com.campusdeal.rag.HybridRetriever;
import com.campusdeal.rag.RetrievalResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SF-01..02：SearchFaqTool —— Agent 暴露的 FAQ 混合检索工具。
 */
@ExtendWith(MockitoExtension.class)
class SearchFaqToolTest {

    @Mock
    HybridRetriever hybridRetriever;

    @InjectMocks
    SearchFaqTool tool;

    @Test
    @DisplayName("SF-01 调用混合检索并返回 JSON 结果")
    void sf01_retrievesAndSerializes() {
        when(hybridRetriever.retrieve("退款", 5)).thenReturn(List.of(
                RetrievalResult.builder().docId("faq-1").title("退款指南").content("申请退款流程").score(0.9).build()));

        String json = tool.searchFaq("退款");

        assertThat(json).contains("faq-1").contains("退款指南");
        verify(hybridRetriever).retrieve("退款", 5);
    }

    @Test
    @DisplayName("SF-02 空关键字：返回错误 JSON，不触发检索")
    void sf02_blankKeyword() {
        String json = tool.searchFaq("   ");

        assertThat(json).contains("error");
        verifyNoInteractions(hybridRetriever);
    }
}
