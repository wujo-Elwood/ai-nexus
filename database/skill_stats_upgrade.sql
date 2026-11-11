-- 技能调用统计升级脚本
-- 1. agent_tool_step 表：智能体工具调用步骤记录（此前只有实体与 Mapper，开发库缺表，
--    步骤落库一直被静默跳过，本脚本补建）。
-- 2. 表中直接携带 agent_code，用于统计各智能体的技能调用分布。
-- 3. 历史数据无需回填：表为新建，统计从本脚本执行后的调用开始累积。

USE rag_db;

CREATE TABLE IF NOT EXISTS agent_tool_step (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL COMMENT '对话运行编号，一次会话内所有步骤相同',
  agent_code VARCHAR(64) DEFAULT NULL COMMENT '智能体编码',
  step_no INT NOT NULL COMMENT '本次运行内的步骤序号',
  tool_name VARCHAR(64) NOT NULL COMMENT '技能编码',
  arguments TEXT COMMENT '模型给出的参数 JSON',
  result MEDIUMTEXT COMMENT '技能返回给模型的结果',
  status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '执行状态：SUCCESS/FAILED',
  error_message VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
  cost_ms BIGINT DEFAULT NULL COMMENT '执行耗时（毫秒）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_run_id (run_id),
  KEY idx_tool_name (tool_name),
  KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体技能调用步骤记录';

-- 旧版本已经建过 agent_tool_step 时，CREATE TABLE 不会自动补齐 agent_code。
SET @agent_code_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_tool_step'
    AND COLUMN_NAME = 'agent_code'
);
SET @agent_code_sql = IF(
  @agent_code_exists = 0,
  'ALTER TABLE agent_tool_step ADD COLUMN agent_code VARCHAR(64) DEFAULT NULL COMMENT ''智能体编码'' AFTER run_id',
  'SELECT 1'
);
PREPARE agent_code_stmt FROM @agent_code_sql;
EXECUTE agent_code_stmt;
DEALLOCATE PREPARE agent_code_stmt;

-- 旧表仅有运行编号索引时，补齐技能和时间维度统计索引。
SET @tool_name_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_tool_step'
    AND INDEX_NAME = 'idx_tool_name'
);
SET @tool_name_index_sql = IF(
  @tool_name_index_exists = 0,
  'ALTER TABLE agent_tool_step ADD INDEX idx_tool_name (tool_name)',
  'SELECT 1'
);
PREPARE tool_name_index_stmt FROM @tool_name_index_sql;
EXECUTE tool_name_index_stmt;
DEALLOCATE PREPARE tool_name_index_stmt;

SET @create_time_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_tool_step'
    AND INDEX_NAME = 'idx_create_time'
);
SET @create_time_index_sql = IF(
  @create_time_index_exists = 0,
  'ALTER TABLE agent_tool_step ADD INDEX idx_create_time (create_time)',
  'SELECT 1'
);
PREPARE create_time_index_stmt FROM @create_time_index_sql;
EXECUTE create_time_index_stmt;
DEALLOCATE PREPARE create_time_index_stmt;
