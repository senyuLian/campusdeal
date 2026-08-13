package com.campusdeal.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 混合检索（BM25 + 向量 + RRF 融合）的最终结果。
 *
 * <p>{@code score} 是 RRF 融合分数（0-1 量级），{@code subScores} 保留各检索器的原始分，
 * 用于调试与解释（"为什么排第一"）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResult {

    private String docId;
    private String title;
    private String content;
    private double score;
    private String source;
    private Map<String, Double> subScores;
}
