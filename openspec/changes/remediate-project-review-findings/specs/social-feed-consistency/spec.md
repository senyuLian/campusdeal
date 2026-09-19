# Spec Delta

## Purpose

定义点赞、关注、帖子计数和 Feed 滚动分页在重复请求、并发操作及大量同时间戳数据下的一致行为，并约束列表查询避免随结果数量线性放大数据库访问。

## ADDED Requirements

### Requirement: Like state transitions are idempotent
The system SHALL expose explicit like and unlike state transitions, enforce one like relation per user and post, and derive or reconcile the displayed count from durable relations so retries cannot increment or decrement more than once.

#### Scenario: Duplicate like requests
- **WHEN** the same user submits concurrent or repeated like requests for one post
- **THEN** one durable like exists and the post count increases by exactly one

#### Scenario: Duplicate unlike requests
- **WHEN** the same user submits repeated unlike requests
- **THEN** no like exists and the post count never becomes negative

### Requirement: Follow relations are unique and idempotent
The system SHALL enforce one follow relation for each follower-target pair and SHALL reject self-follow while treating repeated follow or unfollow requests as the requested final state.

#### Scenario: Concurrent follow requests
- **WHEN** a user concurrently follows the same target more than once
- **THEN** exactly one durable relation and one cached set member exist

#### Scenario: User follows itself
- **WHEN** a user attempts to follow its own identity
- **THEN** the system returns a validation error and changes no relation

### Requirement: Feed pagination is stable under equal scores
The system SHALL use a cursor that combines timestamp and deterministic tie position so each eligible feed item is returned once across consecutive pages, including when more items share a timestamp than fit in one page.

#### Scenario: Page boundary contains equal timestamps
- **WHEN** the last score in one page is shared by additional posts
- **THEN** the next cursor resumes after already returned members without skipping or repeating them

#### Scenario: New posts arrive during pagination
- **WHEN** newer posts are added after a client begins scrolling
- **THEN** continuing with the prior cursor preserves the prior snapshot ordering and does not insert duplicates into subsequent pages

### Requirement: List enrichment uses bounded data access
Merchant-nearby and post-list endpoints SHALL paginate before loading result entities and SHALL batch-load related users and like state so database and cache round trips do not grow one-for-one with result count.

#### Scenario: Post page contains multiple authors
- **WHEN** a page of posts is requested
- **THEN** author and viewer-like enrichment is fetched in bounded batches while preserving post order

#### Scenario: Nearby merchants are requested
- **WHEN** coordinates and a page cursor are supplied
- **THEN** only the requested candidate page is loaded and sorted, rather than loading the full merchant table into application memory
