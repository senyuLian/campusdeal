package com.campusdeal.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * BM25 稀疏检索的单条结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BM25Result {

    private String docId;
    private String title;
    private String content;
    private double score;
    private String source;
}
