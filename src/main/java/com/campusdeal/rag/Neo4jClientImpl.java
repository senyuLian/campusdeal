package com.campusdeal.rag;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Neo4j 知识图谱客户端。
 *
 * <p>Driver 懒加载：{@code GraphDatabase.driver(...)} 本身不会立即建连，连接在首次查询时才建立；
 * 实例未启动时应用照常启动，查询会降级为空结果并记录告警。
 * 节点标签 / 关系类型通过 {@link #safeLabel} 白名单校验，防止 Cypher 注入。</p>
 */
@Slf4j
@Component
public class Neo4jClientImpl implements KnowledgeGraph {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private final String uri;
    private final String user;
    private final String password;

    /** 测试注入点：@InjectMocks 无法构造 @Value 构造器，测试用测试构造器注入 mock Driver */
    private volatile Driver driver;

    @Autowired
    public Neo4jClientImpl(
            @Value("${campusdeal.neo4j.uri:bolt://localhost:7687}") String uri,
            @Value("${campusdeal.neo4j.user:neo4j}") String user,
            @Value("${campusdeal.neo4j.password:neo4j}") String password) {
        this.uri = uri;
        this.user = user;
        this.password = password;
    }

    /** 测试构造器：跳过 @Value，直接注入 mock Driver */
    Neo4jClientImpl(Driver driver) {
        this.uri = "bolt://localhost:7687";
        this.user = "neo4j";
        this.password = "neo4j";
        this.driver = driver;
    }

    @Override
    public GraphResult queryRelatedEntities(String entityName, String relationType, int maxDepth) {
        int depth = Math.max(1, Math.min(maxDepth, 3));
        boolean filterByRelation = relationType != null && !relationType.isBlank();

        String cypher;
        Map<String, Object> params = new HashMap<>();
        params.put("entityName", entityName);
        if (filterByRelation) {
            params.put("relationType", relationType);
            cypher = """
                MATCH (n)
                WHERE n.name CONTAINS $entityName
                   OR n.category CONTAINS $entityName
                   OR ANY(tag IN n.tags WHERE tag CONTAINS $entityName)
                MATCH path = (n)-[r*1..%d]-(related)
                WHERE ALL(x IN r WHERE type(x) = $relationType)
                RETURN n, related, relationships(path) AS rels
                LIMIT 50
                """.formatted(depth);
        } else {
            cypher = """
                MATCH (n)
                WHERE n.name CONTAINS $entityName
                   OR n.category CONTAINS $entityName
                   OR ANY(tag IN n.tags WHERE tag CONTAINS $entityName)
                MATCH path = (n)-[*1..%d]-(related)
                RETURN n, related, relationships(path) AS rels
                LIMIT 50
                """.formatted(depth);
        }

        Driver d = resolveDriver();
        if (d == null) {
            return emptyResult(cypher);
        }
        try (Session session = d.session()) {
            Result result = session.run(cypher, params);
            return toGraphResult(result, cypher);
        } catch (Exception e) {
            log.warn("Neo4j 图谱查询失败（实例未就绪？）: {}", e.getMessage());
            return emptyResult(cypher);
        }
    }

    @Override
    public void upsertNode(String label, Map<String, Object> properties) {
        String safe = safeLabel(label);
        String cypher = "MERGE (n:%s {id: $id}) SET n += $properties".formatted(safe);
        Driver d = resolveDriver();
        if (d == null) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("id", properties == null ? null : properties.get("id"));
        params.put("properties", properties);
        try (Session session = d.session()) {
            session.run(cypher, params);
        } catch (Exception e) {
            log.warn("Neo4j upsertNode 失败: {}", e.getMessage());
        }
    }

    @Override
    public void createRelationship(String fromId, String toId, String relationType) {
        String safe = safeLabel(relationType);
        String cypher = "MATCH (a {id: $fromId}), (b {id: $toId}) MERGE (a)-[r:%s]->(b)".formatted(safe);
        Driver d = resolveDriver();
        if (d == null) {
            return;
        }
        try (Session session = d.session()) {
            session.run(cypher, Map.of("fromId", fromId, "toId", toId));
        } catch (Exception e) {
            log.warn("Neo4j createRelationship 失败: {}", e.getMessage());
        }
    }

    @Override
    public List<GraphResult> semanticSearch(String description, int topK) {
        // 生产环境：Neo4j GDS 插件 gds.similarity.cosine 做向量语义搜索；
        // 此处用文本包含匹配作为可运行的降级实现（无 GDS 插件依赖）。
        String cypher = """
            MATCH (n)
            WHERE n.name CONTAINS $description
               OR n.category CONTAINS $description
               OR n.description CONTAINS $description
            RETURN n AS n, n AS related, [] AS rels
            LIMIT %d
            """.formatted(Math.max(1, topK));
        Driver d = resolveDriver();
        if (d == null) {
            return List.of();
        }
        try (Session session = d.session()) {
            Result result = session.run(cypher, Map.of("description", description));
            GraphResult g = toGraphResult(result, cypher);
            return g.getNodes().isEmpty() ? List.of() : List.of(g);
        } catch (Exception e) {
            log.warn("Neo4j semanticSearch 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 解析 Cypher 返回的记录为 GraphResult（去重节点 / 边） */
    private GraphResult toGraphResult(Result result, String cypher) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> edgeKeys = new LinkedHashSet<>();

        while (result.hasNext()) {
            Record record = result.next();
            addNode(nodes, nodeIds, record.get("n").asNode());
            addNode(nodes, nodeIds, record.get("related").asNode());
            List<Relationship> rels = record.get("rels").asList(Values.ofRelationship());
            for (Relationship rel : rels) {
                String from = rel.startNodeElementId();
                String to = rel.endNodeElementId();
                String key = from + "->" + to + ":" + rel.type();
                if (edgeKeys.add(key)) {
                    edges.add(GraphEdge.builder().fromId(from).toId(to).type(rel.type()).build());
                }
            }
        }

        return GraphResult.builder().nodes(nodes).edges(edges).cypherQuery(cypher).build();
    }

    private void addNode(List<GraphNode> nodes, Set<String> nodeIds, Node node) {
        String id = node.elementId();
        if (nodeIds.add(id)) {
            Iterator<String> it = node.labels().iterator();
            String label = it.hasNext() ? it.next() : "UNKNOWN";
            nodes.add(GraphNode.builder().id(id).label(label).properties(node.asMap()).build());
        }
    }

    private Driver resolveDriver() {
        Driver d = driver;
        if (d == null) {
            synchronized (this) {
                if (driver == null) {
                    try {
                        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
                    } catch (Exception e) {
                        log.warn("Neo4j 驱动创建失败: {}", e.getMessage());
                        return null;
                    }
                }
                d = driver;
            }
        }
        return d;
    }

    private static GraphResult emptyResult(String cypher) {
        return GraphResult.builder().nodes(List.of()).edges(List.of()).cypherQuery(cypher).build();
    }

    /** 校验并返回可安全嵌入 Cypher 的标识符（防注入） */
    private static String safeLabel(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("非法图谱标识符: " + identifier);
        }
        return identifier;
    }
}
