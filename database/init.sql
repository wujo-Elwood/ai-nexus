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
    chunk_size INT NULL COMMENT '知识库切片大小',
    chunk_overlap INT NULL COMMENT '知识库切片重叠长度',
    top_k INT NULL COMMENT '知识库最终召回数量',
    similarity_threshold DECIMAL(6,4) NULL COMMENT '知识库相似度阈值',
    vector_weight DECIMAL(6,4) NULL COMMENT '知识库向量权重',
    keyword_weight DECIMAL(6,4) NULL COMMENT '知识库关键词权重',
    heading_split_enabled TINYINT NULL COMMENT '是否启用标题层级切片',
    table_keep_strategy VARCHAR(20) NULL COMMENT '表格保留策略',
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
    version_no INT NOT NULL DEFAULT 1 COMMENT '文件版本号',
    version_group_id BIGINT NULL COMMENT '同名文件版本组',
    parent_version_id BIGINT NULL COMMENT '父版本文件ID',
    file_sha256 CHAR(64) NULL COMMENT '文件 SHA-256',
    folder_id BIGINT NULL COMMENT '目录ID',
    category VARCHAR(64) NULL COMMENT '文档分类',
    is_current TINYINT NOT NULL DEFAULT 1 COMMENT '是否当前版本',
    quality_status VARCHAR(20) DEFAULT 'UNKNOWN' COMMENT '质量状态',
    quality_score DECIMAL(5,2) NULL COMMENT '质量评分',
    vector_status VARCHAR(20) DEFAULT 'UNKNOWN' COMMENT '向量状态',
    image_context TEXT NULL COMMENT '文档附加上下文',
    status VARCHAR(20) DEFAULT 'UPLOADED',
    process_stage VARCHAR(30) DEFAULT 'UPLOADED' COMMENT 'UPLOADED/PARSING/SPLITTING/VECTORIZING/COMPLETED/FAILED',
    progress INT DEFAULT 0 COMMENT '文件处理进度，范围 0 到 100',
    error_message TEXT COMMENT '文件处理失败原因',
    process_attempts INT NOT NULL DEFAULT 0 COMMENT '文件处理累计尝试次数',
    next_retry_time DATETIME NULL COMMENT '下一次自动重试时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_kb_id (kb_id),
    UNIQUE KEY uk_kb_file_sha256 (kb_id, file_sha256),
    INDEX idx_kb_file_version (kb_id, file_name, is_current, version_no)
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
    image_base_url VARCHAR(255) COMMENT '生图 API 地址，为空时沿用普通 API 地址',
    image_api_key VARCHAR(255) COMMENT '生图 API 密钥，为空时沿用普通 API 密钥',
    image_model VARCHAR(100) COMMENT '生图模型名称，为空时沿用普通模型名称',
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
    created_by BIGINT COMMENT '创建用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_template_code (template_code),
    INDEX idx_created_by (created_by),
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
    progress INT DEFAULT 0 COMMENT '任务进度',
    error_message TEXT COMMENT '失败原因',
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
    original_value TEXT COMMENT '模型原始值',
    manual_value TEXT COMMENT '人工修正值',
    final_value TEXT COMMENT '最终值',
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

-- 第9步 初始化合同基础信息抽取模板
INSERT INTO extract_template (template_name, template_code, document_type, description, enabled)
SELECT '合同基础信息抽取', 'contract_basic', 'CONTRACT', '抽取合同基础信息', 1
WHERE NOT EXISTS (
    SELECT 1 FROM extract_template WHERE template_code = 'contract_basic'
);

-- 第10步 初始化合同名称字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contractName', '合同名称', 'TEXT', 1, 0, '抽取合同完整名称', '采购合同', NULL, 0.8000, 10
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contractName'
  );

-- 第11步 初始化合同编号字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contractNo', '合同编号', 'TEXT', 0, 0, '抽取合同编号或协议编号', 'HT-2026-001', NULL, 0.8000, 20
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contractNo'
  );

-- 第12步 初始化甲方字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'partyA', '甲方', 'TEXT', 1, 0, '抽取合同甲方名称', '甲方公司', NULL, 0.8000, 30
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'partyA'
  );

-- 第13步 初始化乙方字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'partyB', '乙方', 'TEXT', 1, 0, '抽取合同乙方名称', '乙方公司', NULL, 0.8000, 40
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'partyB'
  );

-- 第14步 初始化合同金额字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'amount', '合同金额', 'AMOUNT', 0, 0, '抽取合同总金额并保留币种信息', '人民币10000元', NULL, 0.8000, 50
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'amount'
  );

-- 第15步 初始化签署日期字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'signDate', '签署日期', 'DATE', 0, 0, '抽取合同签署日期', '2026-01-01', NULL, 0.8000, 60
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'signDate'
  );

