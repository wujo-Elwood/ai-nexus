-- 知识库大文件分片上传升级脚本，兼容 MySQL 5.7，支持重复执行
USE rag_db;

SET @session_table_exists = (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'kb_upload_session'
);
SET @session_sql = IF(
    @session_table_exists = 0,
    'CREATE TABLE kb_upload_session (
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
        status VARCHAR(20) NOT NULL DEFAULT ''UPLOADING'',
        error_message VARCHAR(500),
        expire_time DATETIME NOT NULL,
        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        INDEX idx_kb_upload_user (kb_id, create_user),
        INDEX idx_upload_expire (expire_time),
        INDEX idx_upload_status (status)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
    'SELECT 1'
);
PREPARE session_statement FROM @session_sql;
EXECUTE session_statement;
DEALLOCATE PREPARE session_statement;

SET @chunk_table_exists = (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'kb_upload_chunk'
);
SET @chunk_sql = IF(
    @chunk_table_exists = 0,
    'CREATE TABLE kb_upload_chunk (
        upload_id VARCHAR(64) NOT NULL,
        chunk_index INT NOT NULL,
        chunk_size BIGINT NOT NULL,
        chunk_sha256 VARCHAR(64),
        file_path VARCHAR(500) NOT NULL,
        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (upload_id, chunk_index),
        INDEX idx_upload_chunk_upload (upload_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
    'SELECT 1'
);
PREPARE chunk_statement FROM @chunk_sql;
EXECUTE chunk_statement;
DEALLOCATE PREPARE chunk_statement;
