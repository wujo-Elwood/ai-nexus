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
-- 第1步 创建抽取文档表，保存上传后等待抽取的文档信息
CREATE TABLE IF NOT EXISTS extract_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_id BIGINT,
    document_name VARCHAR(255) NOT NULL COMMENT '文档名称',
    document_type VARCHAR(50) COMMENT '文档类型',
    document_status VARCHAR(20) DEFAULT 'UPLOADED' COMMENT '文档状态',
    storage_path VARCHAR(500) COMMENT '存储路径',
    create_user BIGINT COMMENT '创建用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_file_id (file_id),
    INDEX idx_document_status (document_status),
    INDEX idx_create_user (create_user)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第2步 创建抽取模板表，保存不同业务场景的模板信息
CREATE TABLE IF NOT EXISTS extract_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_code VARCHAR(50) NOT NULL COMMENT '模板编码',
    template_name VARCHAR(100) NOT NULL COMMENT '模板名称',
    template_desc VARCHAR(500) COMMENT '模板说明',
    template_status VARCHAR(20) DEFAULT 'ENABLED' COMMENT '模板状态',
    create_user BIGINT COMMENT '创建用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_template_code (template_code),
    INDEX idx_template_status (template_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第3步 创建抽取字段表，保存模板下需要识别的字段
CREATE TABLE IF NOT EXISTS extract_field (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_id BIGINT NOT NULL COMMENT '模板ID',
    field_key VARCHAR(50) NOT NULL COMMENT '字段键',
    field_name VARCHAR(100) NOT NULL COMMENT '字段名称',
    field_type VARCHAR(30) DEFAULT 'TEXT' COMMENT '字段类型',
    field_prompt VARCHAR(500) COMMENT '字段提示词',
    is_required TINYINT DEFAULT 0 COMMENT '是否必填：1=是，0=否',
    sort_order INT DEFAULT 0 COMMENT '排序号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_template_field_key (template_id, field_key),
    INDEX idx_template_id (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第4步 创建抽取任务表，保存每次文档抽取的执行过程
CREATE TABLE IF NOT EXISTS extract_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL COMMENT '文档ID',
    template_id BIGINT NOT NULL COMMENT '模板ID',
    task_status VARCHAR(20) DEFAULT 'PENDING' COMMENT '任务状态',
    error_message TEXT COMMENT '错误信息',
    start_time DATETIME COMMENT '开始时间',
    finish_time DATETIME COMMENT '完成时间',
    create_user BIGINT COMMENT '创建用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_document_id (document_id),
    INDEX idx_template_id (template_id),
    INDEX idx_task_status (task_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第5步 创建抽取结果表，保存字段级抽取结果
CREATE TABLE IF NOT EXISTS extract_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL COMMENT '任务ID',
    field_id BIGINT NOT NULL COMMENT '字段ID',
    field_key VARCHAR(50) NOT NULL COMMENT '字段键',
    field_value TEXT COMMENT '字段值',
    confidence DECIMAL(5,4) COMMENT '置信度',
    source_text TEXT COMMENT '来源文本',
    review_status VARCHAR(20) DEFAULT 'PENDING' COMMENT '审核状态',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_field_key (task_id, field_key),
    INDEX idx_task_id (task_id),
    INDEX idx_field_id (field_id),
    INDEX idx_review_status (review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第6步 创建审核记录表，保存人工复核时的修改痕迹
CREATE TABLE IF NOT EXISTS extract_review_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL COMMENT '任务ID',
    result_id BIGINT COMMENT '结果ID',
    field_key VARCHAR(50) COMMENT '字段键',
    original_value TEXT COMMENT '原始值',
    reviewed_value TEXT COMMENT '审核后值',
    review_action VARCHAR(20) NOT NULL COMMENT '审核动作',
    review_comment VARCHAR(500) COMMENT '审核备注',
    review_user BIGINT COMMENT '审核用户',
    review_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id),
    INDEX idx_result_id (result_id),
    INDEX idx_review_user (review_user)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第7步 创建导出记录表，保存抽取结果导出的文件信息
CREATE TABLE IF NOT EXISTS extract_export_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT COMMENT '任务ID',
    template_id BIGINT COMMENT '模板ID',
    export_type VARCHAR(30) NOT NULL COMMENT '导出类型',
    export_status VARCHAR(20) DEFAULT 'PENDING' COMMENT '导出状态',
    export_path VARCHAR(500) COMMENT '导出路径',
    error_message TEXT COMMENT '错误信息',
    create_user BIGINT COMMENT '创建用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    finish_time DATETIME COMMENT 'finish time',
    INDEX idx_task_id (task_id),
    INDEX idx_template_id (template_id),
    INDEX idx_export_status (export_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第8步 初始化合同基础信息抽取模板
INSERT INTO extract_template (template_code, template_name, template_desc, template_status, create_user)
SELECT 'contract_basic', '合同基础信息抽取', '抽取合同基础信息', 'ENABLED', 1
WHERE NOT EXISTS (
    SELECT 1 FROM extract_template WHERE template_code = 'contract_basic'
);

-- 第9步 初始化合同名称字段
INSERT INTO extract_field (template_id, field_key, field_name, field_type, field_prompt, is_required, sort_order)
SELECT t.id, 'contractName', '合同名称', 'TEXT', '抽取合同完整名称', 1, 10
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_key = 'contractName'
  );

-- 第10步 初始化合同编号字段
INSERT INTO extract_field (template_id, field_key, field_name, field_type, field_prompt, is_required, sort_order)
SELECT t.id, 'contractNo', '合同编号', 'TEXT', '抽取合同编号或协议编号', 0, 20
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_key = 'contractNo'
  );

-- 第11步 初始化甲方字段
INSERT INTO extract_field (template_id, field_key, field_name, field_type, field_prompt, is_required, sort_order)
SELECT t.id, 'partyA', '甲方', 'TEXT', '抽取合同甲方名称', 1, 30
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_key = 'partyA'
  );

-- 第12步 初始化乙方字段
INSERT INTO extract_field (template_id, field_key, field_name, field_type, field_prompt, is_required, sort_order)
SELECT t.id, 'partyB', '乙方', 'TEXT', '抽取合同乙方名称', 1, 40
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_key = 'partyB'
  );

-- 第13步 初始化合同金额字段
INSERT INTO extract_field (template_id, field_key, field_name, field_type, field_prompt, is_required, sort_order)
SELECT t.id, 'amount', '合同金额', 'NUMBER', '抽取合同总金额并保留币种信息', 0, 50
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_key = 'amount'
  );

