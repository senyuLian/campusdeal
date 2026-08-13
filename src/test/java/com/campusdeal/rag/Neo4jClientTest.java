package com.campusdeal.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KG-01..03：Neo4j 知识图谱客户端单元测试（Mock Driver，无需真实 Neo4j 实例）。
 */
class Neo4jClientTest {

    private Node node(String id, String label, String name) {
        Node n = mock(Node.class);
        when(n.elementId()).thenReturn(id);
        when(n.labels()).thenReturn(List.of(label));
        when(n.asMap()).thenReturn(Map.of("name", name));
        return n;
    }

    private Relationship rel(String from, String to, String type) {
        Relationship r = mock(Relationship.class);
        when(r.startNodeElementId()).thenReturn(from);
        when(r.endNodeElementId()).thenReturn(to);
        when(r.type()).thenReturn(type);
        return r;
    }

    private Record record(Node n, Node related, List<Relationship> rels) {
        // 直接 mock Value 接口：Values.value(mockNode) 无法把 Mockito 代理转换为 Neo4j Value
        Value nVal = mock(Value.class);
        when(nVal.asNode()).thenReturn(n);
        Value relatedVal = mock(Value.class);
        when(relatedVal.asNode()).thenReturn(related);
        Value relsVal = mock(Value.class);
        when(relsVal.asList(org.mockito.ArgumentMatchers.<Function<Value, Relationship>>any()))
                .thenReturn(rels);
        Record rec = mock(Record.class);
        when(rec.get("n")).thenReturn(nVal);
        when(rec.get("related")).thenReturn(relatedVal);
        when(rec.get("rels")).thenReturn(relsVal);
        return rec;
    }

    @Test
    @DisplayName("KG-01 查商家关联：食堂A -[SELLS]-> 黄焖鸡米饭 返回节点与边")
    void kg01_relatedEntities() {
        Driver driver = mock(Driver.class);
        Session session = mock(Session.class);
        Result result = mock(Result.class);
        when(driver.session()).thenReturn(session);

        Node merchant = node("n-1", "Merchant", "食堂A");
        Node product = node("n-2", "Product", "黄焖鸡米饭");
        Relationship sells = rel("n-1", "n-2", "SELLS");
        // record() 内部会调用 when()，必须先完成 stubbing，避免嵌套进外层 when(...) 导致 UnfinishedStubbing
        Record row = record(merchant, product, List.of(sells));
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(row);
        when(session.run(anyString(), anyMap())).thenReturn(result);

        Neo4jClientImpl client = new Neo4jClientImpl(driver);
        GraphResult g = client.queryRelatedEntities("食堂A", null, 2);

        assertThat(g.getNodes()).hasSize(2);
        assertThat(g.getEdges()).hasSize(1);
        assertThat(g.getEdges().get(0).getType()).isEqualTo("SELLS");
        assertThat(g.getNodes()).extracting(GraphNode::getLabel).contains("Merchant", "Product");
        assertThat(g.getCypherQuery()).contains("[*1..2]");
    }

    @Test
    @DisplayName("KG-02 多度遍历：A→B→C 返回三方节点与两条边")
    void kg02_multiHopTraversal() {
        Driver driver = mock(Driver.class);
        Session session = mock(Session.class);
        Result result = mock(Result.class);
        when(driver.session()).thenReturn(session);

        Node nodeA = node("n-a", "Merchant", "A");
        Node nodeB = node("n-b", "Product", "B");
        Node nodeC = node("n-c", "Product", "C");
        Relationship ab = rel("n-a", "n-b", "SELLS");
        Relationship bc = rel("n-b", "n-c", "SIMILAR_TO");

        Record row1 = record(nodeA, nodeB, List.of(ab));
        Record row2 = record(nodeA, nodeC, List.of(ab, bc));
        when(result.hasNext()).thenReturn(true, true, false);
        when(result.next()).thenReturn(row1, row2);
        when(session.run(anyString(), anyMap())).thenReturn(result);

        Neo4jClientImpl client = new Neo4jClientImpl(driver);
        GraphResult g = client.queryRelatedEntities("A", null, 2);

        assertThat(g.getNodes()).extracting(GraphNode::getId).contains("n-a", "n-b", "n-c");
        assertThat(g.getEdges()).extracting(GraphEdge::getType).contains("SELLS", "SIMILAR_TO");
    }

    @Test
    @DisplayName("KG-03 不存在的实体：返回空 GraphResult 且带 Cypher")
    void kg03_entityNotFound() {
        Driver driver = mock(Driver.class);
        Session session = mock(Session.class);
        Result result = mock(Result.class);
        when(driver.session()).thenReturn(session);
        when(result.hasNext()).thenReturn(false);
        when(session.run(anyString(), anyMap())).thenReturn(result);

        Neo4jClientImpl client = new Neo4jClientImpl(driver);
        GraphResult g = client.queryRelatedEntities("不存在", null, 1);

        assertThat(g.getNodes()).isEmpty();
        assertThat(g.getEdges()).isEmpty();
        assertThat(g.getCypherQuery()).isNotBlank();
    }

    @Test
    @DisplayName("KG-04 非法关系类型防注入：createRelationship 抛出异常")
    void kg04_invalidRelationTypeRejected() {
        Neo4jClientImpl client = new Neo4jClientImpl(mock(Driver.class));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> client.createRelationship("n-1", "n-2", "SELLS; DROP MATCH"));
    }
}
