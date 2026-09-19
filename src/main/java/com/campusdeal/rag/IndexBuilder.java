package com.campusdeal.rag;

import com.campusdeal.security.SensitiveLogSanitizer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识索引构建编排：启动时全量加载文档 → 分块 → 生成 Embedding → 写入 PGVector + 构建 BM25。
 *
 * <p>容错设计：任一环节失败（未配置 API Key / PGVector 未就绪 / Embedding 调用失败）都不影响启动，
 * BM25 纯内存索引始终可用；向量部分失败仅降级为「纯关键词检索」。</p>
 */
@Slf4j
@Component
public class IndexBuilder implements ApplicationRunner {

    private static final int BATCH_SIZE = 50;

    @Resource
    private DocumentLoader documentLoader;

    @Resource
    private TextEmbedder embedder;

    @Resource
    private InMemoryBM25Index bm25Index;

    @Resource
    private VectorStore vectorStore;

    @Value("${campusdeal.deepseek.api-key:}")
    private String apiKey;

    @Value("${campusdeal.pgvector.enabled:false}")
    private boolean vectorEnabled = false;

    @Override
    public void run(ApplicationArguments args) {
        try {
            buildAll();
        } catch (Exception e) {
            log.warn("知识索引构建失败（不影响应用启动）: {}", SensitiveLogSanitizer.exceptionSummary(e));
        }
    }

    public void buildAll() {
        log.info("=== 开始构建知识索引 ===");

        List<Document> allDocs = new ArrayList<>();
        allDocs.addAll(documentLoader.loadFaqs());
        allDocs.addAll(documentLoader.loadPolicies());
        allDocs.addAll(documentLoader.loadMerchants());
        log.info("加载文档 {} 篇", allDocs.size());
        if (allDocs.isEmpty()) {
            log.warn("没有可索引的文档，跳过构建");
            return;
        }

        List<DocumentChunk> chunks = allDocs.stream()
                .flatMap(doc -> documentLoader.chunkDocument(doc).stream())
                .collect(Collectors.toList());
        log.info("分块为 {} 段", chunks.size());

        // Step 3: 生成 Embedding（无 API Key 时跳过向量部分，BM25 仍可用）
        boolean embedded = false;
        if (!vectorEnabled || apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 DeepSeek API Key，跳过向量索引（BM25 关键词检索仍可用）");
        } else {
            try {
                for (List<DocumentChunk> batch : partition(chunks, BATCH_SIZE)) {
                    List<String> texts = batch.stream().map(DocumentChunk::getText).collect(Collectors.toList());
                    List<float[]> embeddings = embedder.embedBatch(texts);
                    for (int j = 0; j < batch.size(); j++) {
                        batch.get(j).setEmbedding(embeddings.get(j));
                    }
                }
                embedded = true;
            } catch (Exception e) {
            log.warn("Embedding 生成失败，跳过向量索引（BM25 仍可用）: {}", SensitiveLogSanitizer.exceptionSummary(e));
            }
        }

        // Step 4: 写入 PGVector
        if (embedded) {
            try {
                Map<String, Document> docById = allDocs.stream()
                        .collect(Collectors.toMap(Document::getId, Function.identity()));
                List<VectorEntry> entries = chunks.stream()
                        .filter(c -> c.getEmbedding() != null)
                        .map(c -> {
                            Document doc = docById.get(c.getDocId());
                            return VectorEntry.builder()
                                    .docId(c.getChunkId())
                                    .embedding(c.getEmbedding())
                                    .metadata(Map.of(
                                            "docId", c.getDocId(),
                                            "title", doc == null || doc.getTitle() == null ? "" : doc.getTitle(),
                                            "text", c.getText(),
                                            "source", doc == null ? "unknown" : doc.getSource(),
                                            "category", doc == null ? "general" : doc.getCategory()))
                                    .build();
                        })
                        .collect(Collectors.toList());
                vectorStore.batchInsert(entries);
                log.info("写入 {} 条向量到 PGVector", entries.size());
            } catch (Exception e) {
            log.warn("PGVector 写入失败（不影响启动）: {}", SensitiveLogSanitizer.exceptionSummary(e));
            }
        }

        // Step 5: BM25 indexes the same canonical passages as PGVector. This
        // keeps sparse and dense channels on one stable document/chunk ID.
        Map<String, Document> docsById = allDocs.stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        for (DocumentChunk chunk : chunks) {
            Document source = docsById.get(chunk.getDocId());
            if (source == null) continue;
            bm25Index.index(Document.builder()
                    .id(chunk.getChunkId())
                    .title(source.getTitle())
                    .content(chunk.getText())
                    .source(source.getSource())
                    .category(source.getCategory())
                    .metadata(Map.of("documentId", source.getId(), "chunkIndex", chunk.getChunkIndex()))
                    .build());
        }
        log.info("BM25 索引构建完成，共 {} 个段落", chunks.size());
        log.info("=== 知识索引构建完成 ===");
    }

    /** 每日增量重建（由 @EnableScheduling 驱动；失败会被容错逻辑兜住） */
    @Scheduled(cron = "0 30 3 * * ?")
    public void scheduledReindex() {
        log.info("触发每日知识索引重建");
        buildAll();
    }

    static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
