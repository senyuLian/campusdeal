# Spec Delta

## Purpose

定义智能客服会话、敏感操作确认和流式输出的用户隔离与安全约束，保证多实例和并发执行时不会泄露、覆盖或提前发送未经验证的内容。

## ADDED Requirements

### Requirement: Agent sessions are owned and isolated
Every Agent session SHALL have one immutable owner. Reading, continuing, saving, or deleting a session MUST validate the authenticated user against that owner before returning messages or changing state.

#### Scenario: Another user supplies a known session identifier
- **WHEN** an authenticated user requests or continues a session owned by another user
- **THEN** the system returns HTTP 403 or a non-enumerating HTTP 404 and reveals no session content

#### Scenario: New session is created
- **WHEN** an authenticated user starts a chat without a session identifier
- **THEN** the system issues a high-entropy identifier bound to that user and stores the owner with the session

### Requirement: Concurrent session turns do not overwrite each other
The system SHALL serialize or version concurrent updates to the same session so a stale full-history write cannot silently discard an accepted turn.

#### Scenario: Two turns complete concurrently
- **WHEN** two valid turns for the same session attempt to save from the same prior version
- **THEN** both are ordered and preserved, or one receives an explicit conflict that can be retried

### Requirement: Streamed output is safe before transmission
The system MUST apply configured PII masking, policy checks, and output validation before any corresponding text becomes observable in an SSE chunk. A later final event SHALL not be the first point at which unsafe text is corrected.

#### Scenario: Model output contains a phone number across chunk boundaries
- **WHEN** generated text contains protected personal information split across streaming tokens
- **THEN** no client event contains the unmasked value and the visible answer contains only the configured masked representation

#### Scenario: Output is rejected as unreliable
- **WHEN** output verification rejects a generated claim
- **THEN** the client receives only the safe replacement response and never receives the rejected claim in an earlier chunk

### Requirement: Sensitive confirmations work across instances
Pending confirmations SHALL be stored in a shared expiring store, bound to user, tool, arguments, target resource, and creation time. Confirmation ownership and expiry MUST be checked atomically before consuming the operation.

#### Scenario: Confirmation reaches another application instance
- **WHEN** the owner approves a valid confirmation through a different healthy instance
- **THEN** the operation executes at most once with the original reviewed arguments

#### Scenario: Unauthorized confirmation attempt
- **WHEN** another user submits a valid confirmation identifier
- **THEN** the system rejects the request without consuming the owner's pending confirmation

### Requirement: Agent execution uses bounded resources
Agent work SHALL run on a configured bounded executor with per-turn timeout and cancellation propagation when the client disconnects. A user turn SHALL perform no duplicate final-answer generation solely to switch from synchronous reasoning to streaming.

#### Scenario: Client disconnects during generation
- **WHEN** the SSE client disconnects before a turn completes
- **THEN** the system cancels remaining work where supported, releases request context, and does not leave an unbounded background task

#### Scenario: Model exceeds the turn timeout
- **WHEN** model or tool execution exceeds the configured timeout
- **THEN** the turn ends with a safe timeout event and records a timeout metric
