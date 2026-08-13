# 设计文档 05：检索与知识模块

> 所属 Phase：Phase 3（AI 智能助手 Agent）
> 模块定位：Agent 的记忆——RAG 混合检索（BM25 + 向量 + RRF 融合）+ Neo4j 知识图谱 + FAQ 工具
> 依赖关系：依赖 PGVector 实例 + Neo4j 实例，不依赖 Agent 编排模块

---

## 1. 模块概述

### 1.1 职责

| 组件 | 职责 |
|---|---|
| **Document Loader** | 加载 FAQ 文档、商户政策文本，切分为检索单元 |
| **Text Embedder** | 调用 DeepSeek Embedding API，将文本转成 1024 维向量 |
| **BM25 Index** | 基于分词 + TF-IDF 的稀疏检索（关键词匹配） |
| **PGVector Store** | 存储文本向量，支持 ANN（近似最近邻）检索 |
| **Hybrid Retriever** | BM25 + 向量检索 → RRF（Reciprocal Rank Fusion）融合排序 |
| **Neo4j Client** | 管理知识图谱（商家-商品-分类-标签 关系网） |
| **FAQ Tool** | 暴露给 Agent 的 `searchFaq` 工具——Agent 可调用它搜索知识 |

### 1.2 模块边界

```
                    ┌──────────────────────────────────────────────┐
                    │  本模块                                       │
                    │                                               │
   文档文件 ──▶ DocumentLoader ──▶ Text Embedder ──▶ PGVector Store │
                    │                        │           │          │
                    │                        │           │ 向量     │
                    │                        ▼           ▼          │
                    │                   BM25 Index   ─── Hybrid     │
                    │                        │        Retriever    │
                    │                        │           │          │
   MySQL ────▶ Neo4j Client ──▶ Neo4j Graph  │           │          │
                    │                        │           │          │
                    └────────────────────────┼───────────┼──────────│
                                             │           │          │
                                           SearchFaqTool ──────────│
                                             │                      │
                                             ▼                      │
                                      (Agent ToolRegistry)          │
                                      → 被 Agent 编排模块调用       │
                                      └──────────────────────────────┘
```

### 1.3 与其他模块的关系

```
本模块 ← 依赖 PGVector 数据库（PostgreSQL + pgvector 扩展）
本模块 ← 依赖 Neo4j 图数据库
本模块 ← 依赖 DeepSeek Embedding API
本模块 → 暴露 SearchFaqTool → 被 04-Agent 模块的 ToolRegistry 注册
本模块 → 暴露 SearchGraphTool → 被 04-Agent 模块的 ToolRegistry 注册
本模块 → 不依赖 Phase 2 模块
本模块 → 不依赖 06-安全模块
```

---

## 2. 对外接口（API 契约）

### 2.1 HybridRetriever 接口

```java
public interface HybridRetriever {
    /**
     * 混合检索：BM25 + 向量 → RRF 融合
     * @param query 用户自然语言查询
     * @param topK 返回文档数量
     * @return 排序后的检索结果（含相关性分数）
     */
    List<RetrievalResult> retrieve(String query, int topK);

    /**
     * 索引一篇新文档
     * @param document 文档内容
     */
    void index(Document document);

    /**
     * 批量索引文档（启动时全量加载）
     */
    void batchIndex(List<Document> documents);

    /**
     * 删除文档（通过文档 ID）
     */
    void delete(String docId);
}

@Data
@Builder
public class RetrievalResult {
    private String docId;
    private String title;
    private String content;      // 文档片段
    private double score;        // RRF 融合分数（0-1）
    private String source;       // 来源：faq / merchant_policy / campus_rule
    private Map<String, Double> subScores; // {bm25: 0.85, vector: 0.92}
}
```

### 2.2 PGVectorIndex 接口

```java
public interface VectorStore {
    /**
     * 向量近似最近邻检索
     * @param embedding 查询向量（1024 维）
     * @param topK 返回数量
     * @return 检索结果（按余弦相似度排序）
     */
    List<VectorResult> search(float[] embedding, int topK);

    /**
     * 插入向量（含元数据）
     */
    void insert(String docId, float[] embedding, Map<String, Object> metadata);

    /**
     * 批量插入
     */
    void batchInsert(List<VectorEntry> entries);

    /**
     * 删除向量
     */
    void delete(String docId);
}

@Data
@Builder
public class VectorEntry {
    private String docId;
    private float[] embedding;
    private Map<String, Object> metadata;
}

@Data
@Builder
public class VectorResult {
    private String docId;
    private String content;
    private double cosineSimilarity;
}
```

### 2.3 Neo4j KnowledgeGraph 接口

