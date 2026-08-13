package com.campusdeal.rag;

import java.util.List;
import java.util.Map;

/**
 * 向量存储（PGVector）：支持近似最近邻检索、插入、批量插入与删除。
 */
public interface VectorStore {

    /** 向量近似最近邻检索（按余弦相似度降序） */
    List<VectorResult> search(float[] embedding, int topK);

    /** 插入向量（含元数据），docId 冲突时按 ON CONFLICT 更新 */
    void insert(String docId, float[] embedding, Map<String, Object> metadata);

    /** 批量插入 */
    void batchInsert(List<VectorEntry> entries);

    /** 按 docId 删除 */
    void delete(String docId);
}
