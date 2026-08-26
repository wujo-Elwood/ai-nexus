-- 知识库企业能力升级脚本，兼容 MySQL 5.7，可重复执行
USE rag_db;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND COLUMN_NAME='version_no')=0,
 'ALTER TABLE kb_file ADD COLUMN version_no INT NOT NULL DEFAULT 1 COMMENT ''文件版本号'' AFTER file_path', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND COLUMN_NAME='version_group_id')=0,
 'ALTER TABLE kb_file ADD COLUMN version_group_id BIGINT NULL COMMENT ''同名文件版本组'' AFTER version_no', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND COLUMN_NAME='parent_version_id')=0,
 'ALTER TABLE kb_file ADD COLUMN parent_version_id BIGINT NULL COMMENT ''父版本文件ID'' AFTER version_group_id', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND COLUMN_NAME='file_sha256')=0,
 'ALTER TABLE kb_file ADD COLUMN file_sha256 CHAR(64) NULL COMMENT ''文件 SHA-256'' AFTER parent_version_id', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND COLUMN_NAME='folder_id')=0,
 'ALTER TABLE kb_file ADD COLUMN folder_id BIGINT NULL COMMENT ''目录ID'' AFTER file_sha256', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND COLUMN_NAME='category')=0,
 'ALTER TABLE kb_file ADD COLUMN category VARCHAR(64) NULL COMMENT ''文档分类'' AFTER folder_id', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND COLUMN_NAME='is_current')=0,
 'ALTER TABLE kb_file ADD COLUMN is_current TINYINT NOT NULL DEFAULT 1 COMMENT ''是否当前版本'' AFTER category', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND COLUMN_NAME='quality_status')=0,
 'ALTER TABLE kb_file ADD COLUMN quality_status VARCHAR(20) DEFAULT ''UNKNOWN'' COMMENT ''质量状态'' AFTER is_current', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND COLUMN_NAME='quality_score')=0,
 'ALTER TABLE kb_file ADD COLUMN quality_score DECIMAL(5,2) NULL COMMENT ''质量评分'' AFTER quality_status', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND COLUMN_NAME='vector_status')=0,
 'ALTER TABLE kb_file ADD COLUMN vector_status VARCHAR(20) DEFAULT ''UNKNOWN'' COMMENT ''向量状态'' AFTER quality_score', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND COLUMN_NAME='image_context')=0,
 'ALTER TABLE kb_file ADD COLUMN image_context TEXT NULL COMMENT ''图片文件名和文档上下文'' AFTER vector_status', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE kb_file SET version_no=1 WHERE version_no IS NULL OR version_no=0;
UPDATE kb_file SET version_group_id=id WHERE version_group_id IS NULL;
UPDATE kb_file SET is_current=1 WHERE is_current IS NULL;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND INDEX_NAME='uk_kb_file_sha256')=0,
 'ALTER TABLE kb_file ADD UNIQUE KEY uk_kb_file_sha256 (kb_id,file_sha256)', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_file' AND INDEX_NAME='idx_kb_file_version')=0,
 'ALTER TABLE kb_file ADD INDEX idx_kb_file_version (kb_id,file_name,is_current,version_no)', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

CREATE TABLE IF NOT EXISTS kb_file_folder (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, kb_id BIGINT NOT NULL, parent_id BIGINT NULL,
 name VARCHAR(128) NOT NULL, create_user BIGINT NOT NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_kb_folder_name (kb_id,parent_id,name), INDEX idx_kb_folder (kb_id,parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kb_file_tag (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, kb_id BIGINT NOT NULL, name VARCHAR(64) NOT NULL,
 color VARCHAR(16) NULL, create_user BIGINT NOT NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_kb_tag_name (kb_id,name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kb_file_tag_rel (
 file_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (file_id,tag_id), INDEX idx_tag_rel_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kb_file_version (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, file_id BIGINT NOT NULL, version_no INT NOT NULL,
 file_sha256 CHAR(64) NOT NULL, file_path VARCHAR(500) NOT NULL, is_current TINYINT NOT NULL DEFAULT 0,
 create_user BIGINT NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_file_version (file_id,version_no), INDEX idx_version_sha (file_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