```java
public interface KnowledgeGraph {
    /**
     * 查询与给定实体关联的节点
     * @param entityName 实体名（如 "食堂A"、"奶茶"）
     * @param relationType 关系类型（如 "SELLS"、"CATEGORY_OF"），null=全部
     * @param maxDepth 遍历深度
     * @return 子图节点和关系
     */
    GraphResult queryRelatedEntities(String entityName, String relationType, int maxDepth);

    /**
     * 创建/更新节点
     */
    void upsertNode(String label, Map<String, Object> properties);

    /**
     * 创建关系
     */
    void createRelationship(String fromId, String toId, String relationType);

    /**
     * 语义搜索：通过向量相似度找相关商家/商品（Cypher + gds 插件）
     */
    List<GraphResult> semanticSearch(String description, int topK);
}

@Data
@Builder
public class GraphResult {
    private List<GraphNode> nodes;
    private List<GraphEdge> edges;
    private String cypherQuery;  // 执行的 Cypher 语句（用于调试）
}

@Data
@Builder
public class GraphNode {
    private String id;
    private String label;
    private Map<String, Object> properties;
}
```

### 2.4 SearchFaqTool（暴露给 Agent）

```java
@Component("searchFaq")
@ToolAnnotation(
    name = "searchFaq",
    description = "搜索 CampusDeal FAQ 知识库，获取平台规则、使用帮助等信息。适用于用户询问'怎么用'、'有什么功能'、'退款规则'等问题。",
    requireConfirmation = false
)
public class SearchFaqTool {
    /**
     * @param query 用户的自然语言问题
     * @return 相关的 FAQ 条目列表
     */
    public String execute(String query) {
        List<RetrievalResult> results = hybridRetriever.retrieve(query, 5);
        return JSONUtil.toJsonStr(results);
    }
}
```

### 2.5 SearchGraphTool（暴露给 Agent）

```java
@Component("searchGraph")
@ToolAnnotation(
    name = "searchGraph",
    description = "搜索 CampusDeal 知识图谱，查询商家信息、商品分类、周边位置等信息。适用于用户询问'附近有什么吃的'、'这个店卖什么'等问题。",
    requireConfirmation = false
)
public class SearchGraphTool {
    /**
     * @param query 用户的查询（商家名/商品名/分类/位置）
     * @return 图谱查询结果（关联节点和关系）
     */
    public String execute(String query) {
        GraphResult graph = knowledgeGraph.queryRelatedEntities(query, null, 2);
        return JSONUtil.toJsonStr(graph);
    }
}
```

---

## 3. 数据模型

### 3.1 PGVector 表结构

```sql
-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 文档表（存储向量 + 文本）
CREATE TABLE document_vectors (
    id              VARCHAR(128) PRIMARY KEY,       -- 文档 ID
    title           VARCHAR(255) NOT NULL,
    content         TEXT NOT NULL,                  -- 原始文本
    embedding       vector(1024) NOT NULL,          -- DeepSeek embedding (1024维)
    source          VARCHAR(50) NOT NULL,           -- faq / merchant_policy / campus_rule
    category        VARCHAR(100),                   -- 退款 / 配送 / 账户 / 优惠券 ...
    metadata        JSONB,                          -- 扩展元数据
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 向量索引（IVFFlat 近似检索，lists=文档数/1000）
CREATE INDEX idx_embedding ON document_vectors
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
```

### 3.2 Neo4j 图 Schema

```cypher
// 节点类型
(:Merchant {id, name, address, latitude, longitude, rating, category})
(:Product {id, name, price, description})
(:Category {name})               // 品类：餐饮 / 超市 / 打印 / 快递
(:Tag {name})                    // 标签：奶茶 / 麻辣烫 / 自习 / 外卖
(:Building {name, latitude, longitude})  // 教学楼/宿舍楼（地点参照）

// 关系类型
(:Merchant)-[:SELLS]->(:Product)
(:Merchant)-[:LOCATED_IN]->(:Building)
(:Merchant)-[:BELONGS_TO]->(:Category)
(:Product)-[:TAGGED_AS]->(:Tag)
(:Product)-[:SIMILAR_TO]->(:Product)   // "看了这家还看了"
```

### 3.3 BM25 数据结构

```java
/**
 * 内存 BM25 索引
 * 基于分词 + TF-IDF，适合 FAQ 场景（文档量 < 10万）
 */
public class InMemoryBM25Index {
    /** 文档 ID → 分词后的词频表 */
    private final Map<String, Map<String, Integer>> docTermFreq = new HashMap<>();
    /** 词 → 文档频次（出现在几个文档中） */
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    /** 文档 ID → 原始文档 */
    private final Map<String, Document> documents = new HashMap<>();
    /** 总文档数 */
    private int totalDocs = 0;

    // BM25 参数
    private static final double K1 = 1.5;  // 词频饱和度
    private static final double B  = 0.75; // 长度归一化
}
```

