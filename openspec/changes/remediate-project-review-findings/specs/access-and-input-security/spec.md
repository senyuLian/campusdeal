# Spec Delta

## Purpose

定义 CampusDeal 对业务写操作、上传资源和登录验证码的统一安全边界，使调用者身份、角色、资源归属和输入约束都能被稳定验证并形成一致的错误契约。

## ADDED Requirements

### Requirement: Business writes enforce authorization
The system SHALL require authentication for every upload, coupon, merchant, social, order, and Agent write operation, and SHALL authorize the authenticated principal against the required role and resource ownership before invoking the write service.

#### Scenario: Anonymous write request
- **WHEN** an anonymous caller invokes a protected write endpoint
- **THEN** the system returns HTTP 401 and performs no persistent or cached mutation

#### Scenario: Authenticated caller lacks authority
- **WHEN** an authenticated caller attempts an administrator-only operation or modifies a merchant or upload it does not own
- **THEN** the system returns HTTP 403 and performs no persistent or cached mutation

#### Scenario: Authorized owner or administrator
- **WHEN** a caller has the required role and owns the target resource, or has administrator authority
- **THEN** the system permits the validated operation

### Requirement: Upload storage is confined and attributable
The system SHALL accept only configured image media types and sizes, SHALL store files under the configured upload root using server-generated identifiers, and SHALL associate each upload with its owner. A resolved upload path MUST remain inside the upload root after normalization and symbolic-link resolution.

#### Scenario: Traversal or absolute deletion path
- **WHEN** a deletion request contains a parent traversal, absolute path, or symbolic-link escape
- **THEN** the system rejects the request without inspecting or deleting a file outside the upload root

#### Scenario: Unsupported upload content
- **WHEN** an upload exceeds the configured size or its detected content is not an allowed image type
- **THEN** the system rejects it before publishing the file to the static content path

#### Scenario: Owner deletes an upload
- **WHEN** the authenticated owner submits the server-issued upload identifier to the protected deletion endpoint
- **THEN** the system deletes only that upload and returns a successful result

### Requirement: Legacy GET deletion is removed
The system MUST NOT expose a GET endpoint that deletes an upload or any other resource.

#### Scenario: Legacy delete URL is requested
- **WHEN** a client invokes the former GET upload deletion URL
- **THEN** the system returns HTTP 404 or 405 and leaves all files unchanged

### Requirement: Inputs and errors follow one contract
The system SHALL bind write requests to dedicated input objects, validate required fields and numeric, time, pagination, coordinate, and message-length bounds, and map failures to stable application error codes with appropriate HTTP status codes.

#### Scenario: Invalid flash-deal window
- **WHEN** a coupon write request has missing stock, negative values, or an end time not after its begin time
- **THEN** the system returns HTTP 400 with a validation error code and persists nothing

#### Scenario: Unexpected server failure
- **WHEN** an unexpected exception prevents an operation from completing
- **THEN** the system returns HTTP 500 with a non-sensitive error code and does not expose stack traces or credentials

### Requirement: Verification codes are single-use and rate-limited
The system SHALL atomically validate and consume a verification code so one code can create at most one login session. It SHALL enforce configurable limits for code sends and failed validations by phone number and request origin.

#### Scenario: Concurrent reuse of a valid code
- **WHEN** two login requests concurrently submit the same valid verification code
- **THEN** exactly one request can create a session and the other receives an invalid-or-consumed-code response

#### Scenario: Verification abuse exceeds a limit
- **WHEN** a caller exceeds a configured send or failure threshold within its window
- **THEN** the system returns HTTP 429 and reports when a retry is allowed

### Requirement: Secrets are excluded from logs
The system MUST NOT log verification codes, complete login tokens, passwords, API keys, or complete authentication request bodies.

#### Scenario: Login and logout complete
- **WHEN** a verification code is sent or a session is created or destroyed
- **THEN** operational logs contain only non-sensitive correlation data and masked identifiers
