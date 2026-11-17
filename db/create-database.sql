-- 只为本项目创建独立数据库和账号。请先替换下方密码，并确认实例中没有同名业务库。
-- 本文件不会由应用自动执行；Flyway 首次启动时只在 liubai_diary 中创建项目表。
CREATE DATABASE IF NOT EXISTS liubai_diary
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'liubai_app'@'127.0.0.1'
    IDENTIFIED BY 'replace-this-example-password';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
    ON liubai_diary.* TO 'liubai_app'@'127.0.0.1';