### 3.4 Document 加载模型

```java
@Data
@Builder
public class Document {
    private String id;         // 文档 ID
    private String title;      // 标题（如 "如何申请退款"）
    private String content;    // 正文
    private String source;     // faq / merchant_policy / campus_rule
    private String category;   // 退款 / 配送 / 账户 / 优惠券
    private Map<String, Object> metadata;
}

@Data
@Builder
public class DocumentChunk {
    private String chunkId;     // 分段 ID
    private String docId;       // 所属文档 ID
    private String text;        // 分段文本（200-500 字符）
    private int chunkIndex;     // 在文档中的位置
    private float[] embedding;  // 向量（加载后填充）
}
```

---

## 4. 核心类设计

### 4.1 类图总览

```
┌──────────────────────────┐       ┌──────────────────────────┐
│    DocumentLoader          │       │    TextEmbedder           │
├──────────────────────────┤       ├──────────────────────────┤
│ - Path faqDir             │       │ - DeepSeek API client     │
│ - Path policyDir          │       │ - String model: deepseek- │
├──────────────────────────┤       │   embedder                │
│ + loadFaqs(): List<Doc>  │       ├──────────────────────────┤
│ + loadPolicies(): List   │       │ + embed(text): float[1024]│
│ + chunkDocument(): List   │──────▶│ + embedBatch(texts): List │
└──────────────────────────┘       └──────────────────────────┘
                                              │
                    ┌─────────────────────────┼──────────────────┐
                    │                         │                  │
                    ▼                         ▼                  ▼
          ┌──────────────────┐   ┌──────────────────┐  ┌──────────────────┐
          │  InMemoryBM25Index│   │  PgVectorStore    │  │  HybridRetriever  │
          │  (Lucene-free)   │   │  (JdbcTemplate    │  │  (融合 + 重排)    │
          ├──────────────────┤   │   + pgvector)     │  ├──────────────────┤
          │ + search(): List  │   ├──────────────────┤  │ + retrieve(): List│
          │ + index(): void   │   │ + search(): List  │  │ - bm25Search()   │
          │ - tokenize()      │   │ + insert(): void  │  │ - vectorSearch() │
          │ - computeScore()  │   │ - cosineDist()    │  │ - rrfFusion()    │
          └──────────────────┘   └──────────────────┘  └──────────────────┘

┌──────────────────────────┐       ┌──────────────────────────┐
│    Neo4jClient             │       │    IndexBuilder           │
├──────────────────────────┤       │ (数据加载编排)              │
│ - Driver neo4jDriver      │       ├──────────────────────────┤
│ - String uri, user, pass  │       │ - DocumentLoader loader   │
├──────────────────────────┤       │ - TextEmbedder embedder   │
│ + executeCypher(): Result │       │ - InMemoryBM25Index bm25  │
│ + semanticSearch(): List  │       │ - PgVectorStore pgvector  │
│ - toGraphResult()         │       │ - Neo4jClient neo4j       │
└──────────────────────────┘       ├──────────────────────────┤
                                   │ + buildAll(): void         │
                                   │   (启动时全量构建)          │
                                   │ + reindex(): void           │
                                   │   (@Scheduled 每日增量)     │
                                   └──────────────────────────┘
```

### 4.2 HybridRetriever 实现（核心算法）

