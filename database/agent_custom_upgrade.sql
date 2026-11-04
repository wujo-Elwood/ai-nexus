-- 自定义智能体升级脚本：agent_info 增加创建人，普通用户可创建自己的智能体（按创建人隔离），兼容 MySQL 5.7，支持重复执行
USE rag_db;

SET @owner_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_info' AND COLUMN_NAME = 'create_user'
);
SET @owner_sql = IF(
    @owner_exists = 0,
    'ALTER TABLE agent_info ADD COLUMN create_user BIGINT NULL COMMENT ''创建人用户ID：NULL=平台内置，非空=用户自定义智能体（按创建人可见）''',
    'SELECT 1'
);
PREPARE owner_stmt FROM @owner_sql;
EXECUTE owner_stmt;
DEALLOCATE PREPARE owner_stmt;

-- 存量自定义智能体（此前由管理员在技能管理页创建）归属保持 NULL，按平台智能体展示
