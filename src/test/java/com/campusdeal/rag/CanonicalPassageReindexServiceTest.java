package com.campusdeal.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CanonicalPassageReindexServiceTest {

    @Mock DocumentLoader documentLoader;
    @Mock TextEmbedder embedder;
    @Mock InMemoryBM25Index bm25Index;
    @Mock VectorStore vectorStore;
    @InjectMocks CanonicalPassageReindexService service;

    @Test
    void dryRunReportsCanonicalPassageCountWithoutWritingIndexes() {
        Document doc = Document.builder().id("faq-1").content("退款").source("faq").build();
        when(documentLoader.loadFaqs()).thenReturn(List.of(doc));
        when(documentLoader.loadPolicies()).thenReturn(List.of());
        when(documentLoader.loadMerchants()).thenReturn(List.of());
        when(documentLoader.chunkDocument(doc)).thenReturn(List.of(
                DocumentChunk.builder().chunkId("faq-1#0").docId("faq-1").chunkIndex(0).text("退款").build()));

        var report = service.reindex(true, false);

        assertThat(report.documentCount()).isEqualTo(1);
        assertThat(report.passageCount()).isEqualTo(1);
        assertThat(report.dryRun()).isTrue();
        verify(bm25Index, never()).index(any());
        verify(vectorStore, never()).batchInsert(anyList());
    }

    @Test
    void resumeWritesCanonicalPassagesAndCheckpoint() {
        Document doc = Document.builder().id("faq-1").title("退款").content("退款").source("faq").category("policy").build();
        when(documentLoader.loadFaqs()).thenReturn(List.of(doc));
        when(documentLoader.loadPolicies()).thenReturn(List.of());
        when(documentLoader.loadMerchants()).thenReturn(List.of());
        when(documentLoader.chunkDocument(doc)).thenReturn(List.of(
                DocumentChunk.builder().chunkId("faq-1#0").docId("faq-1").chunkIndex(0).text("退款").build()));
        ReflectionTestUtils.setField(service, "checkpointPath", "target/test-rag-reindex.checkpoint");
        ReflectionTestUtils.setField(service, "vectorEnabled", false);

        var report = service.reindex(false, false);

        assertThat(report.nextOffset()).isEqualTo(1);
        verify(bm25Index).index(any(Document.class));
    }
}
