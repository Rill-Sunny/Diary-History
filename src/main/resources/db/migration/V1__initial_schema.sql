CREATE TABLE app_users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    email_normalized VARCHAR(254) NOT NULL,
    username VARCHAR(24) NOT NULL,
    username_normalized VARCHAR(24) NOT NULL,
    nickname VARCHAR(40) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    theme VARCHAR(16) NOT NULL DEFAULT 'light',
    accent VARCHAR(16) NOT NULL DEFAULT 'olive',
    auth_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_app_users_email (email_normalized),
    UNIQUE KEY uq_app_users_username (username_normalized),
    CONSTRAINT chk_app_users_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT chk_app_users_theme CHECK (theme IN ('light', 'dark')),
    CONSTRAINT chk_app_users_accent CHECK (accent IN ('olive', 'blue', 'rose'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE email_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    purpose VARCHAR(24) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    used_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_email_tokens_hash (token_hash),
    KEY ix_email_tokens_user_purpose (user_id, purpose, used_at),
    CONSTRAINT fk_email_tokens_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notebooks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    name VARCHAR(60) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    cover_media_id BIGINT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_notebooks_owner_name (owner_id, name),
    KEY ix_notebooks_owner (owner_id),
    CONSTRAINT fk_notebooks_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tags (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    name VARCHAR(40) NOT NULL,
    color CHAR(7) NOT NULL DEFAULT '#798744',
    icon_name VARCHAR(40) NOT NULL DEFAULT 'leaf',
    icon_media_id BIGINT NULL,
    icon_status VARCHAR(16) NOT NULL DEFAULT 'BUILT_IN',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tags_owner_name (owner_id, name),
    KEY ix_tags_owner (owner_id),
    CONSTRAINT fk_tags_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT chk_tags_color CHECK (color REGEXP '^#[0-9A-Fa-f]{6}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE media_assets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(64) NOT NULL,
    file_size BIGINT NOT NULL,
    stored_name VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_media_owner_hash (owner_id, sha256),
    UNIQUE KEY uq_media_stored_name (stored_name),
    KEY ix_media_owner (owner_id),
    CONSTRAINT fk_media_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE notebooks
    ADD CONSTRAINT fk_notebooks_cover FOREIGN KEY (cover_media_id) REFERENCES media_assets(id) ON DELETE SET NULL;

ALTER TABLE tags
    ADD CONSTRAINT fk_tags_icon_media FOREIGN KEY (icon_media_id) REFERENCES media_assets(id) ON DELETE SET NULL;

CREATE TABLE journals (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    notebook_id BIGINT NULL,
    client_id VARCHAR(120) NULL,
    journal_date DATE NOT NULL,
    title VARCHAR(255) NOT NULL DEFAULT '',
    automatic_title BOOLEAN NOT NULL DEFAULT TRUE,
    content_json JSON NOT NULL,
    plain_text MEDIUMTEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_journal_client_operation (owner_id, client_id),
    KEY ix_journals_owner_date (owner_id, journal_date, deleted_at),
    KEY ix_journals_owner_updated (owner_id, updated_at),
    FULLTEXT KEY ft_journals_search (title, plain_text),
    CONSTRAINT fk_journals_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_journals_notebook FOREIGN KEY (notebook_id) REFERENCES notebooks(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE journal_tags (
    owner_id BIGINT NOT NULL,
    journal_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (journal_id, tag_id),
    KEY ix_journal_tags_owner_tag (owner_id, tag_id),
    CONSTRAINT fk_journal_tags_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_journal_tags_journal FOREIGN KEY (journal_id) REFERENCES journals(id) ON DELETE CASCADE,
    CONSTRAINT fk_journal_tags_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE writing_activity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    journal_id BIGINT NULL,
    activity_key VARCHAR(160) NOT NULL,
    activity_date DATE NOT NULL,
    activity_type VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_activity_operation (owner_id, activity_key),
    KEY ix_activity_owner_date (owner_id, activity_date),
    CONSTRAINT fk_activity_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE share_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    journal_id BIGINT NOT NULL,
    journal_version BIGINT NOT NULL,
    title_snapshot VARCHAR(255) NOT NULL,
    journal_date_snapshot DATE NOT NULL,
    content_snapshot JSON NOT NULL,
    plain_text_snapshot MEDIUMTEXT NOT NULL,
    validity_days SMALLINT NOT NULL DEFAULT 30,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    submitted_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_at TIMESTAMP(3) NULL,
    review_reason VARCHAR(500) NULL,
    reviewer_id BIGINT NULL,
    PRIMARY KEY (id),
    KEY ix_share_requests_queue (status, submitted_at),
    KEY ix_share_requests_owner (owner_id, journal_id, submitted_at),
    CONSTRAINT fk_share_requests_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_share_requests_journal FOREIGN KEY (journal_id) REFERENCES journals(id) ON DELETE CASCADE,
    CONSTRAINT fk_share_requests_reviewer FOREIGN KEY (reviewer_id) REFERENCES app_users(id) ON DELETE SET NULL,
    CONSTRAINT chk_share_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_share_request_days CHECK (validity_days BETWEEN 1 AND 90)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE share_links (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    journal_id BIGINT NOT NULL,
    token_value CHAR(64) NOT NULL,
    current_request_id BIGINT NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    revoked_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_share_token (token_value),
    UNIQUE KEY uq_share_journal (journal_id),
    KEY ix_share_owner (owner_id),
    CONSTRAINT fk_share_links_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_share_links_journal FOREIGN KEY (journal_id) REFERENCES journals(id) ON DELETE CASCADE,
    CONSTRAINT fk_share_links_request FOREIGN KEY (current_request_id) REFERENCES share_requests(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE import_batches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    operation_id VARCHAR(120) NOT NULL,
    imported_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    result_json JSON NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_import_operation (owner_id, operation_id),
    CONSTRAINT fk_import_batches_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reminder_settings (
    owner_id BIGINT NOT NULL,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    push_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    reminder_time TIME NOT NULL DEFAULT '20:30:00',
    schedule_type VARCHAR(16) NOT NULL DEFAULT 'WEEKDAYS',
    weekdays_json JSON NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_id),
    CONSTRAINT fk_reminder_settings_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE push_subscriptions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    endpoint_hash CHAR(64) NOT NULL,
    endpoint TEXT NOT NULL,
    p256dh VARCHAR(256) NOT NULL,
    auth_secret VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_push_endpoint (owner_id, endpoint_hash),
    CONSTRAINT fk_push_subscriptions_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reminder_sends (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    local_date DATE NOT NULL,
    channel VARCHAR(12) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    last_error VARCHAR(300) NULL,
    sent_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_reminder_daily_channel (owner_id, local_date, channel),
    KEY ix_reminder_pending (status, local_date),
    CONSTRAINT fk_reminder_sends_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
