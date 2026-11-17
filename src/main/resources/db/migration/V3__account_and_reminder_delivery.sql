ALTER TABLE app_users
    ADD COLUMN avatar_png MEDIUMBLOB NULL;

ALTER TABLE email_tokens
    ADD COLUMN target_email VARCHAR(254) NULL,
    ADD KEY ix_email_tokens_issued (user_id, purpose, created_at);

CREATE TABLE auth_mail_limits (
    ip_hash CHAR(64) NOT NULL,
    window_number BIGINT NOT NULL,
    request_count SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (ip_hash, window_number),
    KEY ix_auth_mail_limits_window (window_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE reminder_sends
    ADD COLUMN started_at TIMESTAMP(3) NULL,
    ADD KEY ix_reminder_owner_channel (owner_id, channel, local_date);
