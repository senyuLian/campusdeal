package com.campusdeal.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档分块后的检索单元（200-500 字符），是向量存储和混合检索的最小粒度。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    private String chunkId;
    private String docId;
    private String text;
    private int chunkIndex;
    private float[] embedding;
}
