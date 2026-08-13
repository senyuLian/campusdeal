package com.campusdeal.rag;

import java.util.List;

/**
 * 混合检索器：BM25（稀疏） + 向量（稠密） → RRF 融合排序。
 */
public interface HybridRetriever {

    /**
     * 混合检索。
     *
     * @param query 用户自然语言查询
     * @param topK  返回文档数量
     * @return 排序后的检索结果（含 RRF 融合分数与各子检索器分数）
     */
    List<RetrievalResult> retrieve(String query, int topK);

    /** 增量索引一篇新文档（同时写入向量库与 BM25 索引） */
    void index(Document document);

    /** 批量索引文档 */
    void batchIndex(List<Document> documents);

    /** 删除文档（同时从向量库与 BM25 索引移除） */
    void delete(String docId);
}
