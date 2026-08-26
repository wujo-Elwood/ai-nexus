-- Knowledge base file process status upgrade.
-- Run once for existing databases before starting the upgraded backend.

USE rag_db;

SET @process_stage_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'kb_file'
      AND COLUMN_NAME = 'process_stage'
);
SET @process_stage_sql = IF(
    @process_stage_exists = 0,
    'ALTER TABLE kb_file ADD COLUMN process_stage VARCHAR(30) DEFAULT ''UPLOADED'' COMMENT ''UPLOADED/PARSING/SPLITTING/VECTORIZING/COMPLETED/FAILED'' AFTER status',
    'SELECT 1'
);
PREPARE process_stage_statement FROM @process_stage_sql;
EXECUTE process_stage_statement;
DEALLOCATE PREPARE process_stage_statement;

SET @progress_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'kb_file'
      AND COLUMN_NAME = 'progress'
);
SET @progress_sql = IF(
    @progress_exists = 0,
    'ALTER TABLE kb_file ADD COLUMN progress INT DEFAULT 0 COMMENT ''process progress from 0 to 100'' AFTER process_stage',
    'SELECT 1'
);
PREPARE progress_statement FROM @progress_sql;
EXECUTE progress_statement;
DEALLOCATE PREPARE progress_statement;

SET @error_message_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'kb_file'
      AND COLUMN_NAME = 'error_message'
);
SET @error_message_sql = IF(
    @error_message_exists = 0,
    'ALTER TABLE kb_file ADD COLUMN error_message TEXT COMMENT ''file process failure reason'' AFTER progress',
    'SELECT 1'
);
PREPARE error_message_statement FROM @error_message_sql;
EXECUTE error_message_statement;
DEALLOCATE PREPARE error_message_statement;

UPDATE kb_file
SET process_stage = CASE status
        WHEN 'COMPLETED' THEN 'COMPLETED'
        WHEN 'FAILED' THEN 'FAILED'
        WHEN 'PROCESSING' THEN 'PARSING'
        ELSE 'UPLOADED'
    END,
    progress = CASE status
        WHEN 'COMPLETED' THEN 100
        WHEN 'PROCESSING' THEN 20
        ELSE 0
    END;
