-- 知识库摘要字段升级脚本，兼容 MySQL 5.7，支持重复执行
USE rag_db;

SET @summary_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_knowledge_base' AND COLUMN_NAME = 'summary'
);
SET @summary_sql = IF(
    @summary_exists = 0,
    'ALTER TABLE kb_knowledge_base ADD COLUMN summary LONGTEXT NULL COMMENT ''知识库文档摘要''',
    'SELECT 1'
);
PREPARE summary_statement FROM @summary_sql;
EXECUTE summary_statement;
DEALLOCATE PREPARE summary_statement;

SET @summary_time_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_knowledge_base' AND COLUMN_NAME = 'summary_updated_at'
);
SET @summary_time_sql = IF(
    @summary_time_exists = 0,
    'ALTER TABLE kb_knowledge_base ADD COLUMN summary_updated_at DATETIME NULL COMMENT ''摘要生成时间''',
    'SELECT 1'
);
PREPARE summary_time_statement FROM @summary_time_sql;
EXECUTE summary_time_statement;
DEALLOCATE PREPARE summary_time_statement;
