# Spec Delta

## Purpose

定义知识文档分块、稀疏与向量索引融合、向量后端启用和降级时的可验证行为，使检索结果不因重复尾块或身份错配而失真。

## ADDED Requirements

### Requirement: Document chunking terminates with bounded overlap
The system SHALL split each non-empty document into ordered chunks that cover the source content, use no more than the configured overlap between adjacent chunks, and stop immediately after emitting the chunk that reaches the source end.

#### Scenario: Document is shorter than the chunk size
- **WHEN** a non-empty document length is below the configured chunk size
- **THEN** exactly one chunk is emitted with the complete content

#### Scenario: Document crosses a chunk boundary
- **WHEN** a document requires multiple chunks
- **THEN** chunk indexes are contiguous, overlap is bounded, and no repeated shrinking suffix chunks are emitted

### Requirement: Hybrid retrieval fuses one canonical identity
Sparse and vector results for the same source passage SHALL share or resolve to one canonical identity before reciprocal-rank fusion, and the fused result SHALL preserve title, source, category, passage text, and per-channel scores.

#### Scenario: Both channels retrieve the same passage
- **WHEN** BM25 and vector search return the same canonical passage
- **THEN** it occupies one result slot with contributions from both channels

#### Scenario: Multiple passages belong to one document
- **WHEN** several chunks from one document rank highly
- **THEN** the response preserves each selected passage's source identity and applies the configured document-diversity policy

### Requirement: Vector retrieval has an explicit enablement contract
The system SHALL enable PGVector only when its feature flag, JDBC driver, database schema, credentials, and embedding configuration are valid. When disabled, it MUST skip embedding requests and serve BM25 results without attempting the vector backend.

#### Scenario: Vector retrieval is disabled
- **WHEN** vector retrieval is not enabled
- **THEN** no embedding API call or PostgreSQL connection is attempted and BM25 remains available

#### Scenario: Enabled vector backend is misconfigured
- **WHEN** vector retrieval is enabled but startup validation cannot load its driver, schema, or configuration
- **THEN** application readiness reports the vector capability unavailable with an actionable reason according to the configured fail-fast policy

### Requirement: Retrieval degradation is observable
The system SHALL expose whether each retrieval channel is enabled, healthy, degraded, or failed and SHALL count degraded searches without disclosing query content.

#### Scenario: Vector search fails during a request
- **WHEN** a transient vector or embedding failure occurs and BM25 is healthy
- **THEN** the query returns BM25 results, records a degraded-search metric, and does not expose internal exception details to the caller
