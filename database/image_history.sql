-- AI 生图历史表
-- 第1步：进入业务数据库
USE rag_db;

-- 第2步：保存每次生成后的图片文件信息
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

-- 第3步：保存异步生图任务状态，页面切换后可以继续查看生成进度
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
