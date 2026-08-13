package com.campusdeal.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 知识图谱中的一个节点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphNode {

    private String id;
    private String label;
    private Map<String, Object> properties;
}
