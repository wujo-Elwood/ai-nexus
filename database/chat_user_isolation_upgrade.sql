-- 聊天会话用户隔离升级脚本
-- 新消息写入当前认证用户编号，历史查询和删除按 user_id + session_id 限定。
-- 无法从旧数据可靠判断归属的消息保持 NULL，不把历史记录错误分配给某个用户。
-- 脚本支持重复执行，兼容已经存在旧版 ai_chat_message 的数据库。

USE rag_db;

-- 首次部署或旧库缺表时，先补齐当前聊天消息表结构。
CREATE TABLE IF NOT EXISTS ai_chat_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NULL COMMENT '所属用户；历史记录无法确认归属时保持 NULL',
    session_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content LONGTEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_user_session_time (user_id, session_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 旧版聊天表没有 user_id 时只新增可空字段，保留无法确认归属的历史数据。
SET @user_id_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_chat_message'
      AND COLUMN_NAME = 'user_id'
);
SET @user_id_sql = IF(
    @user_id_exists = 0,
    'ALTER TABLE ai_chat_message ADD COLUMN user_id BIGINT NULL COMMENT ''所属用户；历史记录无法确认归属时保持 NULL'' AFTER id',
    'SELECT 1'
);
PREPARE user_id_stmt FROM @user_id_sql;
EXECUTE user_id_stmt;
DEALLOCATE PREPARE user_id_stmt;

-- 为按用户和会话查询、删除增加联合索引。
SET @user_session_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_chat_message'
      AND INDEX_NAME = 'idx_user_session_time'
);
SET @user_session_index_sql = IF(
    @user_session_index_exists = 0,
    'ALTER TABLE ai_chat_message ADD INDEX idx_user_session_time (user_id, session_id, create_time)',
    'SELECT 1'
);
PREPARE user_session_index_stmt FROM @user_session_index_sql;
EXECUTE user_session_index_stmt;
DEALLOCATE PREPARE user_session_index_stmt;
