package com.campusdeal.rag;

import java.util.List;

/**
 * 文本向量化器：调用 DeepSeek Embedding API 将文本转为 1024 维向量。
 */
public interface TextEmbedder {

    /** 将单段文本转为向量 */
    float[] embed(String text);

    /** 批量向量化（向量维度一致） */
    List<float[]> embedBatch(List<String> texts);
}
