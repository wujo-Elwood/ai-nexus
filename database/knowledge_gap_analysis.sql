USE rag_db;

-- 回答质量事实表：保存知识库问答的分析指标，不保存完整回答正文
CREATE TABLE IF NOT EXISTS ai_answer_quality (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    answer_message_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL,
    question VARCHAR(2000) NOT NULL,
    confidence INT NOT NULL DEFAULT 0,
    evidence_coverage INT NOT NULL DEFAULT 0,
    grounded TINYINT NOT NULL DEFAULT 0,
    refusal TINYINT NOT NULL DEFAULT 0,
    reason VARCHAR(120) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_answer_quality_time (create_time),
    INDEX idx_answer_quality_kb_time (kb_id, create_time),
    INDEX idx_answer_quality_message (answer_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 知识缺口报告表：每个时间窗口只保留最新报告快照
CREATE TABLE IF NOT EXISTS kb_gap_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    window_days INT NOT NULL,
    sample_count INT NOT NULL DEFAULT 0,
    refusal_count INT NOT NULL DEFAULT 0,
    low_confidence_count INT NOT NULL DEFAULT 0,
    negative_feedback_count INT NOT NULL DEFAULT 0,
    report_json LONGTEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'NOT_GENERATED',
    error_message TEXT,
    generated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_gap_report_window_days (window_days)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
