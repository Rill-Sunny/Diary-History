ALTER TABLE share_requests ADD COLUMN updates_existing_link BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE share_requests r JOIN share_links l ON l.journal_id = r.journal_id
SET r.updates_existing_link = TRUE
WHERE r.status = 'PENDING' AND l.created_at <= r.submitted_at AND l.expires_at > r.submitted_at
  AND (l.revoked_at IS NULL OR l.revoked_at >= r.submitted_at);

ALTER TABLE app_users ADD COLUMN archive_id CHAR(36) NULL;
UPDATE app_users SET archive_id = UUID();
ALTER TABLE app_users MODIFY archive_id CHAR(36) NOT NULL DEFAULT (UUID()),
    ADD CONSTRAINT uq_archive_id UNIQUE (archive_id);

ALTER TABLE journals ADD COLUMN archive_origin VARCHAR(120) NULL,
    ADD INDEX ix_journal_archive_origin (owner_id, archive_origin);
ALTER TABLE writing_activity ADD COLUMN archive_origin VARCHAR(120) NULL;