-- 第16步 初始化开始日期字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'startDate', '开始日期', 'DATE', 0, 0, '抽取合同开始日期或生效日期', '2026-01-01', NULL, 0.8000, 70
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'startDate'
  );

-- 第17步 初始化结束日期字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'endDate', '结束日期', 'DATE', 0, 0, '抽取合同结束日期或终止日期', '2026-12-31', NULL, 0.8000, 80
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'endDate'
  );

-- 第18步 初始化联系人字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contactPerson', '联系人', 'TEXT', 0, 0, '抽取联系人姓名', '张三', NULL, 0.8000, 90
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contactPerson'
  );

-- 第19步 初始化联系电话字段
INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contactPhone', '联系电话', 'PHONE', 0, 0, '抽取联系人电话', '13800000000', NULL, 0.8000, 100
FROM extract_template t
WHERE t.template_code = 'contract_basic'
  AND NOT EXISTS (
      SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contactPhone'
  );


-- RBAC Permission Module Tables
-- 第1步：创建系统菜单表
CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id BIGINT DEFAULT 0 COMMENT '父级菜单ID，0表示根节点',
    menu_name VARCHAR(100) NOT NULL COMMENT '菜单名称',
    path VARCHAR(200) DEFAULT '' COMMENT '前端路由地址',
    route_name VARCHAR(100) DEFAULT '' COMMENT '前端路由名称',
    component VARCHAR(255) DEFAULT '' COMMENT '前端组件路径',
    icon VARCHAR(80) DEFAULT '' COMMENT '菜单图标',
    menu_type VARCHAR(20) DEFAULT 'MENU' COMMENT '菜单类型：DIR=目录，MENU=菜单',
    permission_code VARCHAR(120) DEFAULT '' COMMENT '权限标识',
    sort_no INT DEFAULT 0 COMMENT '排序号',
    visible TINYINT DEFAULT 1 COMMENT '是否可见：1=显示，0=隐藏',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用：1=启用，0=停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_parent_id (parent_id),
    INDEX idx_permission_code (permission_code),
    INDEX idx_sort_no (sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第2步：创建系统角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_name VARCHAR(80) NOT NULL COMMENT '角色名称',
    role_code VARCHAR(80) NOT NULL COMMENT '角色编码',
    description VARCHAR(500) DEFAULT '' COMMENT '角色说明',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用：1=启用，0=停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_role_code (role_code),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第3步：创建用户角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_role (user_id, role_id),
    INDEX idx_user_id (user_id),
    INDEX idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第4步：创建角色菜单关联表
CREATE TABLE IF NOT EXISTS sys_role_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    INDEX idx_role_id (role_id),
    INDEX idx_menu_id (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第5步：初始化平台菜单入口
INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '知识库', '/kb', 'KnowledgeBase', 'KbView', 'database', 'MENU', 'kb:view', 10, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'kb:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, 'AI 聊天', '/chat', 'Chat', 'ChatView', 'message-circle', 'MENU', 'chat:view', 20, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'chat:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, 'AI 生图', '/image', 'ImageGenerate', 'ImageGenerateView', 'image', 'MENU', 'image:view', 30, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'image:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '文档抽取', '/extract', 'Extract', 'ExtractView', 'file-text', 'MENU', 'extract:view', 40, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'extract:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '智能体管理', '/agents', 'Agents', 'AgentView', 'cpu', 'MENU', 'agents:view', 50, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'agents:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '模型设置', '/settings', 'ModelSettings', 'ModelSettings', 'settings', 'MENU', 'settings:view', 60, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'settings:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '用量统计', '/stats', 'Stats', 'StatsView', 'bar-chart', 'MENU', 'stats:view', 70, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'stats:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '权限管理', '/rbac', 'RbacManage', 'RbacManageView', 'shield', 'MENU', 'rbac:manage', 80, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'rbac:manage');

-- 第6步：初始化平台角色
INSERT INTO sys_role (role_name, role_code, description, enabled)
SELECT '平台管理员', 'admin', '拥有平台全部菜单和权限管理能力', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'admin');

INSERT INTO sys_role (role_name, role_code, description, enabled)
SELECT '普通用户', 'user', '拥有知识库、聊天、生图、文档抽取和智能体入口', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'user');

-- 第7步：给管理员角色授权全部菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
INNER JOIN sys_menu m ON 1 = 1
WHERE r.role_code = 'admin';

-- 第8步：给普通用户角色授权业务菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
INNER JOIN sys_menu m ON m.permission_code IN ('kb:view', 'chat:view', 'image:view', 'extract:view', 'agents:view')
WHERE r.role_code = 'user';

