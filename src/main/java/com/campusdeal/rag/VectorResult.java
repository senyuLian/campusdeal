package com.campusdeal.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 向量近似最近邻检索的单条结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorResult {

    private String docId;
    private String title;
    private String content;
    private String source;
    private String category;
    private double cosineSimilarity;
}