```java
@Component
@Slf4j
public class HybridRetrieverImpl implements HybridRetriever {

    @Resource private InMemoryBM25Index bm25Index;
    @Resource private PgVectorStore vectorStore;
    @Resource private TextEmbedder embedder;

    private static final double RRF_K = 60;  // RRF 融合参数

    @Override
    public List<RetrievalResult> retrieve(String query, int topK) {
        // === Step 1: BM25 关键词检索（稀疏） ===
        List<RetrievalResult> bm25Results = bm25Search(query, topK * 2);

        // === Step 2: 向量语义检索（稠密） ===
        List<RetrievalResult> vectorResults = vectorSearch(query, topK * 2);

        // === Step 3: RRF 融合排序 ===
        List<RetrievalResult> fused = rrfFusion(bm25Results, vectorResults, topK);

        log.debug("Hybrid retrieval: query='{}', bm25={}, vector={}, fused={}",
                query, bm25Results.size(), vectorResults.size(), fused.size());

        return fused;
    }

    /**
     * BM25 检索：基于关键词匹配
     */
    private List<RetrievalResult> bm25Search(String query, int topK) {
        return bm25Index.search(query, topK).stream()
                .map(bm25Result -> RetrievalResult.builder()
                        .docId(bm25Result.getDocId())
                        .title(bm25Result.getTitle())
                        .content(bm25Result.getContent())
                        .score(bm25Result.getScore())
                        .subScores(Map.of("bm25", bm25Result.getScore()))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 向量检索：基于语义相似度
     */
    private List<RetrievalResult> vectorSearch(String query, int topK) {
        // Step 1: 将查询转为向量
        float[] queryEmbedding = embedder.embed(query);

        // Step 2: PGVector 近似最近邻检索
        List<VectorResult> vectorResults = vectorStore.search(queryEmbedding, topK);

        return vectorResults.stream()
                .map(vr -> RetrievalResult.builder()
                        .docId(vr.getDocId())
                        .content(vr.getContent())
                        .score(vr.getCosineSimilarity())  // 0-1
                        .subScores(Map.of("vector", (double) vr.getCosineSimilarity()))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Reciprocal Rank Fusion（RRF）融合
     *
     * RRF 原理：
     *   score(d) = Σ 1 / (k + rank_i(d))
     *   其中 rank_i(d) 是文档 d 在第 i 个检索器中的排名（从 1 开始）
     *
     * 为什么用 RRF 而不是加权求和？
     *   - BM25 分数和余弦相似度的数值范围不同，直接加权需要复杂的归一化
     *   - RRF 天然无需归一化——它只看排名，不看原始分数
     *   - k=60 来自学术界最佳实践（TREC 评估）
     */
    private List<RetrievalResult> rrfFusion(
            List<RetrievalResult> bm25Results,
            List<RetrievalResult> vectorResults,
            int topK) {

        // docId → 融合分数
        Map<String, Double> fusionScores = new HashMap<>();
        Map<String, RetrievalResult> merged = new LinkedHashMap<>();

        // BM25 排名贡献
        for (int i = 0; i < bm25Results.size(); i++) {
            String docId = bm25Results.get(i).getDocId();
            double rrfScore = 1.0 / (RRF_K + i + 1);
            fusionScores.merge(docId, rrfScore, Double::sum);
            merged.putIfAbsent(docId, bm25Results.get(i));
        }

        // 向量排名贡献
        for (int i = 0; i < vectorResults.size(); i++) {
            String docId = vectorResults.get(i).getDocId();
            double rrfScore = 1.0 / (RRF_K + i + 1);
            fusionScores.merge(docId, rrfScore, Double::sum);
            merged.putIfAbsent(docId, vectorResults.get(i));
        }

        // 按融合分数降序排序
        return fusionScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    RetrievalResult r = merged.get(entry.getKey());
                    r.setScore(entry.getValue());
                    return r;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void index(Document document) {
        // 1. 生成 embedding
        float[] embedding = embedder.embed(document.getContent());
        // 2. 插入 PGVector
        vectorStore.insert(document.getId(), embedding, document.getMetadata());
        // 3. 更新 BM25 索引
        bm25Index.index(document);
    }
}
```

### 4.3 InMemoryBM25Index 实现

```java
@Component
public class InMemoryBM25Index {

    private final Map<String, Map<String, Integer>> docTermFreq = new ConcurrentHashMap<>();
    private final Map<String, Integer> documentFrequency = new ConcurrentHashMap<>();
    private final Map<String, Document> documents = new ConcurrentHashMap<>();
    private int totalDocs = 0;
    private double avgDocLength = 0;

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /**
     * 中文分词（使用 Hutool 的 WordsUtils 或 HanLP）
     */
    private List<String> tokenize(String text) {
        // 简化：按字符 unigram + bigram
        // 生产环境可接入 jieba / HanLP
        List<String> tokens = new ArrayList<>();
        text = text.replaceAll("[\\p{P}\\s]+", "").toLowerCase();  // 去标点
        for (int i = 0; i < text.length(); i++) {
            tokens.add(String.valueOf(text.charAt(i)));  // unigram
            if (i < text.length() - 1) {
                tokens.add(text.substring(i, i + 2));    // bigram
            }
        }
        return tokens;
    }

    /**
     * BM25 检索
     */
    public List<BM25Result> search(String query, int topK) {
        List<String> queryTokens = tokenize(query);
        Map<String, Double> scores = new HashMap<>();

        for (Map.Entry<String, Map<String, Integer>> entry : docTermFreq.entrySet()) {
            String docId = entry.getKey();
            Map<String, Integer> termFreq = entry.getValue();
            double score = 0;

            for (String token : queryTokens) {
                int tf = termFreq.getOrDefault(token, 0);
                if (tf == 0) continue;

                int df = documentFrequency.getOrDefault(token, 0);
                if (df == 0) continue;

                // BM25 原始公式
                double idf = Math.log(1 + (totalDocs - df + 0.5) / (df + 0.5));
                double docLen = termFreq.values().stream().mapToInt(Integer::intValue).sum();
                double numerator = tf * (K1 + 1);
                double denominator = tf + K1 * (1 - B + B * docLen / avgDocLength);
                score += idf * numerator / denominator;
            }
            if (score > 0) {
                scores.put(docId, score);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    Document doc = documents.get(e.getKey());
                    return new BM25Result(doc.getId(), doc.getTitle(),
                            doc.getContent(), e.getValue());
                })
                .collect(Collectors.toList());
    }
}
```

