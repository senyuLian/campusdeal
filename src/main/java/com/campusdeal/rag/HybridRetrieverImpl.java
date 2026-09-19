package com.campusdeal.rag;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import com.campusdeal.config.ReliabilityMetrics;
import com.campusdeal.security.SensitiveLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 混合检索实现：BM25（关键词/稀疏） + 向量（语义/稠密） → RRF（Reciprocal Rank Fusion）融合排序。
 *
 * <p>为什么用 RRF 而不是加权求和？BM25 分数与余弦相似度的数值范围不同，直接加权需要复杂的归一化；
 * RRF 只看排名（score(d) = Σ 1/(k + rank_i(d))），k=60 来自学术界验证的最优参数，天然无需归一化。</p>
 */
@Slf4j
@Component
public class HybridRetrieverImpl implements HybridRetriever {

    @Resource
    private InMemoryBM25Index bm25Index;

    @Resource
    private VectorStore vectorStore;

    @Resource
    private TextEmbedder embedder;

    @Autowired(required = false)
    private ReliabilityMetrics reliabilityMetrics;

    @Value("${campusdeal.pgvector.enabled:false}")
    private boolean vectorEnabled = false;

    /** RRF 融合参数（TREC 最佳实践） */
    private static final double RRF_K = 60;
    /** Avoid letting one source document consume the entire result window. */
    private static final int MAX_CHUNKS_PER_DOCUMENT = 2;

    @Override
    public List<RetrievalResult> retrieve(String query, int topK) {
        int boundedTopK = Math.max(1, Math.min(topK, 100));
        // 各自多取一些，融合后截断
        List<RetrievalResult> bm25Results = bm25Search(query, boundedTopK * 2);
        List<RetrievalResult> vectorResults = vectorSearch(query, boundedTopK * 2);
        List<RetrievalResult> fused = rrfFusion(bm25Results, vectorResults, boundedTopK);

        log.debug("Hybrid retrieval: queryHash={}, queryLength={}, bm25={}, vector={}, fused={}",
                Integer.toHexString(query == null ? 0 : query.hashCode()),
                query == null ? 0 : query.length(), bm25Results.size(), vectorResults.size(), fused.size());
        return fused;
    }

    private List<RetrievalResult> bm25Search(String query, int topK) {
        return bm25Index.search(query, topK).stream()
                .map(r -> RetrievalResult.builder()
                        .docId(r.getDocId())
                        .title(r.getTitle())
                        .content(r.getContent())
                        .score(r.getScore())
                        .source(r.getSource())
                        .subScores(Map.of("bm25", r.getScore()))
                        .build())
                .collect(Collectors.toList());
    }

    private List<RetrievalResult> vectorSearch(String query, int topK) {
        if (!vectorEnabled) {
            if (reliabilityMetrics != null) reliabilityMetrics.increment("campusdeal.rag.search", "vector_disabled");
            return List.of();
        }
        try {
            float[] queryEmbedding = embedder.embed(query);
            List<VectorResult> vectorResults = vectorStore.search(queryEmbedding, topK);
            return vectorResults.stream()
                    .map(vr -> RetrievalResult.builder()
                    .docId(vr.getDocId())
                            .title(vr.getTitle())
                            .content(vr.getContent())
                            .score(vr.getCosineSimilarity())
                            .source(vr.getSource())
                            .subScores(Map.of("vector", vr.getCosineSimilarity()))
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // Embedding API / PGVector 不可用时降级为纯 BM25，不影响关键词检索
            log.warn("向量检索不可用，降级为纯 BM25: {}", SensitiveLogSanitizer.exceptionSummary(e));
            if (reliabilityMetrics != null) reliabilityMetrics.increment("campusdeal.rag.search", "vector_degraded");
            return List.of();
        }
    }

    /**
     * Reciprocal Rank Fusion：按排名而非原始分数融合，天然去重（同一 docId 的分数累加）。
     */
    private List<RetrievalResult> rrfFusion(
            List<RetrievalResult> bm25Results,
            List<RetrievalResult> vectorResults,
            int topK) {

        Map<String, RetrievalResult> merged = new LinkedHashMap<>();
        Map<String, Double> fusionScores = new HashMap<>();

        for (int i = 0; i < bm25Results.size(); i++) {
            RetrievalResult r = bm25Results.get(i);
            double rrf = 1.0 / (RRF_K + i + 1);
            fusionScores.merge(r.getDocId(), rrf, Double::sum);
            mergeResult(merged, r);
        }
        for (int i = 0; i < vectorResults.size(); i++) {
            RetrievalResult r = vectorResults.get(i);
            double rrf = 1.0 / (RRF_K + i + 1);
            fusionScores.merge(r.getDocId(), rrf, Double::sum);
            mergeResult(merged, r);
        }

        List<RetrievalResult> results = new ArrayList<>();
        Map<String, Integer> documentCounts = new HashMap<>();
        fusionScores.entrySet().stream()
                // Stable tie-breaking matters when both channels assign the
                // same rank score to different canonical passages.
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> {
                    if (results.size() >= topK) return;
                    RetrievalResult r = merged.get(entry.getKey());
                    String documentId = documentIdOf(entry.getKey());
                    int count = documentCounts.getOrDefault(documentId, 0);
                    if (count >= MAX_CHUNKS_PER_DOCUMENT) return;
                    r.setScore(entry.getValue());
                    results.add(r);
                    documentCounts.put(documentId, count + 1);
                });
        return results;
    }

    private String documentIdOf(String canonicalId) {
        if (canonicalId == null) return "";
        int separator = canonicalId.lastIndexOf('#');
        return separator > 0 ? canonicalId.substring(0, separator) : canonicalId;
    }

    private void mergeResult(Map<String, RetrievalResult> merged, RetrievalResult incoming) {
        RetrievalResult current = merged.get(incoming.getDocId());
        if (current == null) {
            Map<String, Double> scores = new LinkedHashMap<>();
            if (incoming.getSubScores() != null) scores.putAll(incoming.getSubScores());
            incoming.setSubScores(scores);
            merged.put(incoming.getDocId(), incoming);
            return;
        }
        if (current.getTitle() == null) current.setTitle(incoming.getTitle());
        if (current.getContent() == null) current.setContent(incoming.getContent());
        if (current.getSource() == null) current.setSource(incoming.getSource());
        if (incoming.getSubScores() != null) current.getSubScores().putAll(incoming.getSubScores());
    }

    @Override
    public void index(Document document) {
        if (vectorEnabled) {
            float[] embedding = embedder.embed(document.getContent());
            vectorStore.insert(document.getId(), embedding, buildMetadata(document));
        }
        bm25Index.index(document);
    }

    @Override
    public void batchIndex(List<Document> documents) {
        if (documents == null) {
            return;
        }
        for (Document d : documents) {
            try {
                index(d);
            } catch (Exception e) {
                log.warn("索引文档失败 {}: {}", d.getId(), SensitiveLogSanitizer.exceptionSummary(e));
            }
        }
    }

    @Override
    public void delete(String docId) {
        if (vectorEnabled) {
            vectorStore.delete(docId);
        }
        bm25Index.delete(docId);
    }

    private Map<String, Object> buildMetadata(Document doc) {
        Map<String, Object> meta = new HashMap<>();
        if (doc.getMetadata() != null) {
            meta.putAll(doc.getMetadata());
        }
        meta.put("title", doc.getTitle());
        meta.put("text", doc.getContent());
        meta.put("source", doc.getSource());
        meta.put("category", doc.getCategory());
        return meta;
    }
}
