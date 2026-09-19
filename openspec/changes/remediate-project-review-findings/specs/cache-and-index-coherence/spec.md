# Spec Delta

## Purpose

定义缓存重建、空值保护、事务后失效、数据库变更订阅和活动准入索引之间的一致性边界，使缓存能够自动恢复且不会破坏秒杀权威状态。

## ADDED Requirements

### Requirement: Logical-expiry entries can be rebuilt safely
The system SHALL use a lock identity distinct from the cached data identity, SHALL grant ownership with a unique token, and SHALL release only a lock held by the same owner. A successful rebuild SHALL replace stale data without being deleted during lock release.

#### Scenario: Expired cached merchant is requested concurrently
- **WHEN** multiple callers read the same logically expired merchant
- **THEN** callers may receive the stale value while at most one rebuild runs and the refreshed value becomes visible afterward

#### Scenario: Lock lease changes owner
- **WHEN** a rebuild exceeds its lock lease and another owner acquires the lock
- **THEN** the original worker cannot delete the new owner's lock

### Requirement: Cache misses resist penetration and stampede
The system SHALL cache a bounded negative result for confirmed missing records and SHALL coordinate cold-cache database loads for the same identity.

#### Scenario: Missing merchant is repeatedly queried
- **WHEN** the database confirms that a merchant does not exist
- **THEN** subsequent requests within the negative TTL do not query the database again

#### Scenario: Popular key is cold
- **WHEN** concurrent requests miss the same valid merchant key
- **THEN** the system bounds concurrent database loads and all callers eventually observe the loaded value

### Requirement: Committed writes invalidate derived caches
The system SHALL invalidate or refresh affected cache entries only after the corresponding database transaction commits, including detail, list, geographic, and local in-process caches.

#### Scenario: Merchant update rolls back
- **WHEN** a merchant update transaction rolls back
- **THEN** the previously valid cache remains usable and no uncommitted value is published

#### Scenario: Merchant update commits
- **WHEN** a merchant update transaction commits
- **THEN** subsequent reads converge on the committed value without an old value being repopulated after invalidation

### Requirement: Change-data capture follows application lifecycle and row semantics
When enabled, the change-data-capture client SHALL start after application readiness and stop during shutdown. It SHALL derive identifiers from the correct table key and event image, acknowledge a batch only after all invalidations succeed, and retry an unacknowledged batch after failure.

#### Scenario: Row is deleted
- **WHEN** a subscribed row deletion event has no after image
- **THEN** the system resolves the identifier from the before image and invalidates the appropriate derived caches

#### Scenario: Flash-deal table changes
- **WHEN** a flash-deal row changes using `voucher_id` as its key
- **THEN** the system resolves that key correctly and does not delete authoritative live stock merely as a generic cache invalidation

### Requirement: Newly committed deals enter admission immediately
A successfully committed flash deal SHALL be recognized by every healthy application instance without waiting for the periodic full Bloom-filter rebuild.

#### Scenario: New deal is created
- **WHEN** a valid flash deal creation transaction returns success
- **THEN** subsequent requests are not rejected as nonexistent solely because a periodic admission-index rebuild has not run

#### Scenario: Admission metadata is missing or malformed
- **WHEN** a deal request cannot validate its activity window or stock metadata
- **THEN** the system follows an explicit safe recovery or unavailable response and does not convert the missing key into a permanent sold-out state
