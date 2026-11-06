-- ============================================================
-- StudyMate 增量升级脚本（已有库执行一次，可重复执行）
-- 适用：从旧版 schema 升级到 JWT 鉴权 / 幂等打卡 / 会话记忆 / 可恢复入库
-- ============================================================
USE studymate;

-- t_user.password（BCrypt）
SET @sql := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE t_user ADD COLUMN password VARCHAR(100) AFTER username',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_user' AND column_name = 'password');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 打卡幂等：唯一键 (task_id, user_id)
CREATE TABLE IF NOT EXISTS t_task_checkin (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_user (task_id, user_id),
    INDEX idx_user_time (user_id, created_at)
);

-- 会话记忆全量历史
CREATE TABLE IF NOT EXISTS t_chat_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    memory_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    model VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_memory (memory_id, id),
    INDEX idx_user (user_id, created_at)
);

-- 刷新令牌（哈希存储 + family 吊销）
CREATE TABLE IF NOT EXISTS t_refresh_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    family_id CHAR(36) NOT NULL,
    expires_at DATETIME NOT NULL,
    revoked TINYINT(1) NOT NULL DEFAULT 0,
    replaced_by CHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_token_hash (token_hash),
    INDEX idx_family (family_id),
    INDEX idx_user (user_id)
);

-- 知识库离线入库任务
CREATE TABLE IF NOT EXISTS t_kb_ingest_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_path VARCHAR(1024) NOT NULL,
    title VARCHAR(256),
    category VARCHAR(32) DEFAULT 'DOCUMENT',
    file_hash CHAR(64) NOT NULL,
    content_hash CHAR(64),
    material_id BIGINT,
    stage VARCHAR(32) NOT NULL DEFAULT 'SANITIZE',
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    clean_text MEDIUMTEXT,
    chunks_json MEDIUMTEXT,
    enhanced_json MEDIUMTEXT,
    error_message TEXT,
    retry_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_file_hash (file_hash),
    INDEX idx_status (status, updated_at)
);

-- PostgreSQL 侧（在 studymate 库执行）：
-- ALTER TABLE t_document_embedding ADD COLUMN IF NOT EXISTS parent_index INT DEFAULT 0;
-- ALTER TABLE t_document_embedding ADD COLUMN IF NOT EXISTS parent_content TEXT;
