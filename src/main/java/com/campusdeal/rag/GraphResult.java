package com.campusdeal.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次图谱查询的返回结果：子图节点、关系边以及实际执行的 Cypher（用于调试）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphResult {

    private List<GraphNode> nodes;
    private List<GraphEdge> edges;
    private String cypherQuery;
}
