-- ============================================================
-- 关键词召回改用全文索引（kb_chunk.content）
-- 背景：原实现是 LIKE '%关键词%'，无法走索引、中文召回差
-- 行为：
--   1) 支持 ngram 分词器（MySQL 5.7+）→ 创建全文索引
--   2) 不支持（MariaDB、MySQL < 5.7 等）→ 自动跳过，脚本不报错
--   3) 索引已存在 → 跳过；表不存在 → 提示先执行 init.sql
-- 说明：只新增索引，不改表结构；后端 KeywordSearchService 优先用
--       MATCH ... AGAINST，索引缺失时自动回退 LIKE，可安全滚动发布
-- 实现：用 PREPARE 动态执行 DDL，避免在不支持 ngram 的数据库上
--       因为解析 `WITH PARSER ngram` 就整段脚本报错
-- 可重复执行
-- ============================================================

SET @has_table = (SELECT COUNT(1) FROM information_schema.TABLES
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunk');

SET @has_index = (SELECT COUNT(1) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunk'
                    AND INDEX_NAME = 'ft_kb_chunk_content');

SET @has_ngram = (SELECT COUNT(1) FROM information_schema.PLUGINS
                  WHERE PLUGIN_NAME = 'ngram' AND PLUGIN_STATUS = 'ACTIVE');

SET @ddl = CASE
    WHEN @has_table = 0 THEN
        'SELECT ''kb_chunk 表不存在，请先执行 init.sql 初始化数据库'' AS result'
    WHEN @has_index > 0 THEN
        'SELECT ''全文索引 ft_kb_chunk_content 已存在，跳过创建'' AS result'
    WHEN @has_ngram = 0 THEN
        'SELECT ''当前数据库不支持 ngram 分词器，已跳过全文索引；关键词召回自动回退 LIKE'' AS result'
    ELSE
        'ALTER TABLE kb_chunk ADD FULLTEXT INDEX ft_kb_chunk_content (content) WITH PARSER ngram'
END;

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 结果自检：无论走哪条分支，都能看清最终状态
SET @final_index = (SELECT COUNT(1) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunk'
                      AND INDEX_NAME = 'ft_kb_chunk_content');

SELECT CASE
    WHEN @final_index > 0 THEN 'OK：全文索引 ft_kb_chunk_content 已就绪，关键词召回走全文索引'
    ELSE 'OK：未建立全文索引，关键词召回走 LIKE（功能正常，仅性能略低）'
END AS result;

-- 验证（可选）：应能看到 ft_kb_chunk_content 这条 FULLTEXT 索引
-- SHOW INDEX FROM kb_chunk WHERE Index_type = 'FULLTEXT';
