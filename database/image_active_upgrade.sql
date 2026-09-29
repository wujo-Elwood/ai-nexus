USE rag_db;

-- 生图供应商标记：解耦生图与聊天模型切换（支持重复执行）
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'rag_db' AND TABLE_NAME = 'ai_model_provider' AND COLUMN_NAME = 'image_active');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE ai_model_provider ADD COLUMN image_active TINYINT DEFAULT 0 COMMENT ''生图供应商标记：1=当前生图使用，0=未使用''',
    'SELECT ''image_active 列已存在，跳过''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 初始化：沿用当前激活供应商作为生图供应商，保持升级前行为
UPDATE ai_model_provider SET image_active = 1 WHERE is_active = 1;
