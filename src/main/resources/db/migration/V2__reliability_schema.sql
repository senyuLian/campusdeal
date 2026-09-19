-- Additive reliability and ownership schema. Ambiguous legacy rows are copied
-- to quarantine tables before uniqueness/ownership invariants are enforced.

CREATE TABLE IF NOT EXISTS outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id VARCHAR(128),
    topic VARCHAR(128),
    payload LONGTEXT,
    status VARCHAR(16),
    retry_count INT,
    error_msg VARCHAR(512),
    create_time TIMESTAMP NULL,
    update_time TIMESTAMP NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE tb_user
    ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'USER' AFTER icon;

ALTER TABLE tb_shop
    ADD COLUMN owner_user_id BIGINT UNSIGNED NULL AFTER type_id,
    ADD KEY idx_shop_owner (owner_user_id);

CREATE TABLE IF NOT EXISTS merchant_owner_quarantine (
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(128) NOT NULL,
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_quarantine_merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO merchant_owner_quarantine (merchant_id, reason)
SELECT id, 'OWNER_UNASSIGNED'
FROM tb_shop
WHERE owner_user_id IS NULL;

CREATE TABLE IF NOT EXISTS tb_upload_asset (
    asset_id VARCHAR(64) NOT NULL,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    relative_path VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_time TIMESTAMP NULL,
    PRIMARY KEY (asset_id),
    UNIQUE KEY uq_upload_path (relative_path),
    KEY idx_upload_owner_status (owner_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS flash_order_intent (
    order_id BIGINT NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    voucher_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    failure_reason VARCHAR(256),
    accepted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    UNIQUE KEY uq_intent_user_voucher (user_id, voucher_id),
    KEY idx_intent_status_time (status, accepted_at),
    KEY idx_intent_user_time (user_id, accepted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_post_like (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_post_like (post_id, user_id),
    KEY idx_post_like_user (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS social_relation_quarantine (
    id BIGINT NOT NULL AUTO_INCREMENT,
    relation_type VARCHAR(32) NOT NULL,
    relation_id BIGINT NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    target_user_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(128) NOT NULL,
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_social_quarantine_relation (relation_type, relation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve invalid/self and duplicate legacy follows before enforcing the pair key.
INSERT INTO social_relation_quarantine
        (relation_type, relation_id, user_id, target_user_id, reason)
SELECT 'FOLLOW', f.id, f.user_id, f.follow_user_id,
       CASE WHEN f.user_id = f.follow_user_id THEN 'SELF_FOLLOW' ELSE 'DUPLICATE_PAIR' END
FROM tb_follow f
WHERE f.user_id = f.follow_user_id
   OR EXISTS (
       SELECT 1 FROM tb_follow earlier
       WHERE earlier.user_id = f.user_id
         AND earlier.follow_user_id = f.follow_user_id
         AND earlier.id < f.id
   );

CREATE TEMPORARY TABLE tmp_follow_quarantine_ids (
    id BIGINT NOT NULL PRIMARY KEY
);

INSERT INTO tmp_follow_quarantine_ids (id)
SELECT f.id
FROM tb_follow f
WHERE f.user_id = f.follow_user_id
   OR EXISTS (
       SELECT 1 FROM tb_follow earlier
       WHERE earlier.user_id = f.user_id
         AND earlier.follow_user_id = f.follow_user_id
         AND earlier.id < f.id
   );

DELETE FROM tb_follow
WHERE id IN (SELECT id FROM tmp_follow_quarantine_ids);

DROP TEMPORARY TABLE tmp_follow_quarantine_ids;

ALTER TABLE tb_follow
    ADD UNIQUE KEY uq_follow_pair (user_id, follow_user_id),
    ADD KEY idx_follow_target (follow_user_id, create_time);

-- Capture malformed legacy Outbox rows before adding non-null lifecycle fields.
CREATE TABLE IF NOT EXISTS outbox_quarantine (
    id BIGINT NOT NULL AUTO_INCREMENT,
    original_id BIGINT,
    message_id VARCHAR(128),
    payload LONGTEXT,
    reason VARCHAR(256) NOT NULL,
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_outbox_quarantine_message (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO outbox_quarantine (original_id, message_id, payload, reason)
SELECT id, message_id, payload, 'MISSING_MESSAGE_ID'
FROM outbox
WHERE message_id IS NULL OR message_id = '';

DELETE FROM outbox WHERE message_id IS NULL OR message_id = '';

INSERT INTO outbox_quarantine (original_id, message_id, payload, reason)
SELECT duplicate.id, duplicate.message_id, duplicate.payload, 'DUPLICATE_MESSAGE_ID'
FROM outbox duplicate
JOIN outbox keeper
  ON keeper.message_id = duplicate.message_id
 AND keeper.id < duplicate.id;

DELETE duplicate
FROM outbox duplicate
JOIN outbox keeper
  ON keeper.message_id = duplicate.message_id
 AND keeper.id < duplicate.id;

UPDATE outbox
SET status = CASE WHEN status IN ('PENDING', 'PROCESSED', 'FAILED') THEN status ELSE 'PENDING' END,
    retry_count = COALESCE(retry_count, 0),
    create_time = COALESCE(create_time, CURRENT_TIMESTAMP),
    update_time = COALESCE(update_time, CURRENT_TIMESTAMP);

ALTER TABLE outbox
    MODIFY message_id VARCHAR(128) NOT NULL,
    MODIFY status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    MODIFY retry_count INT NOT NULL DEFAULT 0,
    MODIFY create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    MODIFY update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ADD UNIQUE KEY uq_outbox_message (message_id);

CREATE TABLE IF NOT EXISTS flash_dlt_replay_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    operator_user_id BIGINT UNSIGNED NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    error_message VARCHAR(240),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_flash_dlt_replay_event (event_id),
    KEY idx_flash_dlt_replay_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
