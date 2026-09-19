-- Reserve a durable quarantine state for malformed rows encountered while
-- draining the pre-intent outbox. Rows are retained for operator repair.
ALTER TABLE outbox
    ADD KEY idx_outbox_legacy_repair (status, message_id, update_time);