-- 第14步 初始化签署日期字段
INSERT INTO extract_field (template_id, field_key, field_name, field_type, field_prompt, is_required, sort_order)
SELECT t.id, 'signDate', '签署日期', 'DATE', '抽取合同签署日期', 0, 60
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_key = 'signDate'
  );

-- 第15步 初始化开始日期字段
INSERT INTO extract_field (template_id, field_key, field_name, field_type, field_prompt, is_required, sort_order)
SELECT t.id, 'startDate', '开始日期', 'DATE', '抽取合同开始日期或生效日期', 0, 70
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_key = 'startDate'
  );

-- 第16步 初始化结束日期字段
INSERT INTO extract_field (template_id, field_key, field_name, field_type, field_prompt, is_required, sort_order)
SELECT t.id, 'endDate', '结束日期', 'DATE', '抽取合同结束日期或终止日期', 0, 80
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_key = 'endDate'
  );

-- 第17步 初始化联系人字段
INSERT INTO extract_field (template_id, field_key, field_name, field_type, field_prompt, is_required, sort_order)
SELECT t.id, 'contactPerson', '联系人', 'TEXT', '抽取联系人姓名', 0, 90
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_key = 'contactPerson'
  );

-- 第18步 初始化联系电话字段
INSERT INTO extract_field (template_id, field_key, field_name, field_type, field_prompt, is_required, sort_order)
SELECT t.id, 'contactPhone', '联系电话', 'TEXT', '抽取联系人电话', 0, 100
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_key = 'contactPhone'
  );
