package com.campusdeal.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** RRF 融合参数（TREC 最佳实践） */
    private static final double RRF_K = 60;

    @Override
    public List<RetrievalResult> retrieve(String query, int topK) {
        // 各自多取一些，融合后截断
        List<RetrievalResult> bm25Results = bm25Search(query, topK * 2);
        List<RetrievalResult> vectorResults = vectorSearch(query, topK * 2);
        List<RetrievalResult> fused = rrfFusion(bm25Results, vectorResults, topK);

        log.debug("Hybrid retrieval: query='{}', bm25={}, vector={}, fused={}",
                query, bm25Results.size(), vectorResults.size(), fused.size());
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
        try {
            float[] queryEmbedding = embedder.embed(query);
            List<VectorResult> vectorResults = vectorStore.search(queryEmbedding, topK);
            return vectorResults.stream()
                    .map(vr -> RetrievalResult.builder()
                            .docId(vr.getDocId())
                            .content(vr.getContent())
                            .score(vr.getCosineSimilarity())
                            .subScores(Map.of("vector", vr.getCosineSimilarity()))
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // Embedding API / PGVector 不可用时降级为纯 BM25，不影响关键词检索
            log.warn("向量检索不可用，降级为纯 BM25: {}", e.getMessage());
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

        Map<String, Double> fusionScores = new HashMap<>();
        Map<String, RetrievalResult> merged = new LinkedHashMap<>();

        for (int i = 0; i < bm25Results.size(); i++) {
            RetrievalResult r = bm25Results.get(i);
            double rrf = 1.0 / (RRF_K + i + 1);
            fusionScores.merge(r.getDocId(), rrf, Double::sum);
            merged.putIfAbsent(r.getDocId(), r);
        }
        for (int i = 0; i < vectorResults.size(); i++) {
            RetrievalResult r = vectorResults.get(i);
            double rrf = 1.0 / (RRF_K + i + 1);
            fusionScores.merge(r.getDocId(), rrf, Double::sum);
            merged.putIfAbsent(r.getDocId(), r);
        }

        return fusionScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    RetrievalResult r = merged.get(entry.getKey());
                    r.setScore(entry.getValue());
                    return r;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void index(Document document) {
        float[] embedding = embedder.embed(document.getContent());
        vectorStore.insert(document.getId(), embedding, buildMetadata(document));
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
                log.warn("索引文档失败 {}: {}", d.getId(), e.getMessage());
            }
        }
    }

    @Override
    public void delete(String docId) {
        vectorStore.delete(docId);
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