### 4.4 PGVectorStore 实现

```java
@Component
@Slf4j
public class PgVectorStoreImpl implements VectorStore {

    @Resource private JdbcTemplate jdbcTemplate;

    private static final String INSERT_SQL = """
        INSERT INTO document_vectors (id, title, content, embedding, source, category, metadata)
        VALUES (?, ?, ?, ?::vector, ?, ?, ?::jsonb)
        ON CONFLICT (id) DO UPDATE SET
            embedding = EXCLUDED.embedding,
            content = EXCLUDED.content,
            updated_at = CURRENT_TIMESTAMP
        """;

    private static final String SEARCH_SQL = """
        SELECT id, content, metadata,
               1 - (embedding <=> ?::vector) AS similarity
        FROM document_vectors
        WHERE source = ?
        ORDER BY embedding <=> ?::vector
        LIMIT ?
        """;

    @Override
    public List<VectorResult> search(float[] embedding, int topK) {
        String vectorStr = Arrays.toString(embedding)
                .replace("[", "[").replace("]", "]");  // PG vector 格式

        return jdbcTemplate.query(SEARCH_SQL, (rs, rowNum) ->
            VectorResult.builder()
                .docId(rs.getString("id"))
                .content(rs.getString("content"))
                .cosineSimilarity(rs.getDouble("similarity"))
                .build(),
            vectorStr, vectorStr, topK
        );
    }

    @Override
    public void insert(String docId, float[] embedding, Map<String, Object> metadata) {
        String vectorStr = embeddingToString(embedding);
        jdbcTemplate.update(INSERT_SQL, docId, "", "", vectorStr,
                metadata.getOrDefault("source", "unknown"),
                metadata.getOrDefault("category", "general"),
                JSONUtil.toJsonStr(metadata));
    }

    private String embeddingToString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
```

### 4.5 Neo4j Client 实现

```java
@Component
@Slf4j
public class Neo4jClientImpl implements KnowledgeGraph {

    private final Driver driver;

    public Neo4jClientImpl(
            @Value("${campusdeal.neo4j.uri}") String uri,
            @Value("${campusdeal.neo4j.user}") String user,
            @Value("${campusdeal.neo4j.password}") String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }

    @Override
    public GraphResult queryRelatedEntities(String entityName, String relationType, int maxDepth) {
        String cypher = """
            MATCH (n)
            WHERE n.name CONTAINS $entityName
                  OR n.category CONTAINS $entityName
                  OR ANY(tag IN n.tags WHERE tag CONTAINS $entityName)
            MATCH path = (n)-[*1..%d]-(related)
            RETURN n, related, relationships(path) AS rels
            LIMIT 50
            """.formatted(maxDepth);

        try (Session session = driver.session()) {
            var result = session.run(cypher, Map.of("entityName", entityName));
            return toGraphResult(result, cypher);
        }
    }

    @Override
    public void upsertNode(String label, Map<String, Object> properties) {
        String cypher = """
            MERGE (n:%s {id: $id})
            SET n += $properties
            """.formatted(label);

        try (Session session = driver.session()) {
            session.run(cypher, Map.of(
                "id", properties.get("id"),
                "properties", properties
            ));
        }
    }

    @Override
    public void createRelationship(String fromId, String toId, String relationType) {
        String cypher = """
            MATCH (a {id: $fromId}), (b {id: $toId})
            MERGE (a)-[:%s]->(b)
            """.formatted(relationType);

        try (Session session = driver.session()) {
            session.run(cypher, Map.of("fromId", fromId, "toId", toId));
        }
    }

    private GraphResult toGraphResult(Result result, String cypher) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();

        while (result.hasNext()) {
            var record = result.next();
            // ... 解析 record 到 nodes/edges
        }

        return GraphResult.builder()
                .nodes(nodes).edges(edges)
                .cypherQuery(cypher)
                .build();
    }
}
```

### 4.6 IndexBuilder（启动时全量构建）

