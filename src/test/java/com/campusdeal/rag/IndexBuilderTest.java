package com.campusdeal.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IB-01..03：知识索引构建编排单元测试（Mock 所有写入组件，不依赖数据库 / Embedding API）。
 */
@ExtendWith(MockitoExtension.class)
class IndexBuilderTest {

    @Mock
    DocumentLoader documentLoader;
    @Mock
    TextEmbedder embedder;
    @Mock
    InMemoryBM25Index bm25Index;
    @Mock
    VectorStore vectorStore;

    @InjectMocks
    IndexBuilder builder;

    private Document doc(String id, String title) {
        return Document.builder().id(id).title(title).content(title + " 校园卡退款说明").source("faq").category("faq").build();
    }

    private void stubDocumentLoading(List<Document> docs) {
        when(documentLoader.loadFaqs()).thenReturn(docs);
        when(documentLoader.loadPolicies()).thenReturn(List.of());
        when(documentLoader.loadMerchants()).thenReturn(List.of());
        when(documentLoader.chunkDocument(any())).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            return List.of(DocumentChunk.builder()
                    .chunkId(d.getId() + "-c0")
                    .docId(d.getId())
                    .text(d.getContent())
                    .build());
        });
    }

    @Test
    @DisplayName("IB-01 全量构建：有 API Key 时写入向量并构建 BM25")
    void ib01_fullBuild() {
        List<Document> docs = List.of(doc("faq-1", "退款"), doc("faq-2", "优惠券"));
        stubDocumentLoading(docs);
        ReflectionTestUtils.setField(builder, "apiKey", "test-key");
        ReflectionTestUtils.setField(builder, "vectorEnabled", true);
        when(embedder.embedBatch(anyList())).thenReturn(List.of(new float[]{1f}, new float[]{2f}));

        builder.buildAll();

        verify(vectorStore).batchInsert(anyList());
        verify(bm25Index, times(2)).index(any(Document.class));
    }

    @Test
    @DisplayName("IB-02 无 API Key：跳过向量索引，BM25 仍构建")
    void ib02_noApiKeySkipsVector() {
        List<Document> docs = List.of(doc("faq-1", "退款"), doc("faq-2", "优惠券"));
        stubDocumentLoading(docs);
        // apiKey 为 null（@InjectMocks 未注入 @Value）→ 跳过 embedding

        builder.buildAll();

        verify(vectorStore, never()).batchInsert(anyList());
        verify(bm25Index, times(2)).index(any(Document.class));
    }

    @Test
    @DisplayName("IB-03 Embedding 失败：容错降级，BM25 仍构建")
    void ib03_embeddingFailureDegrades() {
        List<Document> docs = List.of(doc("faq-1", "退款"), doc("faq-2", "优惠券"));
        stubDocumentLoading(docs);
        ReflectionTestUtils.setField(builder, "apiKey", "test-key");
        ReflectionTestUtils.setField(builder, "vectorEnabled", true);
        when(embedder.embedBatch(anyList())).thenThrow(new RuntimeException("API down"));

        builder.buildAll();

        verify(vectorStore, never()).batchInsert(anyList());
        verify(bm25Index, times(2)).index(any(Document.class));
    }
}
