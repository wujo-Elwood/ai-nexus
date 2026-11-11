-- RAG Knowledge Base System Database Schema
--
-- 本文件包含当前全部表结构（含智能体步骤编排 agent_step、文档口径冲突 kb_conflict、
-- 评测指标 mrr/matched_rank、令牌版本 sys_user.token_version）。
-- 全新部署：直接执行本文件即可。
-- 已有库升级：按 README「历史数据库升级」的顺序执行 database 目录下的 *_upgrade.sql / *.sql。
-- 注意：kb_chunk 的全文索引（ngram 分词）不在本文件内，需单独执行
-- database/kb_fulltext_search_upgrade.sql，脚本会自行判断 ngram 支持性与索引是否已存在。

CREATE DATABASE IF NOT EXISTS rag_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE rag_db;

-- User Table
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    nickname VARCHAR(50),
    avatar VARCHAR(255),
    mascot_prefs VARCHAR(255),
    -- 令牌版本号：改密或被强制下线时递增，签发时写入 Token 的 ver claim，旧 Token 随即失效
    token_version INT NOT NULL DEFAULT 0,
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
    vision_parse_enabled TINYINT NULL DEFAULT 0 COMMENT '图片视觉解析开关：1=开启，图片内容走视觉模型入库',
    summary LONGTEXT NULL COMMENT '知识库文档摘要',
    summary_updated_at DATETIME NULL COMMENT '摘要生成时间',
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
-- 关键词召回用的全文索引不在建表语句里创建（部分数据库不支持 ngram 分词器会整表建失败），
-- 请在建库后执行 database/kb_fulltext_search_upgrade.sql，脚本会自动判断支持性与索引存在性
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
    user_id BIGINT NULL COMMENT '所属用户；历史记录无法确认归属时保持 NULL',
    session_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content LONGTEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_user_session_time (user_id, session_id, create_time)
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
    vision_base_url VARCHAR(255) COMMENT '视觉模型 API 地址，为空时沿用普通 API 地址',
    vision_api_key VARCHAR(255) COMMENT '视觉模型 API 密钥，为空时沿用普通 API 密钥',
    vision_model VARCHAR(100) COMMENT '视觉模型名称，为空时沿用普通模型名称',
    is_active TINYINT DEFAULT 0 COMMENT '是否激活：1=激活（聊天/智能体使用），0=未激活',
    image_active TINYINT DEFAULT 0 COMMENT '生图供应商标记：1=当前生图使用，0=未使用',
    vision_active TINYINT DEFAULT 0 COMMENT '视觉供应商标记：1=当前文档视觉解析使用，0=未使用',
    created_by BIGINT COMMENT '创建人用户ID，为空表示历史数据或系统预置，仅管理员可见',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_model_provider_creator (created_by)
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
    source VARCHAR(10) NOT NULL DEFAULT 'CHAT' COMMENT '反馈来源：CHAT=AI聊天消息，AGENT=智能体消息',
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

-- Answer quality facts used by knowledge gap analysis
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

-- Knowledge gap report snapshots for the 7-day and 30-day windows
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

-- 智能体注册表：名称/描述/系统提示词/启停入库，内置智能体 built_in=1；
-- create_user 为空表示平台内置，非空表示用户自定义智能体（按创建人可见）
CREATE TABLE IF NOT EXISTS agent_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_code VARCHAR(64) NOT NULL COMMENT '智能体编码，对话接口按此路由',
    agent_name VARCHAR(64) NOT NULL COMMENT '展示名称',
    description VARCHAR(512) DEFAULT NULL COMMENT '卡片描述',
    scene VARCHAR(255) DEFAULT NULL COMMENT '适用场景说明',
    system_prompt TEXT COMMENT '系统提示词，决定智能体行为',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 停用',
    built_in TINYINT NOT NULL DEFAULT 0 COMMENT '是否平台内置：1 内置不可删除',
    sort_no INT NOT NULL DEFAULT 100 COMMENT '卡片排序',
    create_user BIGINT NULL COMMENT '创建人用户ID：NULL=平台内置，非空=用户自定义（按创建人可见）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_code (agent_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体注册表';

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
    mrr DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '平均倒数排名 MRR：命中越靠前越接近 1',
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
    matched_rank INT NOT NULL DEFAULT 0 COMMENT '首个命中期望来源的排名（1 起，0 表示未命中）',
    citation_hit TINYINT NOT NULL DEFAULT 0,
    grounded TINYINT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    token_count INT NOT NULL DEFAULT 0,
    sources TEXT,
    error_message TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_eval_item_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Agent conversation history tables
CREATE TABLE IF NOT EXISTS agent_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '所属用户',
    agent_code VARCHAR(64) NOT NULL COMMENT '智能体编码',
    title VARCHAR(255) NOT NULL COMMENT '会话标题（首条用户消息截断）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_agent (user_id, agent_code, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体对话会话';

CREATE TABLE IF NOT EXISTS agent_conversation_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL COMMENT '所属会话',
    role VARCHAR(20) NOT NULL COMMENT '角色：user/assistant',
    content MEDIUMTEXT NOT NULL COMMENT '消息内容',
    steps_json MEDIUMTEXT COMMENT '助手消息的工具步骤 JSON（图表/导出恢复用）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_conversation (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体对话消息';