```java
@Component
@Slf4j
public class IndexBuilder implements ApplicationRunner {

    @Resource private DocumentLoader documentLoader;
    @Resource private TextEmbedder embedder;
    @Resource private InMemoryBM25Index bm25Index;
    @Resource private PgVectorStore pgVectorStore;

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== Starting knowledge index build ===");

        // Step 1: 加载所有文档
        List<Document> allDocs = new ArrayList<>();
        allDocs.addAll(documentLoader.loadFaqs());        // FAQ 文档
        allDocs.addAll(documentLoader.loadPolicies());     // 平台政策
        allDocs.addAll(documentLoader.loadMerchants());    // 商户介绍
        log.info("Loaded {} documents", allDocs.size());

        // Step 2: 分块
        List<DocumentChunk> chunks = allDocs.stream()
                .flatMap(doc -> documentLoader.chunkDocument(doc).stream())
                .collect(Collectors.toList());
        log.info("Chunked into {} segments", chunks.size());

        // Step 3: 生成 Embedding（批量，50 条一批）
        List<List<DocumentChunk>> batches = partition(chunks, 50);
        for (int i = 0; i < batches.size(); i++) {
            List<DocumentChunk> batch = batches.get(i);
            List<String> texts = batch.stream()
                    .map(DocumentChunk::getText).collect(Collectors.toList());

            // 调用 DeepSeek Embedding API
            List<float[]> embeddings = embedder.embedBatch(texts);

            // 填充向量
            for (int j = 0; j < batch.size(); j++) {
                batch.get(j).setEmbedding(embeddings.get(j));
            }

            log.info("Embedded batch {}/{} ({} chunks)", i + 1, batches.size(), batch.size());
        }

        // Step 4: 写入 PGVector
        List<VectorEntry> entries = chunks.stream()
                .map(c -> VectorEntry.builder()
                        .docId(c.getChunkId())
                        .embedding(c.getEmbedding())
                        .metadata(Map.of("docId", c.getDocId(), "text", c.getText()))
                        .build())
                .collect(Collectors.toList());
        pgVectorStore.batchInsert(entries);
        log.info("Inserted {} vectors into PGVector", entries.size());

        // Step 5: 构建 BM25 索引（纯内存）
        allDocs.forEach(bm25Index::index);
        log.info("BM25 index built for {} documents", allDocs.size());

        log.info("=== Knowledge index build complete ===");
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
```

---

## 5. 序列图

### 5.1 混合检索完整流程

```
  SearchFaqTool   HybridRetriever   BM25Index   PGVectorStore   TextEmbedder   DeepSeek API
       │                │               │             │              │               │
       │ retrieve(q,5)  │               │             │              │               │
       │───────────────▶│               │             │              │               │
       │                │               │             │              │               │
       │                │── bm25Search(q, 10)          │              │               │
       │                │──────────────▶│             │              │               │
       │                │               │ tokenize    │              │               │
       │                │               │ compute BM25│              │               │
       │                │   top 10      │             │              │               │
       │                │◀──────────────│             │              │               │
       │                │               │             │              │               │
       │                │── vectorSearch(q, 10)        │              │               │
       │                │               │    embed(q)  │              │               │
       │                │               │────────────────────────────▶│               │
       │                │               │             │              │ POST embed    │
       │                │               │             │              │──────────────▶│
       │                │               │             │              │ float[1024]  │
       │                │               │             │              │◀──────────────│
       │                │               │    queryEmbedding          │               │
       │                │               │◀────────────────────────────│               │
       │                │               │             │              │               │
       │                │               │ ANN search (cosine <=>)    │               │
       │                │               │────────────▶│              │               │
       │                │               │   top 10    │              │               │
       │                │               │◀────────────│              │               │
       │                │               │             │              │               │
       │                │── rrfFusion(bm25, vector, 5)             │               │
       │                │   RRF(k=60):                             │               │
       │                │   score(d) = 1/(60+rank_bm25) + 1/(60+rank_vector)        │
       │                │   → 按 score 降序取 top 5                                │
       │                │               │             │              │               │
       │  top 5 results │               │             │              │               │
       │◀───────────────│               │             │              │               │
       │                │               │             │              │               │
       │ return JSON     │               │             │              │               │
```

### 5.2 知识图谱查询流程

```
  SearchGraphTool   KnowledgeGraph   Neo4j Server
       │                 │               │
       │ execute("食堂A") │               │
       │────────────────▶│               │
       │                 │               │
       │                 │ MATCH path = (n)-[*1..2]-(related)
       │                 │ WHERE n.name CONTAINS "食堂A"
       │                 │ RETURN n, related, rels
       │                 │──────────────▶│
       │                 │               │── 图遍历
       │                 │   GraphResult │
       │                 │◀──────────────│
       │                 │               │
       │                 │ (nodes: 食堂A, 黄焖鸡米饭, 奶茶, 东区食堂楼)
       │                 │ (edges: SELLS, SELLS, LOCATED_IN)
       │                 │               │
       │   GraphResult   │               │
       │◀────────────────│               │
       │                 │               │
       │ return JSON     │               │
```

### 5.3 RRF 融合原理示意图

