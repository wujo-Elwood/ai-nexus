-- 吉祥物外观偏好字段升级脚本（跟随用户账号持久化花色/换装/名字，跨浏览器与设备保留），兼容 MySQL 5.7，支持重复执行
USE rag_db;

SET @prefs_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'mascot_prefs'
);
SET @prefs_sql = IF(
    @prefs_exists = 0,
    'ALTER TABLE sys_user ADD COLUMN mascot_prefs VARCHAR(255) NULL COMMENT ''吉祥物外观偏好 JSON（skin/outfit/name）''',
    'SELECT 1'
);
PREPARE prefs_stmt FROM @prefs_sql;
EXECUTE prefs_stmt;
DEALLOCATE PREPARE prefs_stmt;
