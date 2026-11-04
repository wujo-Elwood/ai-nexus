-- ============================================================================
-- 安全加固升级脚本：为 sys_user 增加令牌版本号
--
-- 背景：JWT 签发后在有效期内无法撤回，用户改密或管理员强制下线后旧 Token 仍然可用。
-- 增加 token_version 列后，签发 Token 时把版本号写入 ver claim，每次请求比对数据库值，
-- 不一致即判为失效；改密时自动递增，从而让旧 Token（含其他设备）立即失效。
--
-- 执行方式（与其它升级脚本一致，可重复执行）：
--   mysql -u root -p rag_db -e "source D:/workspace/ai-nexus/database/security_hardening_upgrade.sql"
-- ============================================================================

-- 第1步：探测 sys_user.token_version 是否已存在
SET @has_column = (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_user'
      AND COLUMN_NAME = 'token_version'
);

-- 第2步：不存在才执行 DDL（用 PREPARE 动态执行，脚本可重复运行不报错）
SET @ddl = CASE
    WHEN @has_column > 0 THEN 'SELECT ''sys_user.token_version 已存在，跳过'' AS result'
    ELSE 'ALTER TABLE sys_user ADD COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT ''令牌版本号：改密或强制下线时递增，使旧 Token 失效'''
END;

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 第3步：自检输出当前状态
SELECT
    'OK' AS status,
    (SELECT COUNT(1) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'token_version') AS token_version_column,
    (SELECT COUNT(1) FROM sys_user) AS total_users;
