package com.campusdeal.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DeepSeek LLM 客户端（OpenAI 兼容协议）。
 *
 * <p>模型实例懒加载：启动时不构造模型、不建立外部连接；
 * API Key 为空时应用也能正常启动，首次真正对话才构造模型（此时若 Key 缺失会得到可读的异常）。</p>
 */
@Slf4j
@Component
public class DeepSeekChatClient {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    private static final String MODEL_NAME = "deepseek-chat";

    private final String apiKey;
    private final String baseUrl;

    // 测试注入点：直接传入 mock 模型（测试构造器）
    private final OpenAiChatModel model;
    private final OpenAiStreamingChatModel streamingModel;

    private volatile OpenAiChatModel lazyModel;
    private volatile OpenAiStreamingChatModel lazyStreamingModel;

    @Autowired
    public DeepSeekChatClient(
            @Value("${campusdeal.deepseek.api-key:}") String apiKey,
            @Value("${campusdeal.deepseek.base-url:https://api.deepseek.com/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        this.model = null;
        this.streamingModel = null;
    }

    /** 测试构造器：跳过 @Value，直接注入 mock 模型 */
    DeepSeekChatClient(OpenAiChatModel model, OpenAiStreamingChatModel streamingModel) {
        this.apiKey = "test-key";
        this.baseUrl = DEFAULT_BASE_URL;
        this.model = model;
        this.streamingModel = streamingModel;
    }

    /**
     * 同步对话：可携带工具规格（无工具时传空列表）。
     *
     * @return 模型的完整响应（含 TokenUsage）
     */
    public Response<AiMessage> chatSync(List<ChatMessage> messages, List<ToolSpecification> tools) {
        OpenAiChatModel m = resolveModel();
        if (tools == null || tools.isEmpty()) {
            return m.generate(messages);
        }
        return m.generate(messages, tools);
    }

    /**
     * 流式对话：token 通过回调实时返回（内部会由模型层驱动 onNext/onComplete）。
     */
    public void chatStream(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        OpenAiStreamingChatModel m = resolveStreamingModel();
        m.generate(messages, handler);
    }

    public String modelName() {
        return MODEL_NAME;
    }

    private OpenAiChatModel resolveModel() {
        if (model != null) {
            return model;
        }
        OpenAiChatModel m = lazyModel;
        if (m == null) {
            synchronized (this) {
                if (lazyModel == null) {
                    checkApiKey();
                    lazyModel = OpenAiChatModel.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(MODEL_NAME)
                            .temperature(0.1)
                            .maxTokens(2048)
                            .build();
                }
                m = lazyModel;
            }
        }
        return m;
    }

    private OpenAiStreamingChatModel resolveStreamingModel() {
        if (streamingModel != null) {
            return streamingModel;
        }
        OpenAiStreamingChatModel m = lazyStreamingModel;
        if (m == null) {
            synchronized (this) {
                if (lazyStreamingModel == null) {
                    checkApiKey();
                    lazyStreamingModel = OpenAiStreamingChatModel.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(MODEL_NAME)
                            .temperature(0.1)
                            .maxTokens(2048)
                            .build();
                }
                m = lazyStreamingModel;
            }
        }
        return m;
    }

    private void checkApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("campusdeal.deepseek.api-key 未配置，Agent 无法调用 LLM");
        }
    }
}