```
  查询: "校园卡怎么退款"

  BM25 排名 (关键词匹配):      向量排名 (语义匹配):
  1. "校园卡使用指南"   0.82    1. "如何申请退款"     0.94
  2. "退款流程说明"     0.75    2. "退款到账时间"     0.91
  3. "如何申请退款"     0.68    3. "校园卡挂失流程"   0.87
  4. "充值方法"         0.55    4. "退款流程说明"     0.85
  5. "退款到账时间"     0.50    5. "校园卡使用指南"   0.72

  RRF 融合 (k=60):
  文档                  BM25排名 向量排名  RRF分数
  "如何申请退款"         3        1        1/63 + 1/61 = 0.0323  ← 第1
  "退款流程说明"         2        4        1/62 + 1/64 = 0.0317  ← 第2
  "退款到账时间"         5        2        1/65 + 1/62 = 0.0315  ← 第3
  "校园卡使用指南"       1        5        1/61 + 1/65 = 0.0317  ← 第4(同分按向量优先)
  "校园卡挂失流程"       -        3        0 + 1/63   = 0.0159  ← 第5

  → 最终返回 top 5，BM25 的"充值方法"因向量分数低被淘汰，"校园卡挂失流程"因向量关联进入
```

---

## 6. 测试策略

### 6.1 测试清单

#### BM25 索引测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| BM-01 | 精确匹配 | 索引了 3 篇关于"退款"的文档 | search("退款") | 3 篇全部返回，"如何申请退款"排第一 |
| BM-02 | 空索引 | 0 篇文档 | search("退款") | 返回空列表 |
| BM-03 | 停用词处理 | 文档含"的""了""吗" | search("怎么退款") | 停用词不影响检索结果 |

#### PGVector 测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| PV-01 | 向量检索 | 已插入 100 条向量 | search(queryVec, 5) | 返回 5 条，按余弦相似度降序 |
| PV-02 | 空向量表 | 0 条记录 | search(queryVec, 5) | 返回空列表 |
| PV-03 | 批量插入 | 50 条向量 | batchInsert | 全部写入，无异常 |
| PV-04 | 重复 ID 更新 | 已存在 id="faq-1" | insert("faq-1", newVec) | ON CONFLICT → 更新向量 |

#### HybridRetriever 测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| HR-01 | BM25 + 向量都有结果 | Mock 两个检索器返回 10 条 | retrieve(q, 5) | RRF 融合后返回 5 条，分数正确 |
| HR-02 | 仅 BM25 有结果 | 向量检索返回空 | retrieve(q, 5) | 返回 BM25 的 top 5 |
| HR-03 | 仅向量有结果 | BM25 返回空 | retrieve(q, 5) | 返回向量的 top 5 |
| HR-04 | 两者都空 | 0 文档 | retrieve(q, 5) | 返回空列表 |
| HR-05 | RRF 去重 | BM25 和向量有重叠文档 | retrieve(q, 5) | 重叠文档的 RRF 分数 = 两个排名贡献之和 |

#### Neo4j 知识图谱测试

| 编号 | 场景 | Given | When | Then |
|---|---|---|---|---|
| KG-01 | 查商家关联 | 图含 食堂A -[SELLS]-> 黄焖鸡米饭 | queryRelatedEntities("食堂A", null, 2) | 返回 食堂A + 黄焖鸡米饭 + SELLS 边 |
| KG-02 | 多度遍历 | 图含 A→B→C | queryRelatedEntities("A", null, 2) | 返回 A、B、C 三方 |
| KG-03 | 不存在的实体 | — | queryRelatedEntities("不存在", null, 1) | 返回空 GraphResult |

### 6.2 测试代码示例

