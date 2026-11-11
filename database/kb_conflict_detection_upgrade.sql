-- =====================================================================
-- 文档口径冲突检测：kb_conflict 表
-- 说明：同一知识库里两段内容说的是同一件事，但数值/期限不一致（例如
--       "年假 5 天" 与 "年假 10 天"），检索时会被同时召回，导致回答
--       自相矛盾。本表保存检测出的冲突对，供人工确认口径以哪个为准。
-- 可重复执行：建表用 IF NOT EXISTS。
-- =====================================================================

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

-- 自检：应能查到 kb_conflict 表
SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_conflict';
