-- 反馈来源升级脚本
-- ai_feedback 增加来源列：CHAT=AI 聊天消息，AGENT=智能体会话消息。
-- 两类消息分属不同表、ID 独立自增，反馈必须带来源才能唯一定位消息；
-- 历史数据全部来自 AI 聊天，默认 CHAT 无需迁移。
-- 同时本功能采用"每条消息只保留一条反馈"策略（换评价先删后插，取消即删除）。

USE rag_db;

-- 反馈来源字段支持重复执行，避免已经升级过的数据库再次执行时报重复列错误。
SET @source_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_feedback'
      AND COLUMN_NAME = 'source'
);
SET @source_sql = IF(
    @source_exists = 0,
    'ALTER TABLE ai_feedback ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT ''CHAT'' COMMENT ''反馈来源：CHAT=AI聊天消息，AGENT=智能体消息'' AFTER helpful',
    'SELECT 1'
);
PREPARE source_stmt FROM @source_sql;
EXECUTE source_stmt;
DEALLOCATE PREPARE source_stmt;
