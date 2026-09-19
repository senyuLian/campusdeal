package com.campusdeal.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.campusdeal.config.ReliabilityMetrics;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * HR-01..05：混合检索（BM25 + 向量 + RRF 融合）单元测试。
 */
@ExtendWith(MockitoExtension.class)
class HybridRetrieverTest {

    @Mock
    InMemoryBM25Index bm25Index;
    @Mock
    VectorStore vectorStore;
    @Mock
    TextEmbedder embedder;

    @InjectMocks
    HybridRetrieverImpl retriever;

    @org.junit.jupiter.api.BeforeEach
    void enableVectorForUnitFixture() {
        ReflectionTestUtils.setField(retriever, "vectorEnabled", true);
    }

    @Test
    @DisplayName("HR-01 BM25+向量都有结果：RRF 融合后返回 topK，分数正确")
    void hr01_fuseAndRankCorrectly() {
        when(bm25Index.search(eq("退款"), anyInt())).thenReturn(List.of(
                new BM25Result("doc-1", "退款指南", "...", 0.85, "faq"),
                new BM25Result("doc-2", "充值帮助", "...", 0.70, "faq"),
                new BM25Result("doc-3", "配送政策", "...", 0.50, "faq")));
        when(embedder.embed(eq("退款"))).thenReturn(new float[1024]);
        when(vectorStore.search(any(), anyInt())).thenReturn(List.of(
                VectorResult.builder().docId("doc-2").cosineSimilarity(0.95).build(),
                VectorResult.builder().docId("doc-1").cosineSimilarity(0.88).build(),
                VectorResult.builder().docId("doc-4").cosineSimilarity(0.72).build()));

        List<RetrievalResult> results = retriever.retrieve("退款", 3);

        // doc-1: 1/61 + 1/62 = 0.0325 ; doc-2: 1/62 + 1/61 = 0.0325 (同分)
        assertThat(results).hasSize(3);
        assertThat(results.get(0).getScore()).isGreaterThan(0.03);
        assertThat(results.get(0).getDocId()).isIn("doc-1", "doc-2");
        assertThat(results.get(1).getScore()).isGreaterThan(0.03);
    }

    @Test
    @DisplayName("HR-02 仅 BM25 有结果：返回 BM25 的 topK")
    void hr02_bm25Only() {
        when(bm25Index.search(anyString(), anyInt())).thenReturn(List.of(
                new BM25Result("doc-1", "退款", "...", 0.8, "faq")));
        when(embedder.embed(anyString())).thenReturn(new float[1024]);
        when(vectorStore.search(any(), anyInt())).thenReturn(List.of());

        List<RetrievalResult> results = retriever.retrieve("退款", 3);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDocId()).isEqualTo("doc-1");
    }

    @Test
    @DisplayName("HR-03 仅向量有结果：返回向量的 topK")
    void hr03_vectorOnly() {
        when(bm25Index.search(anyString(), anyInt())).thenReturn(List.of());
        when(embedder.embed(anyString())).thenReturn(new float[1024]);
        when(vectorStore.search(any(), anyInt())).thenReturn(List.of(
                VectorResult.builder().docId("v-1").cosineSimilarity(0.90).build(),
                VectorResult.builder().docId("v-2").cosineSimilarity(0.80).build()));

        List<RetrievalResult> results = retriever.retrieve("退款", 3);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getDocId()).isEqualTo("v-1");
    }

    @Test
    @DisplayName("HR-04 两者都空：返回空列表")
    void hr04_bothEmpty() {
        when(bm25Index.search(anyString(), anyInt())).thenReturn(List.of());
        when(embedder.embed(anyString())).thenReturn(new float[1024]);
        when(vectorStore.search(any(), anyInt())).thenReturn(List.of());

        assertThat(retriever.retrieve("退款", 5)).isEmpty();
    }

    @Test
    @DisplayName("HR-05 RRF 去重：重叠文档的分数 = 两个排名贡献之和")
    void hr05_deduplicateAndCombine() {
        when(bm25Index.search(eq("test"), anyInt())).thenReturn(List.of(
                new BM25Result("A", "Title A", "...", 0.9, "faq"),
                new BM25Result("B", "Title B", "...", 0.8, "faq")));
        when(embedder.embed(eq("test"))).thenReturn(new float[1024]);
        when(vectorStore.search(any(), anyInt())).thenReturn(List.of(
                VectorResult.builder().docId("A").cosineSimilarity(0.95).build(),
                VectorResult.builder().docId("C").cosineSimilarity(0.70).build()));

        List<RetrievalResult> results = retriever.retrieve("test", 5);

        // A, B, C —— 不能有重复的 A
        assertThat(results).hasSize(3);
        Optional<RetrievalResult> docA = results.stream()
                .filter(r -> "A".equals(r.getDocId())).findFirst();
        assertThat(docA).isPresent();
        // doc-A 的分数来自 BM25 rank1 + Vector rank1
        assertThat(docA.get().getScore())
                .isCloseTo(1.0 / 61 + 1.0 / 61, within(0.001));
        assertThat(results).filteredOn(r -> "A".equals(r.getDocId())).hasSize(1);
    }

    @Test
    @DisplayName("HR-06 向量检索抛异常时降级为纯 BM25")
    void hr06_vectorFailureDegradesToBm25() {
        when(bm25Index.search(anyString(), anyInt())).thenReturn(List.of(
                new BM25Result("doc-1", "退款", "...", 0.8, "faq")));
        when(embedder.embed(anyString())).thenThrow(new IllegalStateException("API Key 未配置"));

        List<RetrievalResult> results = retriever.retrieve("退款", 3);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDocId()).isEqualTo("doc-1");
    }

    @Test
    @DisplayName("HR-08 向量降级只记录有界 outcome 标签，不记录原始查询")
    void hr08_vectorFailureRecordsBoundedMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(retriever, "reliabilityMetrics", new ReliabilityMetrics(registry));
        when(bm25Index.search(anyString(), anyInt())).thenReturn(List.of());
        when(embedder.embed(anyString())).thenThrow(new IllegalStateException("backend down"));

        retriever.retrieve("手机号 13812345678", 3);

        assertThat(registry.get("campusdeal.rag.search").tag("outcome", "vector_degraded").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("campusdeal.rag.search").counter().getId().getTags())
                .noneMatch(tag -> "手机号 13812345678".equals(tag.getValue()));
    }

    @Test
    @DisplayName("HR-07 同一文档最多返回两个分块且同分时按 canonical id 稳定排序")
    void hr07_limitsChunksPerDocumentAndBreaksTiesDeterministically() {
        when(bm25Index.search(anyString(), anyInt())).thenReturn(List.of(
                new BM25Result("doc#2", "", "c2", 0.9, "faq"),
                new BM25Result("doc#1", "", "c1", 0.9, "faq"),
                new BM25Result("doc#3", "", "c3", 0.9, "faq"),
                new BM25Result("other#0", "", "other", 0.8, "faq")));
        when(embedder.embed(anyString())).thenReturn(new float[1]);
        when(vectorStore.search(any(), anyInt())).thenReturn(List.of(
                VectorResult.builder().docId("doc#1").cosineSimilarity(0.9).build(),
                VectorResult.builder().docId("doc#2").cosineSimilarity(0.9).build()));

        List<RetrievalResult> results = retriever.retrieve("test", 4);

        assertThat(results).extracting(RetrievalResult::getDocId)
                .containsExactly("doc#1", "doc#2", "other#0");
    }
}
