USE rag_db;

-- 通用智能体工具调用步骤表：每调用一次工具记录一行，用于前端回放和事后排查
CREATE TABLE IF NOT EXISTS agent_tool_step (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(64) NOT NULL COMMENT '运行ID，一次对话请求一个',
    agent_code VARCHAR(64) DEFAULT NULL COMMENT '智能体编码',
    step_no INT NOT NULL COMMENT '步骤序号，从1开始',
    tool_name VARCHAR(100) NOT NULL COMMENT '工具名称',
    arguments TEXT COMMENT '模型给出的参数JSON',
    result MEDIUMTEXT COMMENT '工具返回结果',
    status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '执行状态：SUCCESS/FAILED',
    error_message VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
    cost_ms BIGINT DEFAULT NULL COMMENT '耗时毫秒',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_run_id (run_id),
    INDEX idx_tool_name (tool_name),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用智能体工具调用步骤';

-- 旧版本表可能已经存在但没有智能体编码，补齐统计模块需要的字段。
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
