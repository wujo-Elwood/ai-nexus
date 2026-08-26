USE rag_db;

CREATE TABLE IF NOT EXISTS agent_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_code VARCHAR(80) NOT NULL COMMENT '智能体编码',
    agent_name VARCHAR(100) NOT NULL COMMENT '智能体名称',
    run_status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '运行状态',
    kb_id BIGINT COMMENT '知识库ID',
    kb_name VARCHAR(100) COMMENT '知识库名称快照',
    check_mode VARCHAR(20) COMMENT '检查模式',
    score INT DEFAULT 0 COMMENT '质量评分',
    risk_level VARCHAR(20) COMMENT '风险等级',
    summary VARCHAR(1000) COMMENT '报告摘要',
    request_json LONGTEXT COMMENT '运行参数JSON',
    report_json LONGTEXT COMMENT '完整报告JSON',
    error_message TEXT COMMENT '错误信息',
    created_by BIGINT COMMENT '运行用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_code (agent_code),
    INDEX idx_kb_id (kb_id),
    INDEX idx_created_by (created_by),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
