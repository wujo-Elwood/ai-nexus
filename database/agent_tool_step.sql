USE rag_db;

-- 通用智能体工具调用步骤表：每调用一次工具记录一行，用于前端回放和事后排查
CREATE TABLE IF NOT EXISTS agent_tool_step (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(64) NOT NULL COMMENT '运行ID，一次对话请求一个',
    step_no INT NOT NULL COMMENT '步骤序号，从1开始',
    tool_name VARCHAR(100) NOT NULL COMMENT '工具名称',
    arguments TEXT COMMENT '模型给出的参数JSON',
    result TEXT COMMENT '工具返回结果',
    status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '执行状态：SUCCESS/FAILED',
    error_message TEXT COMMENT '失败原因',
    cost_ms BIGINT DEFAULT 0 COMMENT '耗时毫秒',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_run_id (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用智能体工具调用步骤';
