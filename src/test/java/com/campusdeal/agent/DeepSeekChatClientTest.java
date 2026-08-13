package com.campusdeal.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LL-01..05：DeepSeek LLM 客户端测试（Mock 模型）。
 */
@ExtendWith(MockitoExtension.class)
class DeepSeekChatClientTest {

    @Mock
    OpenAiChatModel model;
    @Mock
    OpenAiStreamingChatModel streamingModel;

    DeepSeekChatClient client;

    @BeforeEach
    void setUp() {
        client = new DeepSeekChatClient(model, streamingModel);
    }

    @Test
    @DisplayName("LL-01 同步对话（无工具）：委托单参 generate")
    void ll01_chatSyncNoTools() {
        when(model.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("你好"), new TokenUsage(1, 2)));

        Response<AiMessage> response = client.chatSync(List.of(UserMessage.from("hello")), List.of());

        assertEquals("你好", response.content().text());
        assertEquals(3, response.tokenUsage().totalTokenCount());
        verify(model).generate(anyList());
    }

    @Test
    @DisplayName("LL-02 同步对话（带工具）：委托双参 generate")
    void ll02_chatSyncWithTools() {
        ToolSpecification spec = ToolSpecification.builder().name("query_order").build();
        when(model.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("hi"), new TokenUsage(1, 2)));

        Response<AiMessage> response = client.chatSync(List.of(UserMessage.from("hello")), List.of(spec));

        assertEquals("hi", response.content().text());
        verify(model).generate(anyList(), anyList());
    }

    @Test
    @DisplayName("LL-03 流式对话：委托 streaming model")
    void ll03_chatStream() {
        client.chatStream(List.of(UserMessage.from("hello")), new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
            }

            @Override
            public void onError(Throwable error) {
            }
        });

        verify(streamingModel).generate(anyList(), any(StreamingResponseHandler.class));
    }

    @Test
    @DisplayName("LL-04 未配置 Key：调用时抛可读异常（懒加载）")
    void ll04_emptyApiKeyThrowsOnChat() {
        DeepSeekChatClient noKey = new DeepSeekChatClient("", "https://api.deepseek.com/v1");

        assertThrows(IllegalStateException.class, () -> noKey.chatSync(List.of(), List.of()));
    }

    @Test
    @DisplayName("LL-05 未配置 Key：可正常构造，不影响启动")
    void ll05_emptyApiKeyConstructionOk() {
        DeepSeekChatClient noKey = new DeepSeekChatClient("", null);

        assertNotNull(noKey);
        assertEquals("deepseek-chat", noKey.modelName());
    }
}