CREATE TABLE IF NOT EXISTS agent_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_code VARCHAR(64) NOT NULL COMMENT '智能体编码',
    skill_name VARCHAR(64) NOT NULL COMMENT '技能编码',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_skill (agent_code, skill_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体-技能绑定关系';

-- 智能体步骤编排：写了步骤的智能体按 step_no 顺序执行，未写步骤的走模型自主工具循环
CREATE TABLE IF NOT EXISTS agent_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_code VARCHAR(64) NOT NULL COMMENT '所属智能体编码',
    step_no INT NOT NULL COMMENT '执行顺序，从 1 开始',
    step_name VARCHAR(128) DEFAULT NULL COMMENT '步骤显示名称',
    step_type VARCHAR(16) NOT NULL DEFAULT 'TOOL' COMMENT '步骤类型：TOOL/LLM/ANSWER',
    tool_name VARCHAR(64) DEFAULT NULL COMMENT 'step_type=TOOL 时调用的技能编码',
    args_template TEXT COMMENT '工具参数模板 JSON，支持 ${input} 与 ${stepN} 占位符',
    instruction TEXT COMMENT 'step_type=LLM/ANSWER 时的指令文本',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 跳过',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_step (agent_code, step_no),
    KEY idx_agent_code (agent_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体步骤编排定义';

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

CREATE TABLE IF NOT EXISTS analysis_file (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '所属用户',
    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
    file_path VARCHAR(512) NOT NULL COMMENT '服务器存储路径',
    sheets_json TEXT COMMENT '表结构 JSON：[{name, columns:[{name,type}], rowCount}]',
    total_rows INT NOT NULL DEFAULT 0 COMMENT '总数据行数',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据分析-上传表格';

CREATE TABLE IF NOT EXISTS kb_grant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id BIGINT NOT NULL COMMENT '知识库编号',
    grantee_type VARCHAR(10) NOT NULL COMMENT '授权对象类型：USER=指定用户，ROLE=角色/组',
    grantee_id BIGINT NOT NULL COMMENT '授权对象编号：用户编号或角色编号',
    permission VARCHAR(10) NOT NULL DEFAULT 'READ' COMMENT '权限级别：READ=可阅读，MANAGE=可管理',
    create_user BIGINT NOT NULL COMMENT '授权操作人（知识库创建者）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_grant (kb_id, grantee_type, grantee_id),
    INDEX idx_grantee (grantee_type, grantee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库共享授权表';

CREATE TABLE IF NOT EXISTS skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_name VARCHAR(64) NOT NULL COMMENT '技能编码，对应技能实现的 name()',
    description VARCHAR(512) DEFAULT NULL COMMENT '技能说明，启动时按代码同步',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_skill_name (skill_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能注册表';

CREATE TABLE IF NOT EXISTS web_search_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL DEFAULT 0 COMMENT '所属用户：0=平台默认，其余为用户专属配置',
    provider VARCHAR(32) NOT NULL COMMENT '搜索源：tavily/serper/bocha/searxng',
    api_key VARCHAR(512) DEFAULT NULL COMMENT '搜索源 API Key；searxng 免 Key 可为空',
    base_url VARCHAR(512) DEFAULT NULL COMMENT '自建搜索源地址（searxng 必填），如 http://searxng:8080',
    max_results INT NOT NULL DEFAULT 5 COMMENT '单次搜索返回结果条数上限（1-10）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='web_search 技能搜索源配置';

CREATE TABLE IF NOT EXISTS kb_conflict (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id BIGINT NOT NULL COMMENT '知识库编号',
    chunk_a_id BIGINT NOT NULL COMMENT '冲突片段 A 的切片编号',
    chunk_b_id BIGINT NOT NULL COMMENT '冲突片段 B 的切片编号',
    file_a_id BIGINT DEFAULT NULL COMMENT '片段 A 所属文件编号',
    file_a_name VARCHAR(255) DEFAULT NULL COMMENT '片段 A 所属文件名',
    chunk_a_index INT DEFAULT NULL COMMENT '片段 A 在文件中的段落序号（从 0 开始）',
    content_a TEXT COMMENT '片段 A 内容快照，便于直接比对',
    file_b_id BIGINT DEFAULT NULL COMMENT '片段 B 所属文件编号',
    file_b_name VARCHAR(255) DEFAULT NULL COMMENT '片段 B 所属文件名',
    chunk_b_index INT DEFAULT NULL COMMENT '片段 B 在文件中的段落序号（从 0 开始）',
    content_b TEXT COMMENT '片段 B 内容快照',
    similarity FLOAT NOT NULL DEFAULT 0 COMMENT '两段文本的 Jaccard 相似度，越高越可能是同一话题',
    conflict_unit VARCHAR(16) DEFAULT NULL COMMENT '产生冲突的计量单位（天/月/%/元…）',
    value_a VARCHAR(64) DEFAULT NULL COMMENT '片段 A 在该单位上的取值',
    value_b VARCHAR(64) DEFAULT NULL COMMENT '片段 B 在该单位上的取值',
    reason VARCHAR(500) DEFAULT NULL COMMENT '自动生成的冲突说明',
    conflict_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=待确认，CONFIRMED=确认冲突，DISMISSED=已忽略',
    create_user BIGINT DEFAULT NULL COMMENT '触发检测的用户编号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_conflict_pair (kb_id, chunk_a_id, chunk_b_id),
    KEY idx_kb_conflict_status (kb_id, conflict_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档口径冲突';

