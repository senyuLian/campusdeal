package com.campusdeal.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 一条待写入向量库的条目：docId + embedding + 元数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorEntry {

    private String docId;
    private float[] embedding;
    private Map<String, Object> metadata;
}
