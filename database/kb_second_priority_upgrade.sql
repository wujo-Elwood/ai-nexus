-- 第二优先级企业能力升级，脚本可重复执行
USE rag_db;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'kb_knowledge_base' AND column_name = 'chunk_size') = 0,
  'ALTER TABLE kb_knowledge_base ADD COLUMN chunk_size INT NULL COMMENT ''知识库切片大小''', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'kb_knowledge_base' AND column_name = 'chunk_overlap') = 0,
  'ALTER TABLE kb_knowledge_base ADD COLUMN chunk_overlap INT NULL COMMENT ''知识库切片重叠长度''', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'kb_knowledge_base' AND column_name = 'top_k') = 0,
  'ALTER TABLE kb_knowledge_base ADD COLUMN top_k INT NULL COMMENT ''知识库最终召回数量''', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'kb_knowledge_base' AND column_name = 'similarity_threshold') = 0,
  'ALTER TABLE kb_knowledge_base ADD COLUMN similarity_threshold DECIMAL(6,4) NULL COMMENT ''知识库相似度阈值''', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'kb_knowledge_base' AND column_name = 'vector_weight') = 0,
  'ALTER TABLE kb_knowledge_base ADD COLUMN vector_weight DECIMAL(6,4) NULL COMMENT ''知识库向量权重''', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'kb_knowledge_base' AND column_name = 'keyword_weight') = 0,
  'ALTER TABLE kb_knowledge_base ADD COLUMN keyword_weight DECIMAL(6,4) NULL COMMENT ''知识库关键词权重''', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'kb_knowledge_base' AND column_name = 'heading_split_enabled') = 0,
  'ALTER TABLE kb_knowledge_base ADD COLUMN heading_split_enabled TINYINT NULL COMMENT ''是否启用标题层级切片''', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'kb_knowledge_base' AND column_name = 'table_keep_strategy') = 0,
  'ALTER TABLE kb_knowledge_base ADD COLUMN table_keep_strategy VARCHAR(20) NULL COMMENT ''表格保留策略''', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS kb_eval_case (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id BIGINT NOT NULL,
    question VARCHAR(1000) NOT NULL,
    expected_answer TEXT,
    expected_sources TEXT,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_eval_case_kb (kb_id), INDEX idx_eval_case_user (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kb_eval_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id BIGINT NOT NULL,
    run_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_count INT NOT NULL DEFAULT 0,
    hit_count INT NOT NULL DEFAULT 0,
    citation_hit_count INT NOT NULL DEFAULT 0,
    grounded_count INT NOT NULL DEFAULT 0,
    avg_latency_ms BIGINT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    parameter_snapshot TEXT,
    error_message TEXT,
    created_by BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    INDEX idx_eval_run_kb (kb_id), INDEX idx_eval_run_user (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kb_eval_run_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    case_id BIGINT NULL,
    question VARCHAR(1000) NOT NULL,
    result_status VARCHAR(20) NOT NULL,
    answer TEXT,
    hit TINYINT NOT NULL DEFAULT 0,
    citation_hit TINYINT NOT NULL DEFAULT 0,
    grounded TINYINT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    token_count INT NOT NULL DEFAULT 0,
    sources TEXT,
    error_message TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_eval_item_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

