-- RAG Knowledge Base System Database Schema

CREATE DATABASE IF NOT EXISTS rag_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE rag_db;

-- User Table
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    nickname VARCHAR(50),
    avatar VARCHAR(255),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Knowledge Base Table
CREATE TABLE IF NOT EXISTS kb_knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    create_user BIGINT NOT NULL,
    visibility VARCHAR(20) DEFAULT 'PRIVATE' COMMENT '可见范围：PRIVATE=私有, PUBLIC=公开',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_create_user (create_user)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- File Table
CREATE TABLE IF NOT EXISTS kb_file (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(50),
    file_size BIGINT,
    file_path VARCHAR(500),
    status VARCHAR(20) DEFAULT 'UPLOADED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_kb_id (kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Text Chunk Table
CREATE TABLE IF NOT EXISTS kb_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT,
    source_info VARCHAR(255) COMMENT '来源信息，如文件名+段落序号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_file_id (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Chat Message Table
CREATE TABLE IF NOT EXISTS ai_chat_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content LONGTEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Model Provider Table
CREATE TABLE IF NOT EXISTS ai_model_provider (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL COMMENT '供应商名称',
    base_url VARCHAR(255) NOT NULL COMMENT 'API 基础地址',
    api_key VARCHAR(255) NOT NULL COMMENT 'API 密钥',
    model VARCHAR(100) NOT NULL COMMENT '模型名称',
    is_active TINYINT DEFAULT 0 COMMENT '是否激活：1=激活，0=未激活',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- Usage Log Table
CREATE TABLE IF NOT EXISTS ai_usage_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT,
    provider_id BIGINT,
    provider_name VARCHAR(50),
    model VARCHAR(100),
    prompt_tokens INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    total_tokens INT DEFAULT 0,
    duration_ms INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'SUCCESS',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Feedback Table
CREATE TABLE IF NOT EXISTS ai_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id BIGINT NOT NULL COMMENT '关联的助手消息ID',
    helpful TINYINT NOT NULL COMMENT '1=有帮助, 0=无帮助',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_message_id (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Document Extraction Module Tables
-- 第1步 创建抽取文档表，保存文档解析后的全文和上传信息
CREATE TABLE IF NOT EXISTS extract_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_name VARCHAR(255) NOT NULL COMMENT '文件名称',
    file_type VARCHAR(50) COMMENT '文件类型',
    file_size BIGINT COMMENT '文件大小',
    file_path VARCHAR(500) COMMENT '文件路径',
    parse_status VARCHAR(20) DEFAULT 'PENDING' COMMENT '解析状态',
    page_count INT DEFAULT 0 COMMENT '页数',
    full_text LONGTEXT COMMENT '全文内容',
    uploaded_by BIGINT COMMENT '上传用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_parse_status (parse_status),
    INDEX idx_uploaded_by (uploaded_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第2步 创建抽取模板表，保存模板基础信息
CREATE TABLE IF NOT EXISTS extract_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_name VARCHAR(100) NOT NULL COMMENT '模板名称',
    template_code VARCHAR(50) NOT NULL COMMENT '模板编码',
    document_type VARCHAR(50) COMMENT '文档类型',
    description VARCHAR(500) COMMENT '模板说明',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用：1=启用，0=停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_template_code (template_code),
    INDEX idx_document_type (document_type),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第3步 创建抽取字段表，保存模板字段配置
CREATE TABLE IF NOT EXISTS extract_field (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_id BIGINT NOT NULL COMMENT '模板ID',
    field_code VARCHAR(50) NOT NULL COMMENT '字段编码',
    field_name VARCHAR(100) NOT NULL COMMENT '字段名称',
    field_type VARCHAR(30) NOT NULL COMMENT '字段类型',
    required TINYINT DEFAULT 0 COMMENT '是否必填：1=是，0=否',
    multiple TINYINT DEFAULT 0 COMMENT '是否多值：1=是，0=否',
    field_prompt VARCHAR(500) COMMENT '字段提示词',
    example_value VARCHAR(255) COMMENT '示例值',
    regex_rule VARCHAR(255) COMMENT '正则规则',
    confidence_threshold DECIMAL(5,4) DEFAULT 0.8000 COMMENT '置信度阈值',
    sort_no INT DEFAULT 0 COMMENT '排序号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_template_field_code (template_id, field_code),
    INDEX idx_template_id (template_id),
    INDEX idx_field_code (field_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第4步 创建抽取任务表，保存任务执行状态
CREATE TABLE IF NOT EXISTS extract_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL COMMENT '文档ID',
    template_id BIGINT NOT NULL COMMENT '模板ID',
    task_status VARCHAR(20) DEFAULT 'PENDING' COMMENT '任务状态',
    task_message TEXT COMMENT '任务消息',
    started_at DATETIME COMMENT '开始时间',
    finished_at DATETIME COMMENT '完成时间',
    created_by BIGINT COMMENT '创建用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_document_id (document_id),
    INDEX idx_template_id (template_id),
    INDEX idx_task_status (task_status),
    INDEX idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第5步 创建抽取结果表，保存字段级抽取结果
CREATE TABLE IF NOT EXISTS extract_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL COMMENT '任务ID',
    document_id BIGINT NOT NULL COMMENT '文档ID',
    field_id BIGINT NOT NULL COMMENT '字段ID',
    field_code VARCHAR(50) NOT NULL COMMENT '字段编码',
    field_name VARCHAR(100) NOT NULL COMMENT '字段名称',
    field_value TEXT COMMENT '字段值',
    raw_text TEXT COMMENT '原始文本',
    page_no INT COMMENT '页码',
    confidence DECIMAL(5,4) COMMENT '置信度',
    result_status VARCHAR(20) DEFAULT 'PENDING' COMMENT '结果状态',
    is_modified TINYINT DEFAULT 0 COMMENT '是否修改：1=是，0=否',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id),
    INDEX idx_document_id (document_id),
    INDEX idx_field_id (field_id),
    INDEX idx_field_code (field_code),
    INDEX idx_result_status (result_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第6步 创建审核记录表，保存人工复核记录
CREATE TABLE IF NOT EXISTS extract_review_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    result_id BIGINT NOT NULL COMMENT '结果ID',
    old_value TEXT COMMENT '原值',
    new_value TEXT COMMENT '新值',
    review_by BIGINT COMMENT '审核用户',
    review_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '审核时间',
    remark VARCHAR(500) COMMENT '备注',
    INDEX idx_result_id (result_id),
    INDEX idx_review_by (review_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第7步 创建导出记录表，保存导出文件信息
CREATE TABLE IF NOT EXISTS extract_export_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL COMMENT '任务ID',
    export_type VARCHAR(30) NOT NULL COMMENT '导出类型',
    export_path VARCHAR(500) COMMENT '导出路径',
    exported_by BIGINT COMMENT '导出用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id),
    INDEX idx_exported_by (exported_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第8步 初始化合同基础信息抽取模板
INSERT INTO extract_template (template_name, template_code, document_type, description, enabled)
SELECT '合同基础信息抽取', 'contract_basic', 'CONTRACT', '抽取合同基础信息', 1
WHERE NOT EXISTS (
    SELECT 1 FROM extract_template WHERE template_code = 'contract_basic'
);

-- 第9步 初始化合同名称字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contractName', '合同名称', 'TEXT', 1, 0, '抽取合同完整名称', '采购合同', NULL, 0.8000, 10
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contractName'
  );

-- 第10步 初始化合同编号字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contractNo', '合同编号', 'TEXT', 0, 0, '抽取合同编号或协议编号', 'HT-2026-001', NULL, 0.8000, 20
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contractNo'
  );

-- 第11步 初始化甲方字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'partyA', '甲方', 'TEXT', 1, 0, '抽取合同甲方名称', '甲方公司', NULL, 0.8000, 30
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'partyA'
  );

-- 第12步 初始化乙方字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'partyB', '乙方', 'TEXT', 1, 0, '抽取合同乙方名称', '乙方公司', NULL, 0.8000, 40
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'partyB'
  );

-- 第13步 初始化合同金额字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'amount', '合同金额', 'AMOUNT', 0, 0, '抽取合同总金额并保留币种信息', '人民币10000元', NULL, 0.8000, 50
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'amount'
  );

-- 第14步 初始化签署日期字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'signDate', '签署日期', 'DATE', 0, 0, '抽取合同签署日期', '2026-01-01', NULL, 0.8000, 60
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'signDate'
  );

-- 第15步 初始化开始日期字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'startDate', '开始日期', 'DATE', 0, 0, '抽取合同开始日期或生效日期', '2026-01-01', NULL, 0.8000, 70
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'startDate'
  );

-- 第16步 初始化结束日期字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'endDate', '结束日期', 'DATE', 0, 0, '抽取合同结束日期或终止日期', '2026-12-31', NULL, 0.8000, 80
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'endDate'
  );

-- 第17步 初始化联系人字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contactPerson', '联系人', 'TEXT', 0, 0, '抽取联系人姓名', '张三', NULL, 0.8000, 90
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contactPerson'
  );

-- 第18步 初始化联系电话字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contactPhone', '联系电话', 'PHONE', 0, 0, '抽取联系人电话', '13800000000', NULL, 0.8000, 100
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contactPhone'
  );
