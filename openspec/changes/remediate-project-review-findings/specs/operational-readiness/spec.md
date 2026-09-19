# Spec Delta

## Purpose

定义 CampusDeal 的数据库迁移、环境配置、运行健康度、告警和发布验证要求，使关键降级与一致性故障可发现、可回滚并能在隔离环境中重复验证。

## ADDED Requirements

### Requirement: Database changes are versioned and repeatable
The system SHALL manage schema changes through ordered, versioned migrations that can initialize an empty supported database and upgrade the previous supported schema without manual DDL.

#### Scenario: Empty database is initialized
- **WHEN** migrations run against an empty supported database
- **THEN** all required tables, constraints, indexes, non-null defaults, and auxiliary schemas are created in a deterministic order

#### Scenario: Existing data violates a new constraint
- **WHEN** an upgrade detects rows that cannot satisfy a new invariant
- **THEN** a documented repair step or migration resolves them before the constraint is enforced, and the migration does not silently discard data

### Requirement: Environment configuration contains no committed secrets
Deployable configuration SHALL obtain database, Redis, Kafka, LLM, graph, vector, and upload settings from environment-specific inputs. Source-controlled defaults MUST NOT contain usable passwords, tokens, or machine-specific absolute paths.

#### Scenario: Required production secret is absent
- **WHEN** a production profile starts without a required secret
- **THEN** startup fails with the missing property name and does not print any secret value

#### Scenario: Optional integration is disabled
- **WHEN** an optional integration is explicitly disabled
- **THEN** readiness describes it as disabled and the application does not start its clients or background work

### Requirement: Critical pipelines expose health and metrics
The system SHALL expose health and metrics for Kafka consumer lag and recovery, oldest PENDING Outbox age, FAILED messages, accepted-versus-persisted orders, stock reconciliation, cache rebuild failures, Canal connection, retrieval degradation, and Agent timeouts.

#### Scenario: Recoverable backlog exceeds threshold
- **WHEN** a configured backlog age, count, or reconciliation threshold is exceeded
- **THEN** health becomes degraded and an alertable metric identifies the affected component and resource

#### Scenario: Sensitive data reaches telemetry
- **WHEN** metrics or logs are emitted for a user operation
- **THEN** they use bounded labels and correlation identifiers without raw prompts, tokens, verification codes, phone numbers, or payload bodies

### Requirement: Release gates exercise real dependency semantics
The release verification suite SHALL include isolated integration tests with supported Redis, MySQL, and Kafka versions for authorization, cache locking, transaction commit, redelivery, compensation, recovery, SSE ordering, and RAG configuration. Test data MUST be isolated from developer and production data.

#### Scenario: Pull request changes a critical pipeline
- **WHEN** code affecting authentication, uploads, flash deals, cache, Agent, or RAG is proposed for release
- **THEN** the relevant integration suite runs and a failure blocks the release gate

#### Scenario: Documented test count changes
- **WHEN** tests are added, removed, or reclassified
- **THEN** generated or maintained project documentation reports the current commands, counts, dependencies, and known exclusions

### Requirement: Failure recovery is documented and reversible
The system SHALL document rollout, rollback, data repair, and feature-disable procedures for each critical integration introduced or changed by this proposal.

#### Scenario: New reliability path causes production errors
- **WHEN** operators disable the new path using its documented rollback controls
- **THEN** the system returns to a known safe behavior without losing already durable order intents or corrupting schema history
