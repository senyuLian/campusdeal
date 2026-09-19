# Design

## Context

The application combines a custom Redis-token authentication layer, MySQL persistence, Redis caches and admission controls, Kafka order delivery, an outbox relay, local Bloom filters, Canal invalidation, an SSE-based agent, and hybrid RAG. The review found several boundaries where the code assumes that another component will provide authorization, durability, retry, lifecycle management, or identity normalization, but that guarantee is not actually present.

The remediation must preserve the current Spring Boot/MyBatis-Plus architecture and existing public business flows while making state ownership explicit. MySQL will remain the source of truth for durable business data. Redis will provide sessions, coordination, caching, rate limiting, and fast admission. Kafka will carry replayable asynchronous work after a durable intent exists. Changes will be introduced incrementally because the current database and Redis data may already be in use.

## Goals / Non-Goals

### Goals

- Enforce authentication, role, ownership, validation, and safe file boundaries at every write entry point.
- Guarantee that an accepted flash-order request has a durable, queryable intent and eventually reaches one terminal outcome without duplicate orders.
- Make cache locks, invalidation, Bloom admission, and Canal consumption safe across multiple application instances.
- Isolate agent sessions by owner and prevent unverified model output from reaching SSE clients.
- Give BM25 and vector retrieval a shared passage identity and make vector infrastructure explicitly optional.
- Make likes, follows, feeds, and nearby queries deterministic, idempotent, and bounded.
- Add migrations, configuration hygiene, observability, and integration release gates for the repaired flows.

### Non-Goals

- Replacing the custom login/session mechanism with a complete Spring Security migration.
- Replacing Kafka, Redis, MySQL, MyBatis-Plus, or the current LLM provider.
- Redesigning the user interface or changing unrelated business features.
- Providing exactly-once delivery across Redis, MySQL, and Kafka; the design instead provides durable intent plus idempotent processing.
- Re-indexing arbitrary external corpora or building a general document-management product.

## Decisions

### 1. Centralize authorization and request contracts around the existing token interceptor

The existing token interceptor remains responsible for resolving the current user. A new authorization service will apply role and resource-ownership rules from controllers and services. Merchant mutations will resolve the target merchant from MySQL and require either its owner or an administrator. Coupon writes will derive or validate the merchant through the same service instead of trusting a request-supplied merchant identifier. Every write service repeats the authorization check at the transactional boundary so an alternate entry point cannot bypass it.

Dedicated request DTOs with Jakarta Validation constraints will replace persistence entities at external write boundaries. A global exception handler will map missing authentication to `401`, insufficient rights to `403`, invalid input to `400`, conflicts to `409`, and throttling to `429`, with stable application error codes. This keeps the present authentication model while creating a single auditable permission contract. A broad Spring Security migration was considered, but it would enlarge the change without resolving resource ownership by itself.

Uploads will be recorded in a new `tb_upload_asset` table containing an opaque asset ID, owner ID, relative storage path, media type, size, hash, and lifecycle status. The API returns and later accepts this ID rather than a caller-controlled filesystem path. File creation validates allowed image media types using decoded content, enforces a configured byte limit, chooses a server-generated filename, and verifies the canonical destination remains below the configured upload root. Deletion loads the asset, verifies its owner or an administrator, checks canonical containment and symlinks again, then marks/deletes it. The mutating GET route is removed.

Verification codes will be consumed with one Redis Lua operation that compares the submitted value and deletes the key only on success. Separate Redis counters with short windows limit sends and verification attempts by phone number and request origin. Logs and error payloads will never include codes, tokens, prompts, passwords, or connection secrets.

### 2. Treat a MySQL order intent as the flash-order acceptance boundary

The API allocates an order ID before admission. A Redis Lua script performs fast stock and one-user-one-order checks and creates a provisional reservation identified by order ID, user ID, deal ID, and a lease expiry. After admission, one MySQL transaction conditionally decrements authoritative deal stock, inserts a `flash_order_intent`, and inserts a producer-outbox row. Uniqueness on order ID and `(user_id, deal_id)` makes retries converge on the same business result. The API reports acceptance only after this transaction commits.

If the database transaction fails, a compare-and-release Lua script compensates only the matching provisional reservation. A reconciler expires orphaned provisional reservations left by a process crash. This permits temporary underselling during a failure but prevents overselling or a success response without durable state. MySQL stock and accepted intents are authoritative; Redis stock and purchaser sets are rebuilt from them after data loss, and a deal remains closed while reconciliation detects an unexplained difference.

