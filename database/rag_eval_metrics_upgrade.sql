-- ============================================================
-- RAG 评测台：补齐 MRR 与命中排名两列
-- 可重复执行：已存在时只输出提示，不报错
-- 说明：MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，
--       这里用 information_schema 探测 + PREPARE 动态执行 ALTER
-- ============================================================

-- 1) kb_eval_run.mrr：平均倒数排名
SET @has_mrr := (SELECT COUNT(1) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'kb_eval_run'
                   AND COLUMN_NAME = 'mrr');
SET @ddl := CASE WHEN @has_mrr > 0
    THEN 'SELECT ''kb_eval_run.mrr 已存在，跳过'' AS result'
    ELSE 'ALTER TABLE kb_eval_run ADD COLUMN mrr DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT ''平均倒数排名 MRR：命中越靠前越接近 1'' AFTER grounded_count'
END;
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) kb_eval_run_item.matched_rank：首个命中期望来源的排名
SET @has_rank := (SELECT COUNT(1) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = 'kb_eval_run_item'
                    AND COLUMN_NAME = 'matched_rank');
SET @ddl := CASE WHEN @has_rank > 0
    THEN 'SELECT ''kb_eval_run_item.matched_rank 已存在，跳过'' AS result'
    ELSE 'ALTER TABLE kb_eval_run_item ADD COLUMN matched_rank INT NOT NULL DEFAULT 0 COMMENT ''首个命中期望来源的排名（1 起，0 表示未命中）'' AFTER hit'
END;
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) 自检：两列是否都已就绪
SELECT
    (SELECT COUNT(1) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_eval_run' AND COLUMN_NAME = 'mrr') AS run_mrr_ready,
    (SELECT COUNT(1) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_eval_run_item' AND COLUMN_NAME = 'matched_rank') AS item_rank_ready;
