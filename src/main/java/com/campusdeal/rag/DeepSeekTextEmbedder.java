package com.campusdeal.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DeepSeek Embedding 向量化器（OpenAI 兼容协议，走 langchain4j OpenAiEmbeddingModel）。
 *
 * <p>模型实例懒加载：启动时不构造模型、不建立外部连接；API Key 为空时应用可正常启动，
 * 首次真正向量化时才构造模型（此时若 Key 缺失会得到可读的异常，由 {@link IndexBuilder} 捕获降级）。</p>
 */
@Slf4j
@Component
public class DeepSeekTextEmbedder implements TextEmbedder {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_MODEL = "deepseek-embedding";
    private static final int DEFAULT_DIMENSION = 1024;

    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final int dimension;

    /** 测试注入点：直接传入 mock 模型（测试构造器） */
    private final OpenAiEmbeddingModel model;

    private volatile OpenAiEmbeddingModel lazyModel;

    @Autowired
    public DeepSeekTextEmbedder(
            @Value("${campusdeal.deepseek.api-key:}") String apiKey,
            @Value("${campusdeal.deepseek.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${campusdeal.deepseek.embedding-model:deepseek-embedding}") String modelName,
            @Value("${campusdeal.deepseek.embedding-dimension:1024}") int dimension) {
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        this.modelName = (modelName == null || modelName.isBlank()) ? DEFAULT_MODEL : modelName;
        this.dimension = dimension > 0 ? dimension : DEFAULT_DIMENSION;
        this.model = null;
    }

    /** 测试构造器：跳过 @Value，直接注入 mock 模型 */
    DeepSeekTextEmbedder(OpenAiEmbeddingModel model) {
        this.apiKey = "test-key";
        this.baseUrl = DEFAULT_BASE_URL;
        this.modelName = DEFAULT_MODEL;
        this.dimension = DEFAULT_DIMENSION;
        this.model = model;
    }

    @Override
    public float[] embed(String text) {
        OpenAiEmbeddingModel m = resolveModel();
        Response<Embedding> resp = m.embed(text);
        return resp.content().vector();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        OpenAiEmbeddingModel m = resolveModel();
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        Response<List<Embedding>> resp = m.embedAll(segments);
        return resp.content().stream().map(Embedding::vector).toList();
    }

    private OpenAiEmbeddingModel resolveModel() {
        if (model != null) {
            return model;
        }
        OpenAiEmbeddingModel m = lazyModel;
        if (m == null) {
            synchronized (this) {
                if (lazyModel == null) {
                    checkApiKey();
                    lazyModel = OpenAiEmbeddingModel.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(modelName)
                            .dimensions(dimension)
                            .maxRetries(1)
                            .build();
                }
                m = lazyModel;
            }
        }
        return m;
    }

    private void checkApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("campusdeal.deepseek.api-key 未配置，无法调用 Embedding API");
        }
    }
}