-- 第9步：把 admin 用户挂到管理员角色
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
INNER JOIN sys_role r ON r.role_code = 'admin'
WHERE u.username = 'admin';

-- 第10步：给没有角色的历史用户补普通用户角色
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
INNER JOIN sys_role r ON r.role_code = 'user'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id
);

-- Agent Management Module Table
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

-- Knowledge Base Enterprise Capability Tables
CREATE TABLE IF NOT EXISTS kb_upload_session (
    upload_id VARCHAR(64) PRIMARY KEY,
    kb_id BIGINT NOT NULL,
    create_user BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(100),
    file_size BIGINT NOT NULL,
    file_sha256 VARCHAR(64),
    chunk_size BIGINT NOT NULL,
    total_chunks INT NOT NULL,
    uploaded_chunks INT DEFAULT 0,
    uploaded_bytes BIGINT DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADING',
    error_message VARCHAR(500),
    expire_time DATETIME NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_kb_upload_user (kb_id, create_user),
    INDEX idx_upload_expire (expire_time),
    INDEX idx_upload_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kb_upload_chunk (
    upload_id VARCHAR(64) NOT NULL,
    chunk_index INT NOT NULL,
    chunk_size BIGINT NOT NULL,
    chunk_sha256 VARCHAR(64),
    file_path VARCHAR(500) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (upload_id, chunk_index),
    INDEX idx_upload_chunk_upload (upload_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kb_file_folder (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    name VARCHAR(128) NOT NULL,
    create_user BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_folder_name (kb_id, parent_id, name),
    INDEX idx_kb_folder (kb_id, parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kb_file_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    color VARCHAR(16) NULL,
    create_user BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_tag_name (kb_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kb_file_tag_rel (
    file_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (file_id, tag_id),
    INDEX idx_tag_rel_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kb_file_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    is_current TINYINT NOT NULL DEFAULT 0,
    create_user BIGINT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_file_version (file_id, version_no),
    INDEX idx_version_sha (file_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI Image Generation Tables
CREATE TABLE IF NOT EXISTS ai_image_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    prompt TEXT NOT NULL COMMENT '生图提示词',
    provider_name VARCHAR(80) COMMENT '供应商名称',
    model_name VARCHAR(120) COMMENT '生图模型名称',
    image_size VARCHAR(30) COMMENT '图片尺寸',
    file_name VARCHAR(255) NOT NULL COMMENT '保存文件名',
    file_path VARCHAR(500) NOT NULL COMMENT '磁盘文件路径',
    mime_type VARCHAR(60) DEFAULT 'image/png' COMMENT '图片媒体类型',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小',
    created_by BIGINT COMMENT '创建用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created_by (created_by),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_image_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    prompt TEXT NOT NULL COMMENT '生图提示词',
    image_size VARCHAR(30) COMMENT '图片尺寸',
    image_count INT DEFAULT 1 COMMENT '生成数量',
    task_status VARCHAR(20) DEFAULT 'PENDING' COMMENT '任务状态',
    task_message VARCHAR(255) COMMENT '任务消息',
    progress INT DEFAULT 0 COMMENT '任务进度',
    provider_name VARCHAR(80) COMMENT '供应商名称',
    model_name VARCHAR(120) COMMENT '生图模型名称',
    request_json LONGTEXT COMMENT '请求参数JSON',
    response_json LONGTEXT COMMENT '生成结果JSON',
    error_message TEXT COMMENT '失败原因',
    started_at DATETIME COMMENT '开始时间',
    finished_at DATETIME COMMENT '完成时间',
    created_by BIGINT COMMENT '创建用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_image_task_created_by (created_by),
    INDEX idx_image_task_status (task_status),
    INDEX idx_image_task_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- RAG Evaluation Tables
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
    INDEX idx_eval_case_kb (kb_id),
    INDEX idx_eval_case_user (created_by)
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
    INDEX idx_eval_run_kb (kb_id),
    INDEX idx_eval_run_user (created_by)
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

-- New Platform Menus
INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '系统健康', '/health', 'SystemHealth', 'SystemHealthView', 'monitor', 'MENU', 'health:view', 75, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'health:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '任务中心', '/tasks', 'TaskCenter', 'TaskCenterView', 'list-check', 'MENU', 'tasks:view', 90, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'tasks:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, 'RAG 评测', '/eval', 'EvalCenter', 'EvalCenterView', 'clipboard-check', 'MENU', 'eval:view', 100, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'eval:view');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
INNER JOIN sys_menu m ON m.permission_code IN ('health:view', 'tasks:view', 'eval:view')
WHERE r.role_code = 'admin';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
INNER JOIN sys_menu m ON m.permission_code IN ('tasks:view', 'eval:view')
WHERE r.role_code = 'user';
