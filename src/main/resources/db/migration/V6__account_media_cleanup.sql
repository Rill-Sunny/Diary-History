CREATE TABLE account_media_cleanup (
    owner_id BIGINT NOT NULL,
    ready_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_id),
    KEY ix_account_media_cleanup_ready (ready_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
