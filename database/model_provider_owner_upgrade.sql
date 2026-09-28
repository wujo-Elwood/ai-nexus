-- 模型供应商归属字段升级脚本，兼容 MySQL 5.7，支持重复执行
USE rag_db;

SET @provider_creator_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_provider' AND COLUMN_NAME = 'created_by'
);
SET @provider_creator_sql = IF(
    @provider_creator_exists = 0,
    'ALTER TABLE ai_model_provider ADD COLUMN created_by BIGINT NULL COMMENT ''创建人用户ID，为空表示历史数据或系统预置，仅管理员可见''',
    'SELECT 1'
);
PREPARE provider_creator_statement FROM @provider_creator_sql;
EXECUTE provider_creator_statement;
DEALLOCATE PREPARE provider_creator_statement;

SET @provider_creator_index_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_provider' AND INDEX_NAME = 'idx_model_provider_creator'
);
SET @provider_creator_index_sql = IF(
    @provider_creator_index_exists = 0,
    'ALTER TABLE ai_model_provider ADD INDEX idx_model_provider_creator (created_by)',
    'SELECT 1'
);
PREPARE provider_creator_index_statement FROM @provider_creator_index_sql;
EXECUTE provider_creator_index_statement;
DEALLOCATE PREPARE provider_creator_index_statement;
