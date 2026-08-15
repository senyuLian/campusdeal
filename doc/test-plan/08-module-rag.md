# 08 · 模块测试：RAG 检索与知识图谱

> 覆盖 BM25 关键词检索、DeepSeek Embedding + PGVector 向量检索、RRF 融合、
> Neo4j 知识图谱查询、索引构建与文档加载。

## 1. 架构与检索流程

```
查询 query
 ├─ BM25 检索（内存索引，topK*2）
 ├─ 向量检索（embedder.embed → PGVector 余弦 ANN，topK*2）
 └─ RRF 融合：score(d) = Σ 1/(k + rank_i)，k=60 → 取 topK
```

| 组件 | 实现 | 说明 |
|---|---|---|
| BM25 | `rag/InMemoryBM25Index` | K1=1.5, B=0.75，unigram+bigram 分词，纯内存 |
| 向量 | `rag/PgVectorStoreImpl` | PG 表 `document_vectors`，`vector(1024)`，IVFFlat 索引 |
| Embedding | `rag/DeepSeekTextEmbedder` | DeepSeek embedding 模型，1024 维 |
| 融合 | `rag/HybridRetrieverImpl` | RRF k=60 |
| 图谱 | `rag/Neo4jClientImpl` | 节点 Merchant/Product/Category/Tag/Building，关系 SELLS/LOCATED_IN/BELONGS_TO/TAGGED_AS/SIMILAR_TO |
| 索引 | `rag/IndexBuilder` | 启动全量构建 + `@Scheduled` 每日增量重建 |
| 加载 | `rag/ClasspathDocumentLoader` | 读 `resources/rag/**/*.md`，分块 200-500 字符 |

## 2. 文档资源

```
resources/rag/
├── faq/  refund.md / coupon.md / account.md
├── policy/ merchant-policy.md
└── merchant/ canteen-a.md
```

## 3. BM25 用例（BM）

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| BM-01 | 相关命中 | 建索引后检索 | 相关文档排前 |
| BM-02 | 空索引 | 空库检索 | 空结果 |
| BM-03 | 无匹配 | 无关 query | 空/低分 |
| BM-04 | 去重 | 重复文档 | 不重复返回 |
| BM-05 | 大小写/中英 | 混合语料 | 分词正确（unigram+bigram） |

## 4. 向量存储用例（PV）

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| PV-01 | 写入 | save 向量 | `document_vectors` 插入 |
| PV-02 | 查询 | 余弦相似度 | topK 结果正确排序 |
| PV-03 | 空库 | 无向量 | 空结果（检索降级） |
| PV-04 | 未配置 | `pgvector.url` 为空 | 应用启动，向量检索禁用，BM25 可用 |
| PV-05 | DB 异常 | PG 不可用 | 降级（catch 后返回空/仅 BM25） |

## 5. 混合检索用例（HR）

| ID | 用例 | 场景 | 预期 |
|---|---|---|---|
| HR-01 | 双侧命中 | BM25+向量都有 | RRF 融合去重，topK 返回 |
| HR-02 | 单侧命中 | 仅 BM25 或仅向量 | 返回单侧结果，不丢 |
| HR-03 | 双侧空 | 均无 | 空结果 |
| HR-04 | 排名融合 | 已知两个排名 | score=Σ1/(60+rank) 正确 |
| HR-05 | 融合去重 | 同一 doc 两侧都中 | 只出现一次 |

## 6. 知识图谱用例（KG）

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| KG-01 | 关系查询 | queryRelatedEntities(实体, null, 2) | 返回节点+关系 |
| KG-02 | 空实体 | 空 query | 空结果 |
| KG-03 | Neo4j 未启动 | 懒加载连接 | 降级为空结果，应用不崩 |
| KG-04 | 深度限制 | maxDepth=2 | 不超过深度 |

## 7. 索引构建用例（IB）

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| IB-01 | 全量构建 | 启动 IndexBuilder | 加载文档→分块→批量 embedding→写 PGVector→建 BM25 |
| IB-02 | 批量大小 | 大语料 | 每批 50 条 embedding |
| IB-03 | 每日重建 | @Scheduled | 增量重建不重复 |
| IB-04 | 加载失败 | md 缺失 | 跳过并告警，不崩 |

## 8. 工具集成用例（SF / SG）

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| SF-01 | search_faq | `retrieve(keyword,5)` | 返回 RetrievalResult 列表 JSON |
| SF-02 | search_faq 空 keyword | - | 空/error |
| SG-01 | search_graph | `queryRelatedEntities(query,null,2)` | GraphResult JSON |
| SG-02 | search_graph 空 query | - | 空/error |

## 9. 降级验收标准

| 依赖缺失 | 行为 | 验收 |
|---|---|---|
| PGVector 未部署 | 向量检索禁用 | BM25 仍可用，Agent 问答不中断 |
| Neo4j 未部署 | 图谱降级空结果 | FAQ 检索兜底 |
| DeepSeek Key 缺失 | embedding 不可用 | 启动正常；调用时报错 → 降级 BM25-only |
| 全部缺失 | 纯 BM25 | FAQ/帮助类问答可用 |

## 10. 现有测试

`rag/` 下 7 个测试类（BM/PV/HR/KG/加载/embedder/indexbuilder），均 Mock 外部依赖。
补：PV-04/05 降级路径、HR 融合数值断言、图谱空降级。

## 11. 验收标准

- BM25 + 向量 + RRF 融合结果合理（相关优先、去重）。
- 任意外部依赖缺失均可降级运行，不影响 Agent 主链路。
- 索引构建幂等可重复；文档加载健壮。
