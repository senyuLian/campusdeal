package com.campusdeal.agent.tool;

import cn.hutool.json.JSONUtil;
import com.campusdeal.agent.Tool;
import com.campusdeal.rag.GraphResult;
import com.campusdeal.rag.KnowledgeGraph;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 知识图谱检索工具：查询商家-商品-分类-位置的结构化关系网。
 */
@Component
public class SearchGraphTool {

    @Resource
    private KnowledgeGraph knowledgeGraph;

    @Tool(name = "search_graph",
            description = "搜索 CampusDeal 知识图谱，查询商家信息、商品分类、周边位置等结构化关系。适用于用户询问'附近有什么吃的'、'这个店卖什么'等问题")
    public String searchGraph(String query) {
        if (query == null || query.isBlank()) {
            return "{\"error\":\"query 不能为空\"}";
        }
        GraphResult graph = knowledgeGraph.queryRelatedEntities(query, null, 2);
        return JSONUtil.toJsonStr(graph);
    }
}
