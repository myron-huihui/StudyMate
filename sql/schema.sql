-- StudyMate MySQL 建表脚本（全新安装）
CREATE DATABASE IF NOT EXISTS studymate DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE studymate;

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(100) COMMENT 'BCrypt 哈希，禁止明文',
    email VARCHAR(128),
    phone VARCHAR(32),
    role VARCHAR(32) DEFAULT 'USER',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 学习计划表
CREATE TABLE IF NOT EXISTS t_study_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    goal TEXT NOT NULL,
    goal_interpreted TEXT,
    daily_hours DECIMAL(4,1),
    current_level TEXT,
    total_days INT,
    status VARCHAR(32) DEFAULT 'DRAFT',
    progress INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user (user_id),
    INDEX idx_status (status)
);

-- 每日任务表
CREATE TABLE IF NOT EXISTS t_daily_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    day_index INT NOT NULL,
    date DATE,
    title VARCHAR(256) NOT NULL,
    description TEXT,
    estimated_minutes INT DEFAULT 30,
    status VARCHAR(32) DEFAULT 'PENDING',
    checked_in TINYINT(1) DEFAULT 0,
    checked_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_plan (plan_id),
    INDEX idx_plan_day (plan_id, day_index)
);

-- 学习资料表
CREATE TABLE IF NOT EXISTS t_learning_material (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    summary TEXT,
    category VARCHAR(32) DEFAULT 'DOCUMENT',
    source VARCHAR(512),
    difficulty INT DEFAULT 3,
    recommend_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_category (category)
);

-- 打卡记录：唯一键保证数据库级幂等（并发重复提交只成功一次）
CREATE TABLE IF NOT EXISTS t_task_checkin (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_user (task_id, user_id),
    INDEX idx_user_time (user_id, created_at)
);

-- 会话记忆全量历史（Redis 只存窗口，MySQL 存全量）
CREATE TABLE IF NOT EXISTS t_chat_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    memory_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL COMMENT 'user / assistant / system',
    content TEXT NOT NULL,
    model VARCHAR(64) COMMENT '仅审计，不参与上下文',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_memory (memory_id, id),
    INDEX idx_user (user_id, created_at)
);

-- 刷新令牌：只存 SHA-256 哈希；同 family 可整体吊销（复用检测）
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

-- 知识库离线入库任务：每阶段持久化，支持断点续跑
CREATE TABLE IF NOT EXISTS t_kb_ingest_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_path VARCHAR(1024) NOT NULL,
    title VARCHAR(256),
    category VARCHAR(32) DEFAULT 'DOCUMENT',
    file_hash CHAR(64) NOT NULL,
    content_hash CHAR(64),
    material_id BIGINT,
    stage VARCHAR(32) NOT NULL DEFAULT 'SANITIZE'
        COMMENT 'SANITIZE/DEDUP/CHUNK/ENHANCE/EMBED/STORE/DONE',
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING'
        COMMENT 'RUNNING/COMPLETED/FAILED/SKIPPED',
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
