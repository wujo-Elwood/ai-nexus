-- 文档抽取模块 V2 升级脚本
-- 第1步：进入业务数据库
USE rag_db;

-- 第2步：给抽取模板表补充创建人字段
SET @has_template_created_by = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'extract_template'
      AND COLUMN_NAME = 'created_by'
);
SET @sql_template_created_by = IF(
    @has_template_created_by = 0,
    'ALTER TABLE extract_template ADD COLUMN created_by BIGINT COMMENT ''创建用户''',
    'SELECT 1'
);
PREPARE stmt FROM @sql_template_created_by;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 第3步：给抽取模板表补充创建人索引
SET @has_template_created_by_index = (
    SELECT COUNT(1)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'extract_template'
      AND INDEX_NAME = 'idx_created_by'
);
SET @sql_template_created_by_index = IF(
    @has_template_created_by_index = 0,
    'ALTER TABLE extract_template ADD INDEX idx_created_by (created_by)',
    'SELECT 1'
);
PREPARE stmt FROM @sql_template_created_by_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 第4步：给抽取任务表补充进度字段
SET @has_task_progress = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'extract_task'
      AND COLUMN_NAME = 'progress'
);
SET @sql_task_progress = IF(
    @has_task_progress = 0,
    'ALTER TABLE extract_task ADD COLUMN progress INT DEFAULT 0 COMMENT ''任务进度''',
    'SELECT 1'
);
PREPARE stmt FROM @sql_task_progress;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 第5步：给抽取任务表补充失败原因字段
SET @has_task_error_message = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'extract_task'
      AND COLUMN_NAME = 'error_message'
);
SET @sql_task_error_message = IF(
    @has_task_error_message = 0,
    'ALTER TABLE extract_task ADD COLUMN error_message TEXT COMMENT ''失败原因''',
    'SELECT 1'
);
PREPARE stmt FROM @sql_task_error_message;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 第6步：给抽取结果表补充模型原始值字段
SET @has_result_original_value = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'extract_result'
      AND COLUMN_NAME = 'original_value'
);
SET @sql_result_original_value = IF(
    @has_result_original_value = 0,
    'ALTER TABLE extract_result ADD COLUMN original_value TEXT COMMENT ''模型原始值''',
    'SELECT 1'
);
PREPARE stmt FROM @sql_result_original_value;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 第7步：给抽取结果表补充人工修正值字段
SET @has_result_manual_value = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'extract_result'
      AND COLUMN_NAME = 'manual_value'
);
SET @sql_result_manual_value = IF(
    @has_result_manual_value = 0,
    'ALTER TABLE extract_result ADD COLUMN manual_value TEXT COMMENT ''人工修正值''',
    'SELECT 1'
);
PREPARE stmt FROM @sql_result_manual_value;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 第8步：给抽取结果表补充最终值字段
SET @has_result_final_value = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'extract_result'
      AND COLUMN_NAME = 'final_value'
);
SET @sql_result_final_value = IF(
    @has_result_final_value = 0,
    'ALTER TABLE extract_result ADD COLUMN final_value TEXT COMMENT ''最终值''',
    'SELECT 1'
);
PREPARE stmt FROM @sql_result_final_value;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 第9步：把老数据的字段值回填到模型原始值和最终值
UPDATE extract_result
SET original_value = field_value
WHERE original_value IS NULL;

-- 第10步：把老数据的最终值回填出来，保证前端可以统一读取
UPDATE extract_result
SET final_value = field_value
WHERE final_value IS NULL;
