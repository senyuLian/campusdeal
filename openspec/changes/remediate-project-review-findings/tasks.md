# Tasks

> Status: 51/64 items have implementation and local evidence. Most unchecked items require Docker/real MySQL, Redis, Kafka, PostgreSQL, PGVector, symlink-capable filesystem, or staging verification. An unchecked verification item does not necessarily mean its implementation is absent.

## 1. Build and Migration Foundations

- [x] 1.1 Add Jakarta Validation, Flyway, PostgreSQL, Micrometer, and Testcontainers dependencies with compatible versions, and verify the application compiles plus the dependency tree contains one intended version of each library
- [ ] 1.2 Create a Flyway baseline that initializes an empty MySQL database to the current supported schema, and verify migration succeeds twice against a clean Testcontainers MySQL instance
- [ ] 1.3 Add incremental migrations for user roles, merchant ownership, upload assets, flash-order intents, outbox lifecycle fields, durable likes/follows, uniqueness constraints, and query indexes, and verify an upgrade fixture preserves existing rows
- [ ] 1.4 Implement deterministic ownership and relation backfills with quarantine/reporting for ambiguous rows, and verify the migration refuses to silently grant access or discard invalid data
- [x] 1.5 Replace committed credentials and machine-specific paths with environment-backed properties and safe local placeholders, and verify a secret scan plus production-profile startup failure names missing properties without printing values
- [ ] 1.6 Add explicit enable flags and conditional bean creation for Kafka, Canal, vector retrieval, and model integrations, and verify each disabled integration starts no client or background thread

## 2. Authorization, Validation, and Upload Safety

- [x] 2.1 Implement a centralized authorization service for roles and merchant/resource ownership on top of the existing token principal, and verify an authorization matrix test covers anonymous, unrelated user, owner, and administrator cases
- [x] 2.2 Enforce authorization again in transactional merchant, coupon, upload, social, order, and Agent write services, and verify direct service tests cannot bypass controller checks
- [x] 2.3 Introduce dedicated validated request DTOs for public write endpoints and bounded pagination, coordinates, times, stock, and message fields, and verify invalid boundary values return no mutation
- [x] 2.4 Add a global HTTP exception mapper with stable error codes and `400`, `401`, `403`, `409`, and `429` statuses, and verify MVC contract tests for each category
- [ ] 2.5 Implement upload creation with decoded-content type detection, size limits, server-generated names, canonical/symlink root containment, and durable owner metadata, and verify valid images succeed while disguised and escaping files fail
- [ ] 2.6 Replace path-based deletion with an authenticated asset-ID delete operation that rechecks ownership and canonical containment, and verify traversal, absolute path, symlink, and cross-owner deletion tests leave files unchanged
- [x] 2.7 Remove the legacy GET deletion mapping and public upload-write exemptions, and verify GET returns `404` or `405` while anonymous write calls return `401`
- [x] 2.8 Consume verification codes with an atomic compare-and-delete Lua script and add phone/origin send and attempt rate limits, and verify concurrent reuse produces one success and throttled requests return `429`
- [x] 2.9 Add log and response redaction for tokens, verification codes, credentials, phone numbers, prompts, and payload bodies, and verify captured logs from representative failures contain none of the seeded sensitive values

## 3. Cache, Canal, and Admission Coherence

- [x] 3.1 Change logical-expiry rebuilds to dedicated lock keys with random owner tokens and Lua compare-and-delete release, and verify a stale worker cannot delete a successor's lock or the rebuilt data
- [x] 3.2 Add negative cache sentinels plus bounded retry/jitter for coordinated cold loads, and verify repeated misses hit MySQL once per negative TTL and concurrent popular-key misses are bounded
- [x] 3.3 Publish cache changes after transaction commit and invalidate all relevant Redis, Caffeine, list, and GEO views idempotently, and verify rollback keeps the old cache while commit converges to the new value
- [x] 3.4 Convert the Canal client into an optional managed lifecycle component with reconnect backoff and graceful shutdown, and verify enabled/disabled startup and connection recovery tests
- [x] 3.5 Correct Canal insert/update/delete row-image and table-key handling, acknowledge only after successful invalidation, and verify delete events use before-images and failed batches are redelivered
- [x] 3.6 Restrict Canal handlers to table-specific derived-cache changes so generic events cannot delete live flash stock, and verify a flash-row update preserves the authoritative stock key
- [x] 3.7 Publish an after-commit new-deal admission event and distribute it to every instance's Bloom filter with periodic rebuild as repair, and verify a newly committed deal is accepted immediately on two simulated instances
- [x] 3.8 Add explicit recovery/unavailable behavior for missing or malformed deal metadata, and verify a missing key does not become a permanent sold-out value

