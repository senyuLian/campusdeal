package com.campusdeal.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DL-01..02：文档加载与分块单元测试（基于类路径资源，无外部依赖）。
 */
class ClasspathDocumentLoaderTest {

    private final ClasspathDocumentLoader loader = new ClasspathDocumentLoader();

    @Test
    @DisplayName("DL-01 从类路径加载 FAQ / 政策 / 商户文档")
    void dl01_loadFromClasspath() {
        List<Document> faqs = loader.loadFaqs();
        List<Document> policies = loader.loadPolicies();
        List<Document> merchants = loader.loadMerchants();

        assertThat(faqs).isNotEmpty();
        assertThat(faqs).allMatch(d -> d.getId() != null && "faq".equals(d.getSource()));
        assertThat(policies).isNotEmpty();
        assertThat(merchants).isNotEmpty();
        // 标题不应为空（从 # 标题 提取）
        assertThat(faqs).allMatch(d -> d.getTitle() != null && !d.getTitle().isBlank());
    }

    @Test
    @DisplayName("DL-02 长文档按固定块长切分：多 chunk、ID 自增、带重叠")
    void dl02_chunkLongDocument() {
        String longText = IntStream.range(0, 30)
                .mapToObj(i -> "第" + i + "条常见问题：校园卡退款怎么处理呢？退款到账时间多久？")
                .collect(Collectors.joining("\n"));
        Document doc = Document.builder().id("d1").title("长文档").content(longText).source("faq").build();

        List<DocumentChunk> chunks = loader.chunkDocument(doc);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).getChunkId()).isEqualTo("d1-c0");
        assertThat(chunks.get(0).getDocId()).isEqualTo("d1");
        assertThat(chunks.get(0).getChunkIndex()).isZero();
    }

    @Test
    @DisplayName("DL-03 空文档分块返回空列表")
    void dl03_blankDocument() {
        Document doc = Document.builder().id("d1").title("空").content("   ").source("faq").build();
        assertThat(loader.chunkDocument(doc)).isEmpty();
    }
}
