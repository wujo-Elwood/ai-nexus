-- 数据问答智能体只读账号：仅有 SELECT 权限，即使 SQL 校验被绕过也无法写入
-- 生产环境建议修改密码，并通过环境变量 DATA_AGENT_DB_USER / DATA_AGENT_DB_PASSWORD 覆盖应用配置
CREATE USER IF NOT EXISTS 'rag_readonly'@'%' IDENTIFIED BY 'AgentRead#2026';
GRANT SELECT ON rag_db.* TO 'rag_readonly'@'%';
FLUSH PRIVILEGES;