## 4. Durable Flash-Order Pipeline

- [x] 4.1 Define flash intent, provisional reservation, event payload, and string order-ID models with documented state transitions, and verify serialization round trips preserve every identity and status
- [x] 4.2 Replace the admission Lua script with an order-ID-based provisional reservation and matching compare-and-release compensation script, and verify stock is decremented/released once under duplicate and stale-owner calls
- [x] 4.3 Implement the MySQL acceptance transaction that conditionally decrements authoritative stock and inserts the intent plus producer Outbox row, and verify acceptance is returned only after commit
- [x] 4.4 Enforce durable uniqueness for order ID and `(user_id, deal_id)` and make request retries return the existing order/status, and verify concurrent duplicate requests create one intent and consume one stock unit
- [x] 4.5 Implement lease-based reconciliation for orphaned provisional reservations, and verify a process-failure fixture restores only reservations with no committed intent
- [x] 4.6 Rework Outbox creation, claiming, retry scheduling, timestamps, error recording, and publish acknowledgements, and verify new rows age into eligibility and failed sends remain recoverable
- [x] 4.7 Make the Kafka consumer transactionally upsert the final order and advance intent state using database uniqueness as idempotency, and verify duplicate deliveries result in one order
- [x] 4.8 Configure bounded Kafka retries and a dead-letter topic, propagate listener failures, and commit offsets only after database commit or durable dead-letter transfer, and verify an injected database failure redelivers before offset advancement
- [x] 4.9 Implement dead-letter replay by event ID with authorization/audit metadata, and verify replay of a repaired failure reaches one terminal order without duplication
- [x] 4.10 Rebuild Redis stock and purchaser state from configured stock, accepted intents, and completed orders while keeping unreconciled deals closed, and verify Redis-loss recovery cannot admit more than configured stock
- [x] 4.11 Add the owner-authorized order-status endpoint for `ACCEPTED`, `PROCESSING`, `SUCCEEDED`, and `FAILED`, and verify foreign users receive `403` and JSON order IDs remain strings
- [x] 4.12 Add shadow comparison and cutover flags for the new acceptance pipeline plus legacy Outbox repair/quarantine, and verify a staged migration can drain existing recoverable rows before switching traffic

## 5. Agent Session and Output Safety

- [x] 5.1 Store immutable owner and version fields with every Agent session and enforce ownership on read, continue, save, delete, and confirm operations, and verify cross-user access changes no session state
- [x] 5.2 Serialize concurrent session updates with Redis compare-and-set or owner-token locking, and verify two concurrent turns cannot overwrite history or commit the same version
- [x] 5.3 Move pending confirmations to Redis with owner, session, operation digest, and TTL plus atomic verify-and-consume, and verify cross-instance confirmation succeeds once for the owner and an unauthorized attempt does not consume it
- [x] 5.4 Buffer each complete model turn, run safety and PII masking before emitting content, then stream only sanitized chunks, and verify sensitive text split across provider chunks is never observed by the SSE client
- [x] 5.5 Remove duplicate final-answer generation paths and guard persistence/completion callbacks against double finalization, and verify one user turn invokes one final generation and stores one assistant message
- [x] 5.6 Run Agent work on a bounded executor with queue limits, timeouts, and disconnect cancellation propagation, and verify saturation is rejected predictably while timed-out/disconnected work releases resources
- [x] 5.7 Define expiry or audited ownership migration for legacy ownerless sessions, and verify such sessions cannot be claimed by an unrelated user during rollout

## 6. RAG Retrieval Integrity