The outbox schema will require a unique event ID, aggregate ID, event type, payload, state, attempt count, next-attempt time, creation/update times, and last error. The relay claims rows in bounded batches and publishes with the event ID as the idempotency key. It marks a row published only after Kafka acknowledges it. Existing malformed rows are repaired or quarantined by migration before the new relay is enabled.

The Kafka listener will stop swallowing processing failures. It inserts or confirms the final voucher order transactionally and advances the intent status. Database uniqueness provides final idempotency; any Redis dedup marker is advisory and is written only after the database outcome is known. Spring Kafka will use a bounded retry policy and a dead-letter topic. An offset is committed only after the database commit or successful transfer to the dead-letter topic. Operators can replay a dead-letter event by event ID.

An authenticated order-status endpoint reads the intent/final order, verifies its owner, and returns `ACCEPTED`, `PROCESSING`, `SUCCEEDED`, or `FAILED` with order IDs serialized as strings. This status contract lets clients distinguish durable acceptance from final completion.

### 3. Separate cache data, locks, invalidation, and admission responsibilities

Cache rebuilds will use distinct lock keys such as `lock:cache:merchant:{id}`. Each acquisition stores a random owner token with an expiry; release uses a compare-and-delete Lua script. Cache misses store a short-lived negative sentinel, and concurrent cold misses use the lock with bounded retry and jitter so only one request rebuilds a key.

Transactions publish an internal change event only after commit. The handler evicts local Caffeine data and Redis entries, so a rollback cannot invalidate a valid cache. Duplicate invalidation events are harmless.

Canal integration becomes an optional Spring lifecycle component controlled by configuration. It reconnects with backoff, reads the correct before/after row image for insert, update, and delete, extracts real primary keys, applies table-specific invalidation, and acknowledges a batch only after all relevant invalidations succeed. Generic row changes will never delete authoritative flash stock.

New flash deals publish an after-commit admission event. Each healthy instance immediately adds the deal to its local Bloom filter; a Redis pub/sub or Kafka broadcast distributes the event, while scheduled rebuild remains a repair mechanism. If deal metadata is missing, the service returns an explicit unavailable/not-found response and does not manufacture stock state.

### 4. Make agent state owner-scoped and verify output before emission

Every agent session stores an immutable owner ID and a monotonically increasing version. Reads, continuation, persistence, deletion, and confirmation first compare the current user with that owner. Concurrent appends use a Redis Lua compare-and-set or a short owner-token lock so only one transition can commit for a given version.

Confirmation requests are stored in shared Redis under a random confirmation ID with owner, session, operation digest, and TTL. Confirmation uses one Lua operation to verify the owner and operation digest and consume the record. An unauthorized attempt neither reveals nor consumes another user's confirmation.

Model output is buffered for a complete assistant turn, then passed through safety checks and PII masking before any content chunk is sent to the SSE client. Sanitized text may then be emitted in chunks to retain the streaming response shape. Buffering increases time to first content, but it is the only simple boundary that also detects sensitive values split across model tokens. The orchestration path performs one model generation per turn; verification does not invoke a second generation.

Agent work runs on a dedicated bounded executor with queue limits and explicit generation/tool timeouts. Client disconnect, emitter timeout, and cancellation propagate to the future and provider call when supported. Completion is persisted once, and callbacks are guarded against double finalization.

### 5. Normalize RAG around a canonical passage model

The document loader will use a terminating sliding-window algorithm: emit `text[start:min(start + size, length)]`, stop after reaching the end, and otherwise advance by `size - overlap`. Configuration validation requires `size > overlap >= 0`. A non-empty document shorter than the window produces exactly one passage.

Both BM25 and vector indexing consume the same passage objects. The canonical identity is a stable value such as `<document-id>#<chunk-index>`, with document ID, chunk index, title, source, and category stored as metadata. Reciprocal-rank fusion merges only matching canonical IDs, retains per-channel scores for diagnostics, and limits passages per source document to prevent one document from occupying the result set.

Vector retrieval is controlled by an explicit `campusdeal.rag.vector.enabled` flag. When disabled, no embedding model, PostgreSQL connection, or vector schema is initialized. When enabled, the PostgreSQL driver, pooled datasource, extension/schema migration, dimensions, and connectivity are validated at startup. Failure of one retrieval channel produces a metric and structured health detail, then uses the healthy channel when policy permits; logs contain identifiers and counts rather than raw user queries.

### 6. Put durable uniqueness behind social actions and stable cursors in feeds

