package com.campusdeal.rag;

import java.util.List;
import java.util.Map;

/**
 * 知识图谱（Neo4j）：商家-商品-分类-标签 关系网的查询与写入。
 */
public interface KnowledgeGraph {

    /**
     * 查询与给定实体关联的子图。
     *
     * @param entityName   实体名（如 "食堂A"、"奶茶"）
     * @param relationType 关系类型（如 "SELLS"、"LOCATED_IN"），null = 全部
     * @param maxDepth     遍历深度
     */
    GraphResult queryRelatedEntities(String entityName, String relationType, int maxDepth);

    /** 创建 / 更新节点（按 id MERGE） */
    void upsertNode(String label, Map<String, Object> properties);

    /** 创建关系（按 id 匹配两端节点 MERGE） */
    void createRelationship(String fromId, String toId, String relationType);

    /**
     * 语义搜索：通过向量相似度找相关商家 / 商品（Cypher + GDS 插件）。
     * 无 GDS 插件时降级为文本匹配。
     */
    List<GraphResult> semanticSearch(String description, int topK);
}
