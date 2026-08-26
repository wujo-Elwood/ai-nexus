-- 知识库文件处理可靠性升级脚本，支持重复执行
USE rag_db;

SET @attempts_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_file' AND COLUMN_NAME = 'process_attempts'
);
SET @attempts_sql = IF(
    @attempts_exists = 0,
    'ALTER TABLE kb_file ADD COLUMN process_attempts INT NOT NULL DEFAULT 0 COMMENT ''文件处理累计尝试次数'' AFTER error_message',
    'SELECT 1'
);
PREPARE attempts_statement FROM @attempts_sql;
EXECUTE attempts_statement;
DEALLOCATE PREPARE attempts_statement;

SET @retry_time_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_file' AND COLUMN_NAME = 'next_retry_time'
);
SET @retry_time_sql = IF(
    @retry_time_exists = 0,
    'ALTER TABLE kb_file ADD COLUMN next_retry_time DATETIME NULL COMMENT ''下一次自动重试时间'' AFTER process_attempts',
    'SELECT 1'
);
PREPARE retry_time_statement FROM @retry_time_sql;
EXECUTE retry_time_statement;
DEALLOCATE PREPARE retry_time_statement;
