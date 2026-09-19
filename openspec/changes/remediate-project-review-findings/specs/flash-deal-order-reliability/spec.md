# Spec Delta

## Purpose

定义秒杀订单从受理、异步落库、失败补偿到状态查询和灾难恢复的可靠性约束，确保系统在进程、Redis、Kafka 或数据库故障下不丢单、不超卖且可对账。

## ADDED Requirements

### Requirement: Successful acceptance has a durable order intent
The system SHALL return a successful flash-deal acceptance only after an order intent containing order, user, deal, and timestamp identities is stored in a recoverable durability boundary.

#### Scenario: Process stops after stock reservation
- **WHEN** the application process stops after reserving stock but before normal asynchronous persistence completes
- **THEN** recovery can discover the accepted order intent and continue it to a terminal state without requiring the user to place a second order

#### Scenario: No durability path is available
- **WHEN** the system cannot durably record the order intent in any configured path
- **THEN** it does not report the order as accepted and it releases or makes recoverable the reservation

### Requirement: Duplicate requests converge on one order
The system SHALL enforce at most one active order per user and flash deal using a durable database constraint, and repeated requests SHALL return the existing order identity and current processing status rather than consume additional stock.

#### Scenario: User retries after a timeout
- **WHEN** a user repeats a request after the first request was durably accepted but its response was lost
- **THEN** the system returns the same logical order and does not decrement stock again

#### Scenario: Duplicate message is delivered
- **WHEN** Kafka or compensation delivers the same order intent more than once
- **THEN** the database contains one order and every duplicate is treated as complete only after that order is confirmed to exist

### Requirement: Consumer failures cause controlled redelivery or recovery
The system SHALL commit a consumed offset only after every covered order is durably persisted or durably transferred to a recoverable failure channel. Processing failures MUST trigger bounded retry and MUST NOT be interpreted as success solely because an in-memory or Redis marker exists.

#### Scenario: Database write fails for a batch
- **WHEN** a batch database write fails
- **THEN** the failed records remain eligible for redelivery or are durably moved to the configured recovery channel before offsets advance

#### Scenario: Retry budget is exhausted
- **WHEN** a message exceeds the configured retry budget
- **THEN** the system records the payload and failure reason in a dead-letter or failed state, advances only according to the recovery policy, and raises an operational alert

### Requirement: Outbox records have valid lifecycle metadata
Every Outbox record SHALL have non-null creation and update timestamps, a unique message identity, an explicit state, retry count, and last error. Only a confirmed order insert or confirmed existing order may transition an Outbox record to PROCESSED.

#### Scenario: Newly recorded pending message ages into eligibility
- **WHEN** a PENDING message is older than the configured compensation delay
- **THEN** the scheduler selects it for retry regardless of whether it originated from producer or consumer failure

#### Scenario: Retry insert fails after dedup acquisition
- **WHEN** compensation acquires a fast dedup marker but the database insert fails
- **THEN** the message remains PENDING or FAILED and a later attempt is not falsely marked PROCESSED

### Requirement: Stock recovery accounts for accepted and completed work
The system SHALL rebuild flash-deal stock and purchaser state from an authoritative snapshot that includes completed orders and accepted in-flight intents. It MUST prevent new acceptance while recovery cannot prove a safe remaining quantity.

#### Scenario: Redis state is lost with messages in flight
- **WHEN** Redis stock and purchaser keys are lost while accepted order intents have not all reached the order table
- **THEN** recovery includes those intents before reopening the deal and total accepted orders cannot exceed configured stock

#### Scenario: Recovery cannot reconcile state
- **WHEN** the system detects an unexplained difference among configured stock, reservations, messages, and orders
- **THEN** the affected deal is closed to new acceptance and an alert identifies the discrepancy

### Requirement: Order processing status is queryable
An authenticated user SHALL be able to query the processing state of its own accepted order using the string order identifier returned by the flash-deal API.

#### Scenario: Accepted order is still processing
- **WHEN** the owner queries an order that is durable but not yet inserted into the final order table
- **THEN** the system returns an ACCEPTED or PROCESSING state without exposing another user's information

#### Scenario: Order reaches a terminal result
- **WHEN** asynchronous processing completes or permanently fails
- **THEN** the status endpoint returns the corresponding terminal state and a stable reason code when failed