- [x] 6.1 Replace document chunking with a validated terminating sliding window, and verify empty, short, exact-boundary, overlapping, and long-document tests have contiguous indexes without repeated suffix chunks
- [x] 6.2 Introduce one canonical passage model and stable `<document-id>#<chunk-index>` identity for both BM25 and vector indexing, and verify both channels return matching IDs and metadata for the same passage
- [x] 6.3 Update reciprocal-rank fusion to merge canonical IDs, retain per-channel scores, and enforce per-document diversity, and verify one dual-channel passage occupies one result slot
- [x] 6.4 Gate embeddings, PostgreSQL connections, and vector beans behind the explicit vector flag, and verify disabled mode performs zero vector/embedding calls while BM25 remains available
- [ ] 6.5 Add PGVector datasource, extension/schema migration, dimension/configuration validation, and readiness behavior for enabled mode, and verify valid startup plus actionable fail-fast/degraded cases against PostgreSQL Testcontainers
- [x] 6.6 Add retrieval channel health and bounded-label degradation metrics with query redaction, and verify a vector failure returns BM25 results and telemetry contains no raw query
- [x] 6.7 Build a canonical-passage reindex command with dry-run and resumable progress, and verify it produces expected passage counts before switching search traffic

## 7. Social and Query Consistency

- [x] 7.1 Implement explicit transactional like/unlike operations on the unique durable relation and update counts only on actual transitions, and verify concurrent duplicate requests leave one relation and an exact nonnegative count
- [x] 7.2 Implement idempotent follow/unfollow on the unique durable relation and reject self-follow, and verify database plus Redis mirrors converge under concurrent retries
- [x] 7.3 Add reconciliation jobs for post-like counts and Redis social mirrors from MySQL, and verify seeded drift is detected and repaired without double counting
- [x] 7.4 Replace feed pagination with an opaque score-and-member cursor and snapshot boundary, and verify equal timestamps plus newly inserted posts cause neither duplicates nor gaps across pages
- [x] 7.5 Batch-load post authors and viewer-like state while preserving result order, and verify query-count assertions remain bounded as page size grows
- [x] 7.6 Page nearby candidates through Redis GEO and an indexed bounding-box database fallback before exact distance sorting, and verify the fallback never scans or materializes the full merchant table

## 8. Observability and Recovery Operations

- [x] 8.1 Add bounded-label metrics and health contributors for Kafka lag/DLT, Outbox age/failures, intent age/outcomes, and Redis/MySQL stock differences, and verify seeded backlog or mismatch changes health to degraded
- [x] 8.2 Add metrics and health for cache hit/miss/rebuild/lock contention, Canal lag/reconnects, and Bloom repair, and verify injected failures increment the expected meters without sensitive labels
- [x] 8.3 Add metrics and health for RAG channel state plus Agent executor saturation, timeout, cancellation, and safety rejection, and verify each injected condition is observable
- [ ] 8.4 Write operator runbooks for rollout, rollback, ownership repair, Outbox/DLT replay, order reconciliation, Redis stock rebuild, integration disablement, and secret rotation, and verify every command and property against a local staging profile

## 9. Release Verification

- [ ] 9.1 Add isolated MySQL/Redis integration tests for authorization, upload containment, OTP concurrency, cache locking, transaction-after-commit behavior, social idempotency, and stable feed pagination, and verify the tagged Maven suite passes from a clean checkout
- [ ] 9.2 Add isolated Kafka/MySQL/Redis tests for acceptance commit, duplicate delivery, retry, DLT, process interruption, Outbox compensation, and Redis-loss recovery, and verify no scenario loses an accepted intent or exceeds stock
- [ ] 9.3 Add Agent integration tests that capture SSE ordering, session isolation, shared confirmation, timeout, and disconnect behavior, and verify no unverified content precedes the safety decision
- [ ] 9.4 Add BM25-only and PostgreSQL/vector integration profiles for chunking, canonical fusion, degradation, and startup validation, and verify both enabled and disabled configurations pass
- [ ] 9.5 Run the complete unit and integration release gates, update the project review/testing documentation with current commands and counts, and verify no P0/P1 finding remains open without an explicit tracked exception