`tb_post_like` will hold the durable `(post_id, user_id)` relation with a unique constraint. Like/unlike transactions insert/delete that relation idempotently and update a denormalized count from the transition result. `tb_follow` receives a unique `(user_id, follow_user_id)` constraint, and self-follow is rejected. Redis mirrors may accelerate reads and ranking but are rebuilt from MySQL; periodic reconciliation repairs counters.

Feed pagination will use an opaque cursor encoding the last score plus a deterministic member tie-breaker. Queries continue strictly after that pair, which prevents duplicates and gaps when several posts share a timestamp or new posts arrive. Page hydration batches author and like-state reads rather than issuing one query per post.

Nearby lookup will first page a bounded Redis GEO index. Its database fallback uses a bounding box and indexed coordinates with a page limit before exact distance calculation. It will not load the entire merchant table into memory.

### 7. Establish migrations, configuration boundaries, and release gates

Flyway will own schema evolution. Existing deployments are baselined at the documented current schema, then receive additive versioned migrations for ownership, uploads, intents, outbox fields, likes, follows, constraints, and indexes. Migrations backfill deterministically where possible and quarantine ambiguous rows rather than silently granting access.

Repository configuration contains placeholders and safe local defaults only. Secrets and machine-specific paths come from environment variables or an ignored local profile. Optional Kafka, Canal, PostgreSQL/vector, and model integrations each have an explicit enable flag and do not create clients when disabled.

Micrometer metrics and health contributors will cover Kafka consumer lag and dead letters, outbox backlog/age/retries, intent age and terminal outcomes, Redis/MySQL stock differences, cache hit/miss/rebuild/lock contention, Canal lag/reconnects, Bloom repair, RAG channel degradation, agent executor saturation, timeouts, and safety rejections. Runbooks define alert thresholds, order reconciliation, dead-letter replay, stock rebuild, key rotation, and rollback.

Testcontainers-based integration suites will exercise MySQL, Redis, Kafka, and PostgreSQL/vector behavior with isolated data. Release gates include authorization matrix tests, upload boundary tests, verification-code concurrency tests, cache stampede tests, outbox replay and consumer retry tests, crash/reconciliation scenarios, session isolation, pre-emission safety tests, RAG fusion tests, and stable feed pagination.

## Migration Plan

1. Introduce Flyway baseline support, configuration flags, safe secret placeholders, health checks, and the new additive tables/columns/constraints. Backfill merchant owners and quarantine rows whose owner cannot be determined. Keep protected merchant writes disabled for unassigned rows.
2. Deploy the authorization service, DTO validation, upload-asset API, verification-code atomics, and rate limiting. Remove the GET deletion mapping after clients have moved to the ID-based delete operation.
3. Deploy corrected cache locks and after-commit invalidation. Enable lifecycle-managed Canal and distributed Bloom admission one integration at a time while monitoring lag and cache metrics.
4. Deploy flash intents and producer outbox in shadow comparison mode. Reconcile Redis/MySQL counts, then switch acceptance to the durable transaction. Drain legacy outbox rows through a converter/quarantine step before enabling the new relay and retry/DLT policy.
5. Deploy owner-scoped agent sessions, shared confirmations, bounded execution, and pre-emission verification. In-flight legacy sessions without an owner expire naturally or are assigned only through an audited migration.
6. Re-index RAG content into canonical passages. Enable BM25 first, validate results, then enable vector retrieval only after PostgreSQL/vector startup checks pass.
7. Backfill like/follow relations and reconcile counters, then switch writes and cursor pagination. Build bounded GEO indexes before enabling the new nearby path.
8. Remove compatibility paths after metrics show no legacy traffic and update operational documentation. Rollback uses feature flags and the previous application version; additive migrations remain in place, and accepted order intents continue draining until terminal.

## Risks / Trade-offs

- Persisting an intent before returning acceptance adds MySQL latency to successful flash requests. Redis still rejects most invalid traffic quickly; load tests and outbox batching will determine capacity.
- Existing merchant ownership may be incomplete. Denying ambiguous writes is safer but requires an administrative backfill workflow.
- Buffering an agent turn delays the first content chunk. The server can emit non-content progress events, but it cannot expose model text until verification completes.
- Changing the stock authority and event pipeline is operationally sensitive. Shadow comparison, explicit cutover flags, and stock-difference alarms reduce the risk.
- Distributed invalidation may deliver duplicate events. All handlers are intentionally idempotent, accepting extra work in exchange for simpler recovery.
- Real dependency integration tests increase CI time. Fast unit tests remain the default developer loop, while tagged integration suites run as merge and release gates.