```java
@ExtendWith(MockitoExtension.class)
class HybridRetrieverTest {

    @Mock private InMemoryBM25Index bm25Index;
    @Mock private PgVectorStore vectorStore;
    @Mock private TextEmbedder embedder;

    @InjectMocks private HybridRetrieverImpl retriever;

    @Test
    @DisplayName("HR-01: RRF 融合后正确排序")
    void shouldFuseAndRankCorrectly() {
        // Given: BM25 结果
        when(bm25Index.search("退款", 10)).thenReturn(List.of(
            new BM25Result("doc-1", "退款指南", "...", 0.85),
            new BM25Result("doc-2", "充值帮助", "...", 0.70),
            new BM25Result("doc-3", "配送政策", "...", 0.50)
        ));

        // Given: 向量结果（通过 PGVector + embedder）
        when(embedder.embed("退款")).thenReturn(new float[1024]);
        when(vectorStore.search(any(), eq(10))).thenReturn(List.of(
            VectorResult.builder().docId("doc-2").cosineSimilarity(0.95f).build(),
            VectorResult.builder().docId("doc-1").cosineSimilarity(0.88f).build(),
            VectorResult.builder().docId("doc-4").cosineSimilarity(0.72f).build()
        ));

        // When
        List<RetrievalResult> results = retriever.retrieve("退款", 3);

        // Then: RRF fusion
        // doc-1: 1/(60+1) + 1/(60+2) = 1/61 + 1/62 = 0.01639 + 0.01613 = 0.0325
        // doc-2: 1/(60+2) + 1/(60+1) = 0.01613 + 0.01639 = 0.0325 (同分)
        // doc-3: 1/(60+3) + 0            = 0.01587
        // doc-4: 0        + 1/(60+3)      = 0.01587 (同分)

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getDocId()).isIn("doc-1", "doc-2");
        assertThat(results.get(0).getScore()).isGreaterThan(0.03);
    }

    @Test
    @DisplayName("HR-02: 仅 BM25 有结果时不应抛异常")
    void shouldHandleVectorEmptyResults() {
        when(bm25Index.search(anyString(), anyInt())).thenReturn(List.of(
            new BM25Result("doc-1", "退款", "...", 0.8)
        ));
        when(embedder.embed(anyString())).thenReturn(new float[1024]);
        when(vectorStore.search(any(), anyInt())).thenReturn(Collections.emptyList());

        List<RetrievalResult> results = retriever.retrieve("退款", 3);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDocId()).isEqualTo("doc-1");
    }

    @Test
    @DisplayName("HR-05: RRF 去重——重叠文档的分数应该是两个排名之和")
    void shouldDeduplicateAndCombineScores() {
        // BM25: doc-A rank1, doc-B rank2
        when(bm25Index.search("test", 10)).thenReturn(List.of(
            new BM25Result("A", "Title A", "...", 0.9),
            new BM25Result("B", "Title B", "...", 0.8)
        ));
        // Vector: doc-A rank1, doc-C rank2 (doc-A 重叠)
        when(embedder.embed("test")).thenReturn(new float[1024]);
        when(vectorStore.search(any(), eq(10))).thenReturn(List.of(
            VectorResult.builder().docId("A").cosineSimilarity(0.95f).build(),
            VectorResult.builder().docId("C").cosineSimilarity(0.70f).build()
        ));

        List<RetrievalResult> results = retriever.retrieve("test", 5);

        assertThat(results).hasSize(3);  // A, B, C（不能有重复的 A）
        Optional<RetrievalResult> docA = results.stream()
                .filter(r -> r.getDocId().equals("A")).findFirst();
        assertThat(docA).isPresent();
        // doc-A 的分数应该来自 BM25 rank1 + Vector rank1
        assertThat(docA.get().getScore())
                .isCloseTo(1.0 / 61 + 1.0 / 61, within(0.001));
    }
}
```

### 6.3 测试独立性说明

```
BM25 Index     ← 纯内存，无外部依赖                → 完全独立单测
PGVector       ← Mock JdbcTemplate                → 不需要 PostgreSQL
HybridRetriever← Mock BM25 + Mock VectorStore     → 不需要真实索引
Neo4j Client   ← Mock Neo4j Driver               → 不需要 Neo4j 实例
TextEmbedder   ← WireMock DeepSeek Embedding API  → 不需要 LLM API
IndexBuilder   ← Mock 所有写入组件                → 不需要数据库/API
```

> **PGVector 的特殊说明**：向量 `<=>` 操作符是 pgvector 扩展特有的，单元测试通过 Mock JdbcTemplate 模拟。完整的向量检索→融合链路通过集成测试验证（需 Docker PostgreSQL + pgvector）。

---

## 附录：面试话术

> **"为什么用混合检索而不是纯向量检索？"**
> 纯向量检索在语义理解上很好，但会漏掉精确关键词匹配——比如用户搜"BRC-2024-001 退款"，向量检索可能把它当普通句子匹配到不相关的退款 FAQ。BM25 天然擅长这种精确 ID / 编码匹配。RRF 融合让两者互补：BM25 处理精确匹配，向量处理语义理解。

> **"RRF 融合和加权求和有什么区别？"**
> 加权求和需要做分数归一化——BM25 分数范围可能是 [0, 20]，余弦相似度是 [0, 1]，直接用权重加会让 BM25 主导。归一化又引入额外假设（比如假设分数正态分布）。RRF 只看排名，k=60 是学术界验证的最优参数，无需归一化。

> **"为什么需要 Neo4j 知识图谱？向量检索不能直接找关联商家吗？"**
> 知识图谱提供的是结构化的关系推理，不是文本相似度——"食堂A 卖什么"要查的是 `(食堂A)-[:SELLS]->(产品)`，这不是向量检索能做的。PGVector 负责非结构化文本检索（FAQ），Neo4j 负责结构化关系查询（商家-商品-位置），两者互补。
