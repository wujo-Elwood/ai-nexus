USE rag_db;

-- 视觉解析升级：模型供应商增加视觉模型配置与标记，知识库增加视觉解析开关（支持重复执行）

-- 1. 视觉 API 地址：为空时沿用普通 API 基础地址
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'rag_db' AND TABLE_NAME = 'ai_model_provider' AND COLUMN_NAME = 'vision_base_url');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE ai_model_provider ADD COLUMN vision_base_url VARCHAR(255) DEFAULT NULL COMMENT ''视觉模型 API 地址，为空时沿用 base_url''',
    'SELECT ''vision_base_url 列已存在，跳过''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 视觉 API 密钥：为空时沿用普通 API 密钥
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'rag_db' AND TABLE_NAME = 'ai_model_provider' AND COLUMN_NAME = 'vision_api_key');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE ai_model_provider ADD COLUMN vision_api_key VARCHAR(255) DEFAULT NULL COMMENT ''视觉模型 API 密钥，为空时沿用 api_key''',
    'SELECT ''vision_api_key 列已存在，跳过''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 视觉模型名称：为空时沿用普通模型名称
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'rag_db' AND TABLE_NAME = 'ai_model_provider' AND COLUMN_NAME = 'vision_model');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE ai_model_provider ADD COLUMN vision_model VARCHAR(100) DEFAULT NULL COMMENT ''视觉模型名称，为空时沿用 model''',
    'SELECT ''vision_model 列已存在，跳过''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 视觉供应商标记：与聊天激活解耦，切聊天模型不影响文档视觉解析
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'rag_db' AND TABLE_NAME = 'ai_model_provider' AND COLUMN_NAME = 'vision_active');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE ai_model_provider ADD COLUMN vision_active TINYINT DEFAULT 0 COMMENT ''视觉供应商标记：1=当前文档视觉解析使用，0=未使用''',
    'SELECT ''vision_active 列已存在，跳过''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. 知识库视觉解析开关：默认关闭，存量知识库行为不变
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'rag_db' AND TABLE_NAME = 'kb_knowledge_base' AND COLUMN_NAME = 'vision_parse_enabled');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE kb_knowledge_base ADD COLUMN vision_parse_enabled TINYINT DEFAULT 0 COMMENT ''图片视觉解析开关：1=开启，图片内容走视觉模型入库''',
    'SELECT ''vision_parse_enabled 列已存在，跳过''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. 初始化：尚未设置视觉供应商时，沿用当前激活供应商，保持升级前可用性
SET @has_vision = (SELECT COUNT(*) FROM ai_model_provider WHERE vision_active = 1);
SET @ddl = IF(@has_vision = 0,
    'UPDATE ai_model_provider SET vision_active = 1 WHERE is_active = 1',
    'SELECT ''已存在视觉供应商，跳过初始化''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
