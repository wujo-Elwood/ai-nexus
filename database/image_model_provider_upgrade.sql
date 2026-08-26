-- 生图模型配置升级脚本
-- 第1步：进入业务数据库
USE rag_db;

-- 第2步：给模型供应商表补充生图 API 地址
SET @has_image_base_url = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_model_provider'
      AND COLUMN_NAME = 'image_base_url'
);
SET @sql_image_base_url = IF(
    @has_image_base_url = 0,
    'ALTER TABLE ai_model_provider ADD COLUMN image_base_url VARCHAR(255) COMMENT ''生图 API 地址，为空时沿用普通 API 地址''',
    'SELECT 1'
);
PREPARE stmt FROM @sql_image_base_url;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 第3步：给模型供应商表补充生图 API 密钥
SET @has_image_api_key = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_model_provider'
      AND COLUMN_NAME = 'image_api_key'
);
SET @sql_image_api_key = IF(
    @has_image_api_key = 0,
    'ALTER TABLE ai_model_provider ADD COLUMN image_api_key VARCHAR(255) COMMENT ''生图 API 密钥，为空时沿用普通 API 密钥''',
    'SELECT 1'
);
PREPARE stmt FROM @sql_image_api_key;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 第4步：给模型供应商表补充生图模型名称
SET @has_image_model = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_model_provider'
      AND COLUMN_NAME = 'image_model'
);
SET @sql_image_model = IF(
    @has_image_model = 0,
    'ALTER TABLE ai_model_provider ADD COLUMN image_model VARCHAR(100) COMMENT ''生图模型名称，为空时沿用普通模型名称''',
    'SELECT 1'
);
PREPARE stmt FROM @sql_image_model;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
