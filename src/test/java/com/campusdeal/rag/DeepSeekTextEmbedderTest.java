package com.campusdeal.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * TE-01..03：DeepSeek Embedding 向量化器单元测试（Mock OpenAiEmbeddingModel，无需真实 API）。
 */
@ExtendWith(MockitoExtension.class)
class DeepSeekTextEmbedderTest {

    @Mock
    OpenAiEmbeddingModel model;

    @Test
    @DisplayName("TE-01 单文本向量化：返回模型输出的 1024 维向量")
    void te01_embedSingle() {
        float[] vec = {0.1f, 0.2f, 0.3f};
        when(model.embed("你好")).thenReturn(Response.from(Embedding.from(vec)));

        DeepSeekTextEmbedder embedder = new DeepSeekTextEmbedder(model);
        assertThat(embedder.embed("你好")).isEqualTo(vec);
    }

    @Test
    @DisplayName("TE-02 批量向量化：embedBatch 按序返回")
    void te02_embedBatch() {
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(
                Embedding.from(new float[]{1f}),
                Embedding.from(new float[]{2f}))));

        DeepSeekTextEmbedder embedder = new DeepSeekTextEmbedder(model);
        List<float[]> result = embedder.embedBatch(List.of("文本A", "文本B"));

        assertThat(result).hasSize(2);
        assertThat(result.get(1)).isEqualTo(new float[]{2f});
    }

    @Test
    @DisplayName("TE-03 未配置 API Key：构造成功，首次向量化抛出可读异常")
    void te03_blankApiKeyThrowsOnUse() {
        DeepSeekTextEmbedder embedder = new DeepSeekTextEmbedder("", "https://api.deepseek.com/v1", "deepseek-embedding", 1024);

        assertThatThrownBy(() -> embedder.embed("hi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }
}
