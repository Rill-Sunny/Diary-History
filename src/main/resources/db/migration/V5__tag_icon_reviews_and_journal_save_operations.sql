CREATE TABLE tag_icon_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tag_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    submitted_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_at TIMESTAMP(3) NULL,
    reason VARCHAR(500) NULL,
    reviewer_id BIGINT NULL,
    PRIMARY KEY (id),
    KEY ix_tag_icon_reviews_tag (tag_id, id),
    KEY ix_tag_icon_reviews_status (status, submitted_at),
    CONSTRAINT fk_tag_icon_reviews_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE,
    CONSTRAINT fk_tag_icon_reviews_media FOREIGN KEY (media_id) REFERENCES media_assets(id) ON DELETE CASCADE,
    CONSTRAINT fk_tag_icon_reviews_reviewer FOREIGN KEY (reviewer_id) REFERENCES app_users(id) ON DELETE SET NULL,
    CONSTRAINT chk_tag_icon_reviews_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE tags ADD COLUMN chip_style VARCHAR(12) NOT NULL DEFAULT 'soft',
    ADD CONSTRAINT chk_tags_chip_style CHECK (chip_style IN ('soft', 'outline', 'solid'));

CREATE TABLE journal_save_operations (
    owner_id BIGINT NOT NULL,
    operation_id VARCHAR(120) NOT NULL,
    journal_id BIGINT NULL,
    journal_version BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    PRIMARY KEY (owner_id, operation_id),
    KEY ix_journal_save_operations_journal (journal_id),
    CONSTRAINT fk_journal_save_operations_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_journal_save_operations_journal FOREIGN KEY (journal_id) REFERENCES journals(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
