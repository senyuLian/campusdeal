package com.campusdeal.agent.tool;

import com.campusdeal.rag.GraphEdge;
import com.campusdeal.rag.GraphNode;
import com.campusdeal.rag.GraphResult;
import com.campusdeal.rag.KnowledgeGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SG-01..02：SearchGraphTool —— Agent 暴露的知识图谱查询工具。
 */
@ExtendWith(MockitoExtension.class)
class SearchGraphToolTest {

    @Mock
    KnowledgeGraph knowledgeGraph;

    @InjectMocks
    SearchGraphTool tool;

    @Test
    @DisplayName("SG-01 查询图谱并返回 JSON 结果")
    void sg01_queriesGraph() {
        when(knowledgeGraph.queryRelatedEntities("食堂A", null, 2)).thenReturn(GraphResult.builder()
                .nodes(List.of(GraphNode.builder().id("n-1").label("Merchant").properties(Map.of("name", "食堂A")).build()))
                .edges(List.of(GraphEdge.builder().fromId("n-1").toId("n-2").type("SELLS").build()))
                .cypherQuery("MATCH ...")
                .build());

        String json = tool.searchGraph("食堂A");

        assertThat(json).contains("食堂A").contains("SELLS");
    }

    @Test
    @DisplayName("SG-02 空查询：返回错误 JSON，不触发图谱查询")
    void sg02_blankQuery() {
        String json = tool.searchGraph("  ");

        assertThat(json).contains("error");
        verifyNoInteractions(knowledgeGraph);
    }
}
